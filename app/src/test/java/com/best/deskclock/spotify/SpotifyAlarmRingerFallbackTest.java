// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import com.best.deskclock.utils.RingtoneUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The ringtone that replaces Spotify must always be audible.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class SpotifyAlarmRingerFallbackTest {

    private static final Uri SYSTEM_DEFAULT = Uri.parse("content://settings/system/alarm_alert");

    @Test
    public void playsTheDefaultAlarmSoundChosenInTheSettings() {
        Uri chosen = Uri.parse("content://media/internal/audio/media/42");
        assertEquals(chosen, SpotifyAlarmRinger.getFallbackRingtone(chosen, SYSTEM_DEFAULT));
    }

    @Test
    public void neverFallsBackToSilence() {
        assertEquals(SYSTEM_DEFAULT, SpotifyAlarmRinger.getFallbackRingtone(RingtoneUtils.RINGTONE_SILENT, SYSTEM_DEFAULT));
        assertEquals(SYSTEM_DEFAULT, SpotifyAlarmRinger.getFallbackRingtone(null, SYSTEM_DEFAULT));
    }

    @Test
    public void neverFallsBackToSpotify() {
        SpotifyUtils.Source source = SpotifyUtils.parse("spotify:playlist:37i9dQZF1DX0UrRvztWcAU");
        assertEquals(SYSTEM_DEFAULT, SpotifyAlarmRinger.getFallbackRingtone(source.toAlarmUri(), SYSTEM_DEFAULT));
    }
}
