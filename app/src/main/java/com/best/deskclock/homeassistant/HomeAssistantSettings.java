// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Stores the Home Assistant settings.
 */
public final class HomeAssistantSettings {

    public static final String KEY_HA_ENABLED = "key_ha_enabled";
    public static final String KEY_HA_CONNECTION = "key_ha_connection";
    public static final String KEY_HA_DEVICE_NAME = "key_ha_device_name";
    public static final String KEY_HA_SHARE_NEXT_ALARM = "key_ha_share_next_alarm";
    public static final String KEY_HA_SHARE_ALARM_EVENTS = "key_ha_share_alarm_events";
    public static final String KEY_HA_SHARE_LABELS = "key_ha_share_labels";
    public static final String KEY_HA_TEST_EVENT = "key_ha_test_event";
    public static final String KEY_HA_EXAMPLES = "key_ha_examples";
    public static final String KEY_HA_DISCONNECT = "key_ha_disconnect";

    // Internal values, not shown as preferences.
    public static final String KEY_HA_URL = "key_ha_url";
    public static final String KEY_HA_TOKEN = "key_ha_token";
    public static final String KEY_HA_WEBHOOK_ID = "key_ha_webhook_id";
    public static final String KEY_HA_DEVICE_ID = "key_ha_device_id";
    public static final String KEY_HA_SENSORS_REGISTERED = "key_ha_sensors_registered";
    public static final String KEY_HA_SERVER_NAME = "key_ha_server_name";
    public static final String KEY_HA_LAST_SYNC = "key_ha_last_sync";
    public static final String KEY_HA_LAST_ERROR = "key_ha_last_error";
    public static final String KEY_HA_ENTITY_PREFIX = "key_ha_entity_prefix";

    /**
     * Keys that must never leave the device in a settings backup: the token gives access to the
     * user's Home Assistant, and the registration belongs to this installation.
     */
    public static final String[] PRIVATE_KEYS = {
        KEY_HA_URL, KEY_HA_TOKEN, KEY_HA_WEBHOOK_ID, KEY_HA_DEVICE_ID, KEY_HA_SENSORS_REGISTERED,
        KEY_HA_SERVER_NAME, KEY_HA_LAST_SYNC, KEY_HA_LAST_ERROR, KEY_HA_ENABLED, KEY_HA_ENTITY_PREFIX
    };

