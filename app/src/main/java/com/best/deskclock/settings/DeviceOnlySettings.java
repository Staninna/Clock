// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import androidx.annotation.NonNull;

import com.best.deskclock.homeassistant.HomeAssistantSettings;
import com.best.deskclock.spotify.SpotifyAccount;
import com.best.deskclock.spotify.SpotifyAlarmPlayer;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Settings that belong to this device only: logins, tokens, connections and playback state. A
 * settings backup never contains them, and a restore never overwrites or removes them.
 */
public final class DeviceOnlySettings {

    private static final Set<String> KEYS = Set.copyOf(Stream.of(
        SpotifyAccount.PRIVATE_KEYS, SpotifyAlarmPlayer.PRIVATE_KEYS, HomeAssistantSettings.PRIVATE_KEYS
    ).flatMap(Stream::of).toList());

    private DeviceOnlySettings() {
    }

    public static boolean contains(@NonNull String key) {
        return KEYS.contains(key);
    }

    /**
     * Removes the device-only settings from every section of a settings backup.
     */
    public static void removeFrom(@NonNull JSONObject backup) {
        for (Iterator<String> sections = backup.keys(); sections.hasNext(); ) {
            final JSONObject section = backup.optJSONObject(sections.next());
            if (section != null) {
                for (String key : KEYS) {
                    section.remove(key);
                }
            }
        }
    }
}
