/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.alarms;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;

import androidx.annotation.NonNull;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.ringtone.AsyncRingtonePlayer;
import com.best.deskclock.ringtone.RingtonePlayer;
import com.best.deskclock.spotify.SpotifyAlarmPlayer;
import com.best.deskclock.spotify.SpotifyUtils;
import com.best.deskclock.utils.DeviceUtils;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.Utils;

/**
 * Manages playing alarm ringtones and vibrating the device.
 */
public final class AlarmKlaxon {

    private static AlarmKlaxon sInstance;

    private boolean mStarted = false;
    private AsyncRingtonePlayer mAsyncRingtonePlayer;
    private RingtonePlayer mRingtonePlayer;
    private Runnable mVibrationRunnable;
    private int mPreviousAlarmVolume = -1;

    private AlarmKlaxon() {
    }

    public record Config(
        boolean isAdvancedAudio,
        boolean isPerAlarmVolume,
        int vibrationDelay,
        boolean isPerAlarmVibrationPattern,
        @NonNull String globalVibrationPattern,
        @NonNull RingtonePlayer.Config ringtonePlayerConfig
    ) {}

    private static synchronized AlarmKlaxon getInstance() {
        if (sInstance == null) {
            sInstance = new AlarmKlaxon();
        }

        return sInstance;
    }

