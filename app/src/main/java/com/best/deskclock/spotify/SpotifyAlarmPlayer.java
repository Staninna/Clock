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
 *     <li>With a Spotify Premium account, it asks Spotify through the Web API to play the source
 *     on this phone.</li>
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
    /** First Web API attempt: Spotify has had time to start and appear as a Connect device. */
    private static final long WEB_API_DELAY_MS = 2500;
    private static final int WEB_API_MAX_ATTEMPTS = 3;
    private static final long FOREGROUND_LAUNCH_DELAY_MS = 8000;
    /** Leaves Spotify time to react to a launch before trying another way. */
    private static final long MIN_DELAY_BETWEEN_LAUNCHES_MS = 5000;
    @VisibleForTesting
    static final long START_TIMEOUT_MS = 18000;
    /** How long playback may be interrupted while ringing before falling back. */
    private static final long INTERRUPTION_TIMEOUT_MS = 8000;

    // Saved state, to clean up if the process dies while the alarm rings.
    private static final String KEY_ACTIVE = "spotify_alarm_active";
    private static final String KEY_PREVIOUS_MUSIC_VOLUME = "spotify_alarm_previous_music_volume";

    /** Device-only state: never part of a settings backup. */
    static final String[] PRIVATE_KEYS = {KEY_ACTIVE, KEY_PREVIOUS_MUSIC_VOLUME};

    /**
     * After the alarm stopped (or fell back to the ringtone) without confirmed Spotify playback,
     * Spotify may still start late: watch for this long and pause it.
     */
    private static final long LATE_START_GUARD_MS = 15000;

    private static Handler sMainHandler;
    private static Runnable sLateStartGuard;

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
    private volatile boolean mPlaybackConfirmed;
    private boolean mMediaKeySent;
    private boolean mLaunchedFromScreen;
    private boolean mForegroundLaunchDone;
    private boolean mResumeKeySent;
    private volatile boolean mFinished;
    private int mWebApiAttempts;
    private long mNextWebApiAttempt = WEB_API_DELAY_MS;
    private boolean mWebApiPending;
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
        cancelLateStartGuard();

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
            // Music that was already playing before the alarm (e.g. a sleep sounds app) does
            // not prove that Spotify started: then only the timeout ends the wait, and the
            // regular ringtone makes sure the alarm is heard.
            if (playing && !mMusicWasActiveBefore) {
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
            } else if (elapsed >= MEDIA_KEY_DELAY_MS && !mMediaKeySent && !mMusicWasActiveBefore) {
                // Not with other music playing: the key could resume that app instead.
                mMediaKeySent = true;
                LogUtils.i("SpotifyAlarmPlayer: nothing playing yet, sending the play media key");
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            }

            if (!mPlaybackConfirmed && elapsed >= mNextWebApiAttempt) {
                tryWebApi();
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

    /**
     * With a Spotify Premium account, asks Spotify through the Web API to play the source on this
     * phone. More reliable than the app link alone, which some Spotify versions only open.
     */
    private void tryWebApi() {
        if (mWebApiPending || mWebApiAttempts >= WEB_API_MAX_ATTEMPTS
            || !SpotifyAccount.isLoggedIn(mContext) || !SpotifyAccount.isPremium(mContext)) {
            mNextWebApiAttempt = Long.MAX_VALUE;
            return;
        }

        mWebApiAttempts++;
        mWebApiPending = true;

        final Context context = mContext;
        final SpotifyUtils.Source source = mSource;
        final boolean musicWasActiveBefore = mMusicWasActiveBefore;
        SpotifyAccount.EXECUTOR.execute(() -> {
            String result;
            boolean retry = false;
            try {
                String token = SpotifyAccount.getAccessToken(context);
                SpotifyWebApi.Device device = token == null ? null : findThisPhone(SpotifyWebApi.getDevices(token),
                    getThisDeviceNames(context));
                if (mFinished || mPlaybackConfirmed
                    || (!musicWasActiveBefore && mAudioManager.isMusicActive())) {
                    // The alarm stopped meanwhile, or Spotify started: a play request would
                    // start or restart the music.
                    result = "not needed anymore";
                } else if (device == null) {
                    result = "this phone is not a Spotify Connect device yet";
                    retry = true;
                } else {
                    SpotifyWebApi.play(token, device.id(), source);
                    result = "playback requested on " + device.name();
                }
            } catch (SpotifyWebApi.SpotifyException e) {
                result = "Web API error " + e.httpCode + (e.reason == null ? "" : " " + e.reason);
                // e.g. Spotify not ready yet (404 NO_ACTIVE_DEVICE) or no network yet
                retry = e.isNetworkError() || e.httpCode == 404 || e.httpCode >= 500;
            }

            final String message = result;
            final boolean shouldRetry = retry;
            mHandler.post(() -> {
                if (mFinished) {
                    return;
                }
                mWebApiPending = false;
                // Never request playback twice: it would restart the music.
                mNextWebApiAttempt = shouldRetry
                    ? SystemClock.elapsedRealtime() - mStartTime + 3000
                    : Long.MAX_VALUE;
                LogUtils.i("SpotifyAlarmPlayer: Web API: " + message);
            });
        });
    }

    /**
     * @return the Spotify Connect device of this phone, recognized by its name. Never another
     * device, not even another phone of the same account: the alarm must ring here.
     */
    @Nullable
    @VisibleForTesting
    static SpotifyWebApi.Device findThisPhone(@NonNull java.util.List<SpotifyWebApi.Device> devices,
                                              @NonNull java.util.List<String> thisDeviceNames) {
        for (SpotifyWebApi.Device device : devices) {
            if (!"smartphone".equalsIgnoreCase(device.type()) || device.name() == null) {
                continue;
            }
            for (String name : thisDeviceNames) {
                if (name != null && !name.trim().isEmpty() && device.name().trim().equalsIgnoreCase(name.trim())) {
                    return device;
                }
            }
        }
        return null;
    }

    /**
     * @return the names Spotify may give this phone as a Connect device: the device name chosen
     * by the user, and the model.
     */
    @NonNull
    private static java.util.List<String> getThisDeviceNames(@NonNull Context context) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            names.add(android.provider.Settings.Global.getString(context.getContentResolver(),
                android.provider.Settings.Global.DEVICE_NAME));
        } catch (Exception ignored) {
            // Not available on this device.
        }
        names.add(android.os.Build.MODEL);
        return names;
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
        // The regular ringtone is now the alarm: pause Spotify, including if it starts late, so
        // it doesn't keep playing after the alarm is dismissed.
        finish(true);
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

        if (pauseSpotify && !mPlaybackConfirmed) {
            // Spotify was asked to play but hasn't yet: it may still start in a few seconds.
            startLateStartGuard(mAudioManager);
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

    private static void startLateStartGuard(@NonNull AudioManager audioManager) {
        cancelLateStartGuard();

        final long until = SystemClock.elapsedRealtime() + LATE_START_GUARD_MS;
        final boolean wasActive = audioManager.isMusicActive();
        sLateStartGuard = new Runnable() {
            @Override
            public void run() {
                if (sLateStartGuard != this) {
                    return;
                }
                boolean active = audioManager.isMusicActive();
                if (active && !wasActive) {
                    LogUtils.i("SpotifyAlarmPlayer: Spotify started after the alarm stopped, pausing it");
                    sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    sLateStartGuard = null;
                } else if (SystemClock.elapsedRealtime() < until) {
                    mainHandler().postDelayed(this, CHECK_INTERVAL_MS);
                } else {
                    sLateStartGuard = null;
                }
            }
        };
        mainHandler().postDelayed(sLateStartGuard, CHECK_INTERVAL_MS);
    }

    @NonNull
    private static Handler mainHandler() {
        if (sMainHandler == null) {
            sMainHandler = new Handler(Looper.getMainLooper());
        }
        return sMainHandler;
    }

    private static void cancelLateStartGuard() {
        if (sLateStartGuard != null) {
            mainHandler().removeCallbacks(sLateStartGuard);
            sLateStartGuard = null;
        }
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
