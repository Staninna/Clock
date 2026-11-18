// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.best.deskclock.homeassistant.HomeAssistantSettings;
import com.best.deskclock.spotify.SpotifyAccount;
import com.best.deskclock.spotify.SpotifyAlarmPlayer;

import org.junit.Test;

public class DeviceOnlySettingsTest {

    @Test
    public void loginsConnectionsAndPlaybackStateStayOnTheDevice() {
        for (String[] keys : new String[][]{
            SpotifyAccount.PRIVATE_KEYS, SpotifyAlarmPlayer.PRIVATE_KEYS, HomeAssistantSettings.PRIVATE_KEYS}) {
            for (String key : keys) {
                assertTrue(key, DeviceOnlySettings.contains(key));
            }
        }
        assertTrue(DeviceOnlySettings.contains(SpotifyAccount.KEY_REFRESH_TOKEN));
        assertTrue(DeviceOnlySettings.contains(HomeAssistantSettings.KEY_HA_TOKEN));
    }

    @Test
    public void regularSettingsAreBackedUp() {
        assertFalse(DeviceOnlySettings.contains(PreferencesKeys.KEY_ALARM_MATH_HARDNESS_LEVEL));
        assertFalse(DeviceOnlySettings.contains(PreferencesKeys.KEY_ALARM_MATH_PROBLEM_COUNT));
        assertFalse(DeviceOnlySettings.contains(""));
    }
}