    public static void stop() {
        AlarmKlaxon instance = getInstance();
        Context appContext = DeskClockApplication.getAppContext();

        if (instance.mVibrationRunnable != null) {
            AppExecutors.getMainThread().removeCallbacks(instance.mVibrationRunnable);
            instance.mVibrationRunnable = null;
        }

        // Called even if this process didn't start the alarm: a Spotify alarm started by a
        // previous process must still be stopped.
        SpotifyAlarmPlayer.stop();

        if (instance.mStarted) {
            instance.mStarted = false;

            if (instance.mRingtonePlayer != null) {
                LogUtils.v("AlarmKlaxon.stop() ExoPlayer");
                instance.mRingtonePlayer.stop();
            }

            if (instance.mAsyncRingtonePlayer != null) {
                LogUtils.v("AlarmKlaxon.stop() MediaPlayer");
                instance.mAsyncRingtonePlayer.stop();
            }

            if (instance.mPreviousAlarmVolume != -1) {
                AudioManager audioManager = appContext.getSystemService(AudioManager.class);
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                // Restore the original alarm volume only if it was changed
                if (currentVolume != instance.mPreviousAlarmVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, instance.mPreviousAlarmVolume, 0);
                }


                instance.mPreviousAlarmVolume = -1;
            }

            final Vibrator vibrator = appContext.getSystemService(Vibrator.class);
            vibrator.cancel();
        }
    }

    public static void start(@NonNull Config config, @NonNull AlarmInstance alarmInstance) {
        // Make sure we are stopped before starting
        stop();

        Context appContext = DeskClockApplication.getAppContext();
        AlarmKlaxon instance = getInstance();
        boolean isRingtoneSilent = RingtoneUtils.RINGTONE_SILENT.equals(alarmInstance.mRingtone);

        if (!isRingtoneSilent) {
            if (SpotifyUtils.isSpotifyAlarmUri(alarmInstance.mRingtone)) {
                instance.startSpotify(appContext, config, alarmInstance);
            } else {
                instance.playRingtone(appContext, config, alarmInstance, alarmInstance.mRingtone);
            }
        }

        if (alarmInstance.mVibrate) {
            long delayInMillis;

            if (isRingtoneSilent) {
                delayInMillis = 0;
                LogUtils.v("AlarmKlaxon: Ringtone is silent, bypassing vibration delay");
            } else {
                delayInMillis = config.vibrationDelay() * 1000L;
                // Add a small safety margin in case the vibration pattern starts with 0 ms,
                // to prevent any vibration if the alarm stops right at the delay limit.
                final long SAFETY_MARGIN_MS = 300;
                delayInMillis += SAFETY_MARGIN_MS;
            }

            instance.mVibrationRunnable = () -> {
                final Vibrator vibrator = appContext.getSystemService(Vibrator.class);

                String patternKey = config.isPerAlarmVibrationPattern()
                    ? alarmInstance.mVibrationPattern
                    : config.globalVibrationPattern();
                long[] pattern = Utils.getVibrationPatternForKey(patternKey);

                Utils.executeVibrations(vibrator, pattern, 0);
            };

            if (delayInMillis > 0) {
                LogUtils.v("AlarmKlaxon: vibration scheduled in " + delayInMillis + "ms");
                AppExecutors.getMainThread().postDelayed(instance.mVibrationRunnable, delayInMillis);
            } else {
                LogUtils.v("AlarmKlaxon: vibration started immediately");
                instance.mVibrationRunnable.run();
            }
        }

        instance.mStarted = true;
    }

    public static void setAutoRoutingEnabled(boolean enabled) {
        AlarmKlaxon instance = getInstance();
        if (instance.mRingtonePlayer != null) {
            instance.mRingtonePlayer.setAutoRoutingEnabled(enabled);
        }
    }

    public static synchronized void releaseResources() {
        if (sInstance != null) {
            if (sInstance.mAsyncRingtonePlayer != null) {
                sInstance.mAsyncRingtonePlayer.shutdown();
                sInstance.mAsyncRingtonePlayer = null;
            }

            if (sInstance.mRingtonePlayer != null) {
                sInstance.mRingtonePlayer.stop();
                sInstance.mRingtonePlayer = null;
            }
        }
    }

    /**
     * Plays a regular ringtone (anything but Spotify) with ExoPlayer or MediaPlayer.
     */
    private void playRingtone(@NonNull Context appContext, @NonNull Config config,
                              @NonNull AlarmInstance alarmInstance, @NonNull Uri ringtoneUri) {

        // Crescendo duration always in milliseconds
        final int crescendoDuration = alarmInstance.mCrescendoDuration * 1000;
        if (DeviceUtils.isUserUnlocked(appContext) && config.isAdvancedAudio) {
            LogUtils.v("AlarmKlaxon.start() with ExoPlayer");

            getRingtonePlayer(config.ringtonePlayerConfig()).play(ringtoneUri, crescendoDuration);
        } else {
            LogUtils.v("AlarmKlaxon.start() with MediaPlayer");

            if (config.isPerAlarmVolume) {
                AudioManager audioManager = appContext.getSystemService(AudioManager.class);

                if (mPreviousAlarmVolume == -1) {
                    mPreviousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                }

                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                if (currentVolume != alarmInstance.mAlarmVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, alarmInstance.mAlarmVolume, 0);
                }
            }

            getAsyncRingtonePlayer().play(ringtoneUri, crescendoDuration);
        }
    }

    /**
     * Rings the alarm with Spotify, and with a regular ringtone if Spotify can't be used.
     */
    private void startSpotify(@NonNull Context appContext, @NonNull Config config,
                              @NonNull AlarmInstance alarmInstance) {

        final SpotifyUtils.Source source = SpotifyUtils.fromAlarmUri(alarmInstance.mRingtone);
        final String unusableReason;

        if (source == null) {
            unusableReason = "invalid Spotify source";
        } else if (!DeviceUtils.isUserUnlocked(appContext)) {
            // Spotify can't run before the device is unlocked for the first time after a reboot.
            unusableReason = "device locked since boot (Direct Boot)";
        } else if (RingtoneUtils.isInTelephoneCall(appContext.getSystemService(AudioManager.class))) {
            unusableReason = "telephone call in progress";
        } else if (!(config.isAdvancedAudio
            && SettingsDAO.isAutoRoutingToExternalAudioDevice(DeskClockApplication.getDefaultSharedPreferences(appContext)))
            && hasExternalMediaOutput(appContext)) {
            // Spotify plays on the media stream, which goes to headphones or a Bluetooth speaker:
            // the phone itself would stay silent.
            unusableReason = "media audio goes to an external device";
        } else if (isMediaMutedByDoNotDisturb(appContext)) {
            unusableReason = "media muted by Do Not Disturb";
        } else {
            final int alarmVolume = config.isPerAlarmVolume ? alarmInstance.mAlarmVolume : -1;
            final boolean started = SpotifyAlarmPlayer.start(appContext, source,
                alarmInstance.mCrescendoDuration * 1000, alarmVolume, reason -> {
                    // Spotify failed while ringing: make sure the alarm is still heard.
                    if (mStarted) {
                        playRingtone(appContext, config, alarmInstance, getSpotifyFallbackUri(appContext));
                    }
                });

            unusableReason = started ? null : "Spotify is not installed";
        }

        if (unusableReason != null) {
            LogUtils.w("AlarmKlaxon: cannot use Spotify (" + unusableReason + "), playing the fallback ringtone");
            playRingtone(appContext, config, alarmInstance, getSpotifyFallbackUri(appContext));
        }
    }

    /**
     * @return {@code true} if headphones, a Bluetooth audio device or a hearing aid is connected,
     * so media audio would not be played by the phone's speaker.
     */
    private static boolean hasExternalMediaOutput(@NonNull Context appContext) {
        try {
            AudioManager audioManager = appContext.getSystemService(AudioManager.class);
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (device.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                         AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                         AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
                         AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_LINE_ANALOG,
                         AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_HDMI,
                         AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
        } catch (RuntimeException e) {
            LogUtils.w("AlarmKlaxon: unable to list the audio outputs: " + e.getMessage());
        }
        return false;
    }

    /**
     * @return {@code true} if Do Not Disturb silences media, which would make a Spotify alarm silent.
     */
    private static boolean isMediaMutedByDoNotDisturb(@NonNull Context appContext) {
        try {
            NotificationManager notificationManager = appContext.getSystemService(NotificationManager.class);
            int filter = notificationManager.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                return true;
            }
            if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && SdkUtils.isAtLeastAndroid9()) {
                NotificationManager.Policy policy = notificationManager.getNotificationPolicy();
                return policy != null
                    && (policy.priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) == 0;
            }
        } catch (RuntimeException e) {
            LogUtils.w("AlarmKlaxon: unable to read the Do Not Disturb state: " + e.getMessage());
        }
        return false;
    }

    /**
     * @return the ringtone played when a Spotify alarm can't use Spotify: the default alarm sound
     * chosen in the settings, or the system alarm sound. Never silent.
     */
    @NonNull
    private static Uri getSpotifyFallbackUri(@NonNull Context appContext) {
        Uri systemDefault = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        Uri uri = SettingsDAO.getAlarmRingtoneUriFromSettings(
            DeskClockApplication.getDefaultSharedPreferences(appContext), systemDefault);

        if (RingtoneUtils.isRandomRingtone(uri)) {
            return RingtoneUtils.getRandomRingtoneUri();
        } else if (RingtoneUtils.isRandomCustomRingtone(uri)) {
            return RingtoneUtils.getRandomCustomRingtoneUri();
        } else if (uri == null || RingtoneUtils.RINGTONE_SILENT.equals(uri) || SpotifyUtils.isSpotifyAlarmUri(uri)) {
            return systemDefault;
        }

        return uri;
    }

    // MediaPlayer
    private AsyncRingtonePlayer getAsyncRingtonePlayer() {
        if (mAsyncRingtonePlayer == null) {
            mAsyncRingtonePlayer = new AsyncRingtonePlayer(DeskClockApplication.getAppContext());
        }

        return mAsyncRingtonePlayer;
    }

    // ExoPlayer
    private RingtonePlayer getRingtonePlayer(@NonNull RingtonePlayer.Config config) {
        if (mRingtonePlayer == null) {
            mRingtonePlayer = new RingtonePlayer(DeskClockApplication.getAppContext(), config);
        }

        return mRingtonePlayer;
    }

}