    public static boolean isPrivateKey(@NonNull String key) {
        for (String privateKey : PRIVATE_KEYS) {
            if (privateKey.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Version of the sensor definitions; bump it to register the sensors again after a change. */
    static final int SENSORS_VERSION = 1;

    private HomeAssistantSettings() {
    }

    public static boolean isEnabled(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(KEY_HA_ENABLED, false);
    }

    /**
     * @return {@code true} if the integration is enabled and connected.
     */
    public static boolean isActive(@NonNull SharedPreferences prefs) {
        return isEnabled(prefs) && isConnected(prefs);
    }

    public static boolean isConnected(@NonNull SharedPreferences prefs) {
        return !TextUtils.isEmpty(getUrl(prefs)) && !TextUtils.isEmpty(getWebhookId(prefs));
    }

    @NonNull
    public static String getUrl(@NonNull SharedPreferences prefs) {
        return prefs.getString(KEY_HA_URL, "");
    }

    @Nullable
    public static String getToken(@NonNull SharedPreferences prefs) {
        return prefs.getString(KEY_HA_TOKEN, null);
    }

    @Nullable
    public static String getWebhookId(@NonNull SharedPreferences prefs) {
        return prefs.getString(KEY_HA_WEBHOOK_ID, null);
    }

    @NonNull
    public static String getServerName(@NonNull SharedPreferences prefs) {
        return prefs.getString(KEY_HA_SERVER_NAME, "Home Assistant");
    }

    /**
     * Saves a successful connection.
     */
    static void saveConnection(@NonNull SharedPreferences prefs, @NonNull String url, @NonNull String token,
                               @NonNull String webhookId, @NonNull String serverName) {
        prefs.edit()
            .putString(KEY_HA_URL, url)
            .putString(KEY_HA_TOKEN, token)
            .putString(KEY_HA_WEBHOOK_ID, webhookId)
            .putString(KEY_HA_SERVER_NAME, serverName)
            // Home Assistant creates the entity ids from the device name at registration, and
            // keeps them when the device is renamed later.
            .putString(KEY_HA_ENTITY_PREFIX, slugify(getDeviceName(prefs)))
            .putInt(KEY_HA_SENSORS_REGISTERED, 0)
            .putBoolean(KEY_HA_ENABLED, true)
            .remove(KEY_HA_LAST_ERROR)
            .apply();
    }

    static void clearWebhook(@NonNull SharedPreferences prefs) {
        prefs.edit().remove(KEY_HA_WEBHOOK_ID).putInt(KEY_HA_SENSORS_REGISTERED, 0).apply();
    }

    public static void disconnect(@NonNull SharedPreferences prefs) {
        prefs.edit()
            .remove(KEY_HA_URL)
            .remove(KEY_HA_TOKEN)
            .remove(KEY_HA_WEBHOOK_ID)
            .remove(KEY_HA_SERVER_NAME)
            .remove(KEY_HA_ENTITY_PREFIX)
            .remove(KEY_HA_SENSORS_REGISTERED)
            .remove(KEY_HA_LAST_SYNC)
            .remove(KEY_HA_LAST_ERROR)
            .putBoolean(KEY_HA_ENABLED, false)
            .apply();
    }

    static boolean areSensorsRegistered(@NonNull SharedPreferences prefs) {
        return prefs.getInt(KEY_HA_SENSORS_REGISTERED, 0) == SENSORS_VERSION;
    }

    static void setSensorsRegistered(@NonNull SharedPreferences prefs) {
        prefs.edit().putInt(KEY_HA_SENSORS_REGISTERED, SENSORS_VERSION).apply();
    }

    /**
     * @return a random identifier for this installation, created on first use.
     */
    @NonNull
    static synchronized String getDeviceId(@NonNull SharedPreferences prefs) {
        String id = prefs.getString(KEY_HA_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_HA_DEVICE_ID, id).apply();
        }
        return id;
    }

    /**
     * @return the name of this device in Home Assistant, e.g. "Pixel 8 Clock".
     */
    @NonNull
    public static String getDeviceName(@NonNull SharedPreferences prefs) {
        String name = prefs.getString(KEY_HA_DEVICE_NAME, null);
        return TextUtils.isEmpty(name) ? getDefaultDeviceName() : name.trim();
    }

    @NonNull
    public static String getDefaultDeviceName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL.trim();
        return model + " Clock";
    }

    /**
     * @return the entity id prefix Home Assistant derives from the device name, e.g.
     * {@code pixel_8_clock}.
     */
    @NonNull
    public static String slugify(@NonNull String name) {
        String slug = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "clock" : slug;
    }

    /**
     * @return the prefix of this device's entity ids, e.g. {@code pixel_8_clock} for
     * {@code sensor.pixel_8_clock_next_alarm}.
     */
    @NonNull
    public static String getEntityPrefix(@NonNull SharedPreferences prefs) {
        String prefix = prefs.getString(KEY_HA_ENTITY_PREFIX, null);
        return TextUtils.isEmpty(prefix) ? slugify(getDeviceName(prefs)) : prefix;
    }

    public static boolean shareNextAlarm(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(KEY_HA_SHARE_NEXT_ALARM, true);
    }

    public static boolean shareAlarmEvents(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(KEY_HA_SHARE_ALARM_EVENTS, true);
    }

    public static boolean shareLabels(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(KEY_HA_SHARE_LABELS, true);
    }

    static void setLastSync(@NonNull SharedPreferences prefs, long time) {
        prefs.edit().putLong(KEY_HA_LAST_SYNC, time).remove(KEY_HA_LAST_ERROR).apply();
    }

    public static long getLastSync(@NonNull SharedPreferences prefs) {
        return prefs.getLong(KEY_HA_LAST_SYNC, 0);
    }

    static void setLastError(@NonNull SharedPreferences prefs, @Nullable String error) {
        prefs.edit().putString(KEY_HA_LAST_ERROR, error).apply();
    }

    @Nullable
    public static String getLastError(@NonNull SharedPreferences prefs) {
        return prefs.getString(KEY_HA_LAST_ERROR, null);
    }
}
