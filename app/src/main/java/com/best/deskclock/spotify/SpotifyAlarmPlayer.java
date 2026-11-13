// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.utils.LogUtils;

import java.lang.ref.WeakReference;

/**
 * Plays an alarm through the Spotify app.
 *
 * <p>An alarm must never stay silent, so this player treats Spotify as a best effort and keeps
 * watching the audio output:</p>
 * <ol>
 *     <li>It asks Spotify to play the chosen content, in a task launched behind the alarm screen
 *     so the alarm stays in front.</li>
 *     <li>If nothing plays after a few seconds it sends a "play" media key, which resumes Spotify.</li>
 *     <li>If still nothing plays and the alarm screen is shown, it brings Spotify to the front for
 *     a moment (some versions only start playing when visible) and then returns to the alarm.</li>
 *     <li>If nothing plays within {@link #START_TIMEOUT_MS}, or if Spotify stops playing while the
 *     alarm rings (lost connection, end of a track...), it gives up and the alarm falls back to
 *     its regular ringtone.</li>
 * </ol>
 *
 * <p>Spotify plays on the media stream, so the player also sets the media volume to match the
 * alarm volume (with crescendo) and restores it when the alarm stops.</p>
 *
 * <p>All methods must be called on the main thread.</p>
 */
public final class SpotifyAlarmPlayer {

    /** Called when Spotify could not be used; the caller should play a regular ringtone. */
    public interface FallbackListener {
        void onSpotifyUnavailable(@NonNull String reason);
    }

    private static final long CHECK_INTERVAL_MS = 1000;
    private static final long MEDIA_KEY_DELAY_MS = 4000;
    private static final long FOREGROUND_LAUNCH_DELAY_MS = 8000;
    /** Leaves Spotify time to react to a launch before trying another way. */
    private static final long MIN_DELAY_BETWEEN_LAUNCHES_MS = 5000;
    @VisibleForTesting
    static final long START_TIMEOUT_MS = 25000;
    /** How long playback may be interrupted while ringing before falling back. */
    private static final long INTERRUPTION_TIMEOUT_MS = 8000;

    // Saved state, to clean up if the process dies while the alarm rings.
    private static final String KEY_ACTIVE = "spotify_alarm_active";
    private static final String KEY_PREVIOUS_MUSIC_VOLUME = "spotify_alarm_previous_music_volume";

    private static SpotifyAlarmPlayer sCurrent;

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SpotifyUtils.Source mSource;
    private final FallbackListener mFallbackListener;
    private final int mCrescendoDurationMs;
    private final int mTargetMusicVolume;

    private WeakReference<Activity> mAlarmScreen = new WeakReference<>(null);
    private long mStartTime;
    private long mLastLaunchTime;
    private long mCrescendoStartTime;
    private long mSilentSince = -1;
    private boolean mMusicWasActiveBefore;
    private boolean mPlaybackConfirmed;
    private boolean mMediaKeySent;
    private boolean mLaunchedFromScreen;
    private boolean mForegroundLaunchDone;
    private boolean mResumeKeySent;
    private boolean mFinished;
    private int mPreviousMusicVolume = -1;

    private final Runnable mWatchdog = this::checkPlayback;
    private final Runnable mCrescendo = new Runnable() {
        @Override
        public void run() {
            if (mFinished) {
                return;
            }

            long elapsed = SystemClock.elapsedRealtime() - mCrescendoStartTime;
            float fraction = Math.min(1f, elapsed / (float) mCrescendoDurationMs);
            int volume = Math.max(1, Math.round(fraction * mTargetMusicVolume));
            setMusicVolume(volume);

            if (fraction < 1f) {
                mHandler.postDelayed(this, Math.max(250, mCrescendoDurationMs / Math.max(1, mTargetMusicVolume) / 2));
            }
        }
    };

    private SpotifyAlarmPlayer(@NonNull Context context, @NonNull SpotifyUtils.Source source,
                               int crescendoDurationMs, int alarmVolume,
                               @NonNull FallbackListener fallbackListener) {

        mContext = context.getApplicationContext();
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mSource = source;
        mCrescendoDurationMs = crescendoDurationMs;
        mFallbackListener = fallbackListener;
        mTargetMusicVolume = computeMusicVolume(mAudioManager, alarmVolume);
    }

    /**
     * Starts playing the given source with Spotify.
     *
     * @param alarmVolume the alarm stream volume this alarm should ring at, or -1 to use the
     *                    current alarm stream volume
     * @return {@code false} if Spotify cannot be used at all; the caller must play a regular
     * ringtone instead (the fallback listener is not called in that case)
     */
    public static boolean start(@NonNull Context context, @NonNull SpotifyUtils.Source source,
                                int crescendoDurationMs, int alarmVolume,
                                @NonNull FallbackListener fallbackListener) {

        stop();

        if (!SpotifyUtils.isSpotifyInstalled(context)) {
            LogUtils.w("SpotifyAlarmPlayer: Spotify is not installed");
            return false;
        }

        sCurrent = new SpotifyAlarmPlayer(context, source, crescendoDurationMs, alarmVolume, fallbackListener);
        sCurrent.begin();
        return true;
    }

