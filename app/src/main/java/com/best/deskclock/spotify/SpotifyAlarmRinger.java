// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.utils.DeviceUtils;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.SdkUtils;

/**
 * Rings an alarm whose ringtone is Spotify content.
 *
 * <p>An alarm must never stay silent: whenever Spotify can't be used, or fails while ringing,
 * the alarm plays a regular ringtone instead: {@link #getFallbackRingtone}.</p>
 */
public final class SpotifyAlarmRinger {

    /** Plays a regular ringtone instead of Spotify. */
    public interface Fallback {
        void play(@NonNull Uri ringtoneUri);
    }

    private SpotifyAlarmRinger() {
    }

    /**
     * Starts the alarm with Spotify, if Spotify can ring it right now.
     *
     * @param isAdvancedAudio whether the app's own player is used (it can route audio to the phone)
     * @param alarmVolume     the alarm stream volume to ring at, or -1 for the current volume
     * @param lateFallback    called on the main thread if Spotify fails while the alarm rings
     * @return {@code false} if Spotify can't ring the alarm: the caller must play
     * {@link #getFallbackRingtone} ({@code lateFallback} is not called then)
     */
    public static boolean start(@NonNull Context appContext, @NonNull AlarmInstance alarmInstance,
                                boolean isAdvancedAudio, int alarmVolume, @NonNull Fallback lateFallback) {

        final SpotifyUtils.Source source = SpotifyUtils.fromAlarmUri(alarmInstance.mRingtone);
        String unusableReason = getUnusableReason(source != null,
            DeviceUtils.isUserUnlocked(appContext),
            RingtoneUtils.isInTelephoneCall(appContext.getSystemService(AudioManager.class)),
            isMediaRoutedAwayFromPhone(appContext, isAdvancedAudio),
            isMediaMutedByDoNotDisturb(appContext));

        if (unusableReason == null && !SpotifyAlarmPlayer.start(appContext, source,
            alarmInstance.mCrescendoDuration * 1000, alarmVolume,
            reason -> lateFallback.play(getFallbackRingtone(appContext)))) {
            unusableReason = "Spotify is not installed";
        }

        if (unusableReason != null) {
            LogUtils.w("SpotifyAlarmRinger: cannot use Spotify (" + unusableReason + "), playing the fallback ringtone");
            return false;
        }
        return true;
    }

    /**
     * @return why Spotify can't ring the alarm right now, or {@code null} if it can.
     */
    @Nullable
    @VisibleForTesting
    static String getUnusableReason(boolean hasValidSource, boolean isUserUnlocked, boolean isInCall,
                                    boolean isMediaRoutedAwayFromPhone, boolean isMediaMutedByDoNotDisturb) {

        if (!hasValidSource) {
            return "invalid Spotify source";
        } else if (!isUserUnlocked) {
            // Spotify can't run before the device is unlocked for the first time after a reboot.
            return "device locked since boot (Direct Boot)";
        } else if (isInCall) {
            return "telephone call in progress";
        } else if (isMediaRoutedAwayFromPhone) {
            // Spotify plays on the media stream, which goes to headphones or a Bluetooth speaker:
            // the phone itself would stay silent.
            return "media audio goes to an external device";
        } else if (isMediaMutedByDoNotDisturb) {
            return "media muted by Do Not Disturb";
        }
        return null;
    }

    /**
     * @return the ringtone played when a Spotify alarm can't use Spotify: the default alarm sound
     * chosen in the settings (a random pick for the random choices), or the system alarm sound.
     * Never silent, never Spotify.
     */
    @NonNull
    public static Uri getFallbackRingtone(@NonNull Context appContext) {
        final Uri systemDefault = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        final Uri fromSettings = SettingsDAO.getAlarmRingtoneUriFromSettings(
            DeskClockApplication.getDefaultSharedPreferences(appContext), systemDefault);

        if (RingtoneUtils.isRandomRingtone(fromSettings)) {
            return RingtoneUtils.getRandomRingtoneUri();
        } else if (RingtoneUtils.isRandomCustomRingtone(fromSettings)) {
            return RingtoneUtils.getRandomCustomRingtoneUri();
        }
        return getFallbackRingtone(fromSettings, systemDefault);
    }

    /**
     * @return {@code fromSettings} if a ringtone can play it, otherwise {@code systemDefault}.
     */
    @NonNull
    @VisibleForTesting
    static Uri getFallbackRingtone(@Nullable Uri fromSettings, @NonNull Uri systemDefault) {
        if (fromSettings == null || RingtoneUtils.RINGTONE_SILENT.equals(fromSettings)
            || SpotifyUtils.isSpotifyAlarmUri(fromSettings)) {
            return systemDefault;
        }
        return fromSettings;
    }

    /**
     * @return {@code true} if media audio would not be played by the phone itself, unless the app's
     * own player routes it back to the phone.
     */
    private static boolean isMediaRoutedAwayFromPhone(@NonNull Context appContext, boolean isAdvancedAudio) {
        final boolean routedBackToPhone = isAdvancedAudio && SettingsDAO.isAutoRoutingToExternalAudioDevice(
            DeskClockApplication.getDefaultSharedPreferences(appContext));
        return !routedBackToPhone && hasExternalMediaOutput(appContext);
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
            LogUtils.w("SpotifyAlarmRinger: unable to list the audio outputs: " + e.getMessage());
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
            LogUtils.w("SpotifyAlarmRinger: unable to read the Do Not Disturb state: " + e.getMessage());
        }
        return false;
    }
}