    /**
     * Called by the alarm screen when it becomes visible. A visible screen is allowed to start
     * activities, which makes starting Spotify much more reliable.
     */
    public static void onAlarmScreenVisible(@NonNull Activity activity) {
        if (sCurrent != null) {
            sCurrent.attachScreen(activity);
        }
    }

    /**
     * Stops the Spotify alarm: pauses Spotify and restores the media volume.
     *
     * <p>This also works if the app process was restarted while the alarm was ringing: the state
     * needed to clean up is saved in the preferences.</p>
     */
    public static void stop() {
        if (sCurrent != null) {
            sCurrent.finish(true);
            sCurrent = null;
            return;
        }

        recoverAfterProcessDeath(DeskClockApplication.getAppContext());
    }

    private static void recoverAfterProcessDeath(@NonNull Context context) {
        SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            return;
        }

        LogUtils.i("SpotifyAlarmPlayer: cleaning up a Spotify alarm from a previous process");
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager.isMusicActive()) {
            sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE);
        }

        int previousVolume = prefs.getInt(KEY_PREVIOUS_MUSIC_VOLUME, -1);
        if (previousVolume >= 0) {
            setMusicVolume(audioManager, previousVolume);
        }

        prefs.edit().remove(KEY_ACTIVE).remove(KEY_PREVIOUS_MUSIC_VOLUME).apply();
    }

    private void begin() {
        mStartTime = SystemClock.elapsedRealtime();
        mMusicWasActiveBefore = mAudioManager.isMusicActive();

        mPreviousMusicVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_PREVIOUS_MUSIC_VOLUME, mPreviousMusicVolume)
            .apply();
        if (mCrescendoDurationMs > 0) {
            // The crescendo starts once Spotify plays, so the first seconds of music aren't lost
            // while Spotify is loading.
            setMusicVolume(1);
        } else {
            setMusicVolume(mTargetMusicVolume);
        }

        LogUtils.i("SpotifyAlarmPlayer: starting " + mSource.spotifyUri()
            + " (media volume " + mTargetMusicVolume + ", crescendo " + mCrescendoDurationMs + " ms)");

        // The app is usually allowed to start activities here because the alarm has just fired.
        // If not, the alarm screen will retry once it is visible.
        launch(mContext, true);

        mHandler.postDelayed(mWatchdog, CHECK_INTERVAL_MS);
    }

    private void attachScreen(@NonNull Activity activity) {
        mAlarmScreen = new WeakReference<>(activity);

        if (!mFinished && !mPlaybackConfirmed && !mLaunchedFromScreen) {
            mLaunchedFromScreen = true;

            // Don't restart the content if Spotify already started (the check may just be late).
            if (mAudioManager.isMusicActive() && !mMusicWasActiveBefore) {
                onPlaybackConfirmed();
                LogUtils.i("SpotifyAlarmPlayer: playback confirmed when the alarm screen appeared");
                return;
            }

            LogUtils.i("SpotifyAlarmPlayer: alarm screen visible, asking Spotify to play again");
            launch(activity, true);
        }
    }

    private void checkPlayback() {
        if (mFinished) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long elapsed = now - mStartTime;
        final boolean playing = mAudioManager.isMusicActive();

        if (!mPlaybackConfirmed) {
            // Music that was already playing before the alarm does not prove Spotify started,
            // so wait until it's confirmed by the media key or a new launch.
            if (playing && (!mMusicWasActiveBefore || elapsed > MEDIA_KEY_DELAY_MS)) {
                onPlaybackConfirmed();
                mSilentSince = -1;
                LogUtils.i("SpotifyAlarmPlayer: playback confirmed after " + elapsed + " ms");
            } else if (elapsed >= START_TIMEOUT_MS) {
                fail("Spotify did not start playing within " + START_TIMEOUT_MS + " ms");
                return;
            } else if (elapsed >= FOREGROUND_LAUNCH_DELAY_MS && !mForegroundLaunchDone
                && now - mLastLaunchTime >= MIN_DELAY_BETWEEN_LAUNCHES_MS) {
                mForegroundLaunchDone = true;
                launchInForegroundThenReturn();
            } else if (elapsed >= MEDIA_KEY_DELAY_MS && !mMediaKeySent) {
                mMediaKeySent = true;
                LogUtils.i("SpotifyAlarmPlayer: nothing playing yet, sending the play media key");
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        } else if (playing) {
            mSilentSince = -1;
            mResumeKeySent = false;
        } else {
            if (mSilentSince < 0) {
                mSilentSince = now;
            }

            long silentFor = now - mSilentSince;
            if (silentFor >= INTERRUPTION_TIMEOUT_MS) {
                fail("Spotify stopped playing during the alarm");
                return;
            } else if (silentFor >= INTERRUPTION_TIMEOUT_MS / 2 && !mResumeKeySent) {
                mResumeKeySent = true;
                LogUtils.i("SpotifyAlarmPlayer: playback interrupted, trying to resume");
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        }

        mHandler.postDelayed(mWatchdog, CHECK_INTERVAL_MS);
    }

    private void onPlaybackConfirmed() {
        mPlaybackConfirmed = true;
        if (mCrescendoDurationMs > 0) {
            mCrescendoStartTime = SystemClock.elapsedRealtime();
            mHandler.post(mCrescendo);
        }
    }

    /**
     * Some Spotify versions only start playback once their screen is shown. Start Spotify and
     * the alarm screen in a single call, while the alarm screen is still visible: Android allows
     * both, and the alarm screen ends up on top again.
     */
    private void launchInForegroundThenReturn() {
        final Activity screen = mAlarmScreen.get();
        if (screen == null || screen.isFinishing()) {
            return;
        }

        LogUtils.i("SpotifyAlarmPlayer: still silent, showing Spotify for a moment");
        mLastLaunchTime = SystemClock.elapsedRealtime();

        Intent spotify = SpotifyUtils.createPlayIntent(mSource)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Intent backToAlarm = new Intent(screen.getIntent())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        try {
            screen.startActivities(new Intent[]{spotify, backToAlarm});
        } catch (Exception e) {
            LogUtils.w("SpotifyAlarmPlayer: unable to show Spotify: " + e.getMessage());
        }
    }

    private void launch(@NonNull Context context, boolean behind) {
        mLastLaunchTime = SystemClock.elapsedRealtime();
        Intent intent = SpotifyUtils.createPlayIntent(mSource)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        try {
            if (behind) {
                // Keeps the alarm screen in front: Spotify's task is created behind it.
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                context.startActivity(intent, ActivityOptions.makeTaskLaunchBehind().toBundle());
            } else {
                context.startActivity(intent);
            }
        } catch (Exception e) {
            // Background activity starts may be refused: the watchdog takes care of it.
            LogUtils.w("SpotifyAlarmPlayer: unable to start Spotify: " + e.getMessage());
        }
    }

    private void sendMediaKey(int keyCode) {
        sendMediaKey(mAudioManager, keyCode);
    }

    private static void sendMediaKey(@NonNull AudioManager audioManager, int keyCode) {
        try {
            long time = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception e) {
            LogUtils.e("SpotifyAlarmPlayer: unable to send media key " + keyCode, e);
        }
    }

    private void fail(@NonNull String reason) {
        LogUtils.w("SpotifyAlarmPlayer: " + reason + "; falling back to the alarm ringtone");
        // Don't pause: if Spotify is just late, the alarm is louder rather than silent.
        finish(false);
        if (sCurrent == this) {
            sCurrent = null;
        }
        mFallbackListener.onSpotifyUnavailable(reason);
    }

    private void finish(boolean pauseSpotify) {
        if (mFinished) {
            return;
        }

        mFinished = true;
        mHandler.removeCallbacksAndMessages(null);

        if (pauseSpotify && (mPlaybackConfirmed || mAudioManager.isMusicActive())) {
            LogUtils.i("SpotifyAlarmPlayer: pausing Spotify");
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }

        if (mPreviousMusicVolume >= 0) {
            setMusicVolume(mPreviousMusicVolume);
            mPreviousMusicVolume = -1;
        }

        DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_PREVIOUS_MUSIC_VOLUME)
            .apply();
    }

    private void setMusicVolume(int volume) {
        setMusicVolume(mAudioManager, volume);
    }

    private static void setMusicVolume(@NonNull AudioManager audioManager, int volume) {
        try {
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != volume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            }
        } catch (SecurityException e) {
            LogUtils.w("SpotifyAlarmPlayer: unable to change the media volume: " + e.getMessage());
        }
    }

    /**
     * Converts an alarm stream volume to the media stream scale, which usually has more steps.
     */
    @VisibleForTesting
    static int computeMusicVolume(@Nullable AudioManager audioManager, int alarmVolume) {
        if (audioManager == null) {
            return 1;
        }

        int maxAlarm = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM));
        int maxMusic = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int volume = alarmVolume >= 0 ? alarmVolume : audioManager.getStreamVolume(AudioManager.STREAM_ALARM);

        return scaleVolume(volume, maxAlarm, maxMusic);
    }

    @VisibleForTesting
    static int scaleVolume(int volume, int fromMax, int toMax) {
        int clamped = Math.max(0, Math.min(volume, fromMax));
        return Math.max(1, Math.round(clamped * toMax / (float) fromMax));
    }
}
