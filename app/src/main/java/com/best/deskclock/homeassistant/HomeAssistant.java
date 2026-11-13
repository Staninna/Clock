// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.best.deskclock.BuildConfig;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.utils.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shares the alarms of this device with Home Assistant.
 *
 * <p>The device appears in Home Assistant like a phone running the companion app (through the
 * built-in {@code mobile_app} integration), with:</p>
 * <ul>
 *     <li>{@code sensor.<device>_next_alarm}: a timestamp sensor with the next alarm. It can
 *     directly be used in a time trigger, with an offset to start a sunrise light 20 minutes
 *     before waking up.</li>
 *     <li>{@code sensor.<device>_alarm_state}: {@code idle}, {@code ringing} or {@code snoozed}.</li>
 *     <li>A {@code clock_alarm} event for each change ({@code ringing}, {@code snoozed},
 *     {@code dismissed}, {@code missed}), to trigger automations such as starting the coffee
 *     machine when the alarm is dismissed.</li>
 * </ul>
 *
 * <p>Everything runs on a background thread with a short wake lock, and never delays an alarm.</p>
 */
public final class HomeAssistant {

    public static final String EVENT_TYPE = "clock_alarm";

    public static final String TYPE_RINGING = "ringing";
    public static final String TYPE_SNOOZED = "snoozed";
    public static final String TYPE_DISMISSED = "dismissed";
    public static final String TYPE_MISSED = "missed";
    public static final String TYPE_TEST = "test";

    static final String STATE_IDLE = "idle";
    static final String STATE_RINGING = "ringing";
    static final String STATE_SNOOZED = "snoozed";

    private static final String SENSOR_NEXT_ALARM = "next_alarm";
    private static final String SENSOR_ALARM_STATE = "alarm_state";

    private static final long[] RETRY_DELAYS_MS = {3000, 15000};
    private static final long WAKE_LOCK_TIMEOUT_MS = 60000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HomeAssistant");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Last next alarm sent, to avoid sending the same value again and again. */
    private static String sLastNextAlarmPayload;
    private static long sLastNextAlarmSentAt;
    /** Re-send an unchanged next alarm after this delay, e.g. after Home Assistant restarted. */
    private static final long NEXT_ALARM_REFRESH_MS = 3 * 60 * 60 * 1000;

    /** Callback for the operations started from the settings. */
    public interface ResultCallback {
        void onResult(@Nullable HomeAssistantClient.HomeAssistantException error);
    }

    private HomeAssistant() {
    }

    // ---------------------------------------------------------------------------------------------
    // Alarm hooks

    public static void onAlarmRinging(@NonNull Context context, @NonNull AlarmInstance instance) {
        onAlarmChanged(context, instance, TYPE_RINGING, STATE_RINGING, null);
    }

    public static void onAlarmSnoozed(@NonNull Context context, @NonNull AlarmInstance instance,
                                      @NonNull Calendar snoozeUntil) {
        onAlarmChanged(context, instance, TYPE_SNOOZED, STATE_SNOOZED, snoozeUntil);
    }

    public static void onAlarmDismissed(@NonNull Context context, @NonNull AlarmInstance instance) {
        onAlarmChanged(context, instance, TYPE_DISMISSED, STATE_IDLE, null);
    }

    public static void onAlarmMissed(@NonNull Context context, @NonNull AlarmInstance instance) {
        onAlarmChanged(context, instance, TYPE_MISSED, STATE_IDLE, null);
    }

    /**
     * Called whenever the next alarm may have changed.
     */
    public static void onNextAlarmChanged(@NonNull Context context, @Nullable AlarmInstance nextAlarm) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);
        if (!HomeAssistantSettings.isActive(prefs) || !HomeAssistantSettings.shareNextAlarm(prefs)) {
            return;
        }

        final JSONObject update = buildNextAlarmUpdate(appContext, prefs, nextAlarm);
        final String payload = update.toString();

        synchronized (HomeAssistant.class) {
            long now = System.currentTimeMillis();
            if (payload.equals(sLastNextAlarmPayload) && now - sLastNextAlarmSentAt < NEXT_ALARM_REFRESH_MS) {
                return;
            }
            sLastNextAlarmPayload = payload;
            sLastNextAlarmSentAt = now;
        }

        run(appContext, "next alarm", client -> client.updateSensors(array(update)));
    }

    private static void onAlarmChanged(@NonNull Context context, @NonNull AlarmInstance instance,
                                       @NonNull String eventType, @NonNull String state, @Nullable Calendar snoozeUntil) {

        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);
        if (!HomeAssistantSettings.isActive(prefs) || !HomeAssistantSettings.shareAlarmEvents(prefs)) {
            return;
        }

        final JSONObject eventData = buildEventData(appContext, prefs, instance, eventType, snoozeUntil);
        final JSONObject stateUpdate = buildAlarmStateUpdate(prefs, instance, state, snoozeUntil);

        run(appContext, eventType, client -> {
            client.fireEvent(eventData);
            client.updateSensors(array(stateUpdate));
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Settings operations

    /**
     * Checks the server and the token, registers this device and its sensors, and saves the
     * connection. The callback runs on the main thread.
     */
    public static void connect(@NonNull Context context, @NonNull String url, @NonNull String token,
                               @NonNull ConnectCallback callback) {

        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);
        final Handler main = new Handler(Looper.getMainLooper());

        EXECUTOR.execute(() -> {
            HomeAssistantClient client = new HomeAssistantClient(url, token.trim());
            try {
                HomeAssistantClient.ServerInfo info = client.fetchServerInfo();
                if (!info.hasMobileApp()) {
                    throw new HomeAssistantClient.HomeAssistantException(
                        HomeAssistantClient.Failure.MOBILE_APP_MISSING, 200, null, null);
                }

                String webhookId = client.registerDevice(buildRegistration(appContext, prefs));
                HomeAssistantSettings.saveConnection(prefs, client.getBaseUrl(), token.trim(), webhookId, info.locationName());

                synchronized (HomeAssistant.class) {
                    sLastNextAlarmPayload = null;
                }

                // Create the sensors right away so they show up in Home Assistant immediately.
                Session session = new Session(appContext, prefs);
                session.ensureSensors();
                HomeAssistantSettings.setLastSync(prefs, System.currentTimeMillis());

                main.post(() -> callback.onConnected(info));
            } catch (HomeAssistantClient.HomeAssistantException e) {
                LogUtils.w("HomeAssistant: connection failed: " + e.failure + " " + e.getMessage());
                main.post(() -> callback.onFailed(e));
            }
        });
    }

    /** Callback of {@link #connect}. */
    public interface ConnectCallback {
        void onConnected(@NonNull HomeAssistantClient.ServerInfo info);

        void onFailed(@NonNull HomeAssistantClient.HomeAssistantException error);
    }

    /**
     * Fires a {@code clock_alarm} event of type {@code test}. The callback runs on the main thread.
     */
    public static void sendTestEvent(@NonNull Context context, @NonNull ResultCallback callback) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);
        final JSONObject data = new JSONObject();
        put(data, "type", TYPE_TEST);
        put(data, "device_name", HomeAssistantSettings.getDeviceName(prefs));
        put(data, "time", formatTimestamp(Calendar.getInstance()));

        runForResult(appContext, client -> client.fireEvent(data), callback);
    }

    /**
     * Re-sends everything: the sensors definitions, the next alarm and the alarm state.
     * Used after a settings change (e.g. the device name). The callback runs on the main thread.
     */
    public static void syncNow(@NonNull Context context, @Nullable ResultCallback callback) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);

        synchronized (HomeAssistant.class) {
            sLastNextAlarmPayload = null;
        }

        runForResult(appContext, client -> {
            client.updateRegistration();
            client.forceRegisterSensors();
        }, callback == null ? error -> { } : callback);

        // Also refresh the next alarm with the usual path.
        EXECUTOR.execute(() -> {
            AlarmInstance next = AlarmInstance.getNextFiringAlarm(appContext);
            onNextAlarmChanged(appContext, next);
        });
    }

    /**
     * Empties the next alarm sensor, when the user stops sharing it.
     */
    public static void clearNextAlarm(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);
        final JSONObject update = buildNextAlarmUpdate(appContext, prefs, null);

        synchronized (HomeAssistant.class) {
            sLastNextAlarmPayload = null;
        }

        run(appContext, "next alarm (cleared)", client -> client.updateSensors(array(update)));
    }

    // ---------------------------------------------------------------------------------------------
    // Execution

    private interface Operation {
        void run(@NonNull Session session) throws HomeAssistantClient.HomeAssistantException;
    }

    private static void run(@NonNull Context appContext, @NonNull String what, @NonNull Operation operation) {
        final PowerManager.WakeLock wakeLock = acquireWakeLock(appContext);

        EXECUTOR.execute(() -> {
            try {
                SharedPreferences prefs = getDefaultSharedPreferences(appContext);
                for (int attempt = 0; ; attempt++) {
                    if (!HomeAssistantSettings.isActive(prefs)) {
                        return;
                    }

                    try {
                        operation.run(new Session(appContext, prefs));
                        HomeAssistantSettings.setLastSync(prefs, System.currentTimeMillis());
                        LogUtils.i("HomeAssistant: sent " + what);
                        return;
                    } catch (HomeAssistantClient.HomeAssistantException e) {
                        boolean retryable = isRetryable(e.failure);
                        LogUtils.w("HomeAssistant: unable to send " + what + " (" + e.failure + ")"
                            + (retryable && attempt < RETRY_DELAYS_MS.length ? ", retrying" : ""));

                        if (!retryable || attempt >= RETRY_DELAYS_MS.length) {
                            HomeAssistantSettings.setLastError(prefs, e.failure.name());
                            // Not sent: send the next alarm again at the next opportunity.
                            synchronized (HomeAssistant.class) {
                                sLastNextAlarmPayload = null;
                            }
                            return;
                        }

                        Thread.sleep(RETRY_DELAYS_MS[attempt]);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        });
    }

    private static void runForResult(@NonNull Context appContext, @NonNull Operation operation,
                                     @NonNull ResultCallback callback) {

        final Handler main = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            SharedPreferences prefs = getDefaultSharedPreferences(appContext);
            HomeAssistantClient.HomeAssistantException error = null;
            try {
                if (!HomeAssistantSettings.isConnected(prefs)) {
                    throw new HomeAssistantClient.HomeAssistantException(HomeAssistantClient.Failure.INVALID_URL, 0, null, null);
                }
                operation.run(new Session(appContext, prefs));
                HomeAssistantSettings.setLastSync(prefs, System.currentTimeMillis());
            } catch (HomeAssistantClient.HomeAssistantException e) {
                HomeAssistantSettings.setLastError(prefs, e.failure.name());
                error = e;
            }

            final HomeAssistantClient.HomeAssistantException result = error;
            main.post(() -> callback.onResult(result));
        });
    }

    private static boolean isRetryable(@NonNull HomeAssistantClient.Failure failure) {
        return switch (failure) {
            case HOST_NOT_FOUND, CONNECTION_REFUSED, TIMEOUT, SERVER_ERROR, NETWORK -> true;
            default -> false;
        };
    }

    @Nullable
    private static PowerManager.WakeLock acquireWakeLock(@NonNull Context context) {
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskClock:HomeAssistant");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            return wakeLock;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A connected client, which registers this device's sensors when needed and registers the
     * device again if it was removed from Home Assistant.
     */
    private static final class Session {
        private final Context mContext;
        private final SharedPreferences mPrefs;

        Session(@NonNull Context context, @NonNull SharedPreferences prefs) {
            mContext = context;
            mPrefs = prefs;
        }

        void fireEvent(@NonNull JSONObject eventData) throws HomeAssistantClient.HomeAssistantException {
            JSONObject data = new JSONObject();
            put(data, "event_type", EVENT_TYPE);
            put(data, "event_data", eventData);
            send("fire_event", data);
        }

        void updateSensors(@NonNull JSONArray updates) throws HomeAssistantClient.HomeAssistantException {
            ensureSensors();
            JSONObject response = send("update_sensor_states", updates);

            // Home Assistant answers for each sensor; a sensor removed by the user is reported as
            // not registered: register the sensors again and resend.
            if (hasUnregisteredSensor(response)) {
                LogUtils.i("HomeAssistant: sensors missing in Home Assistant, registering them again");
                forceRegisterSensors();
                send("update_sensor_states", updates);
            }
        }

        private boolean hasUnregisteredSensor(@NonNull JSONObject response) {
            java.util.Iterator<String> keys = response.keys();
            while (keys.hasNext()) {
                JSONObject result = response.optJSONObject(keys.next());
                if (result != null && !result.optBoolean("success", true)) {
                    JSONObject error = result.optJSONObject("error");
                    if (error != null && "not_registered".equals(error.optString("code"))) {
                        return true;
                    }
                }
            }
            return false;
        }

        void updateRegistration() throws HomeAssistantClient.HomeAssistantException {
            JSONObject registration = buildRegistration(mContext, mPrefs);
            JSONObject data = new JSONObject();
            put(data, "app_version", registration.opt("app_version"));
            put(data, "device_name", registration.opt("device_name"));
            put(data, "manufacturer", registration.opt("manufacturer"));
            put(data, "model", registration.opt("model"));
            put(data, "os_version", registration.opt("os_version"));
            put(data, "app_data", new JSONObject());
            send("update_registration", data);
        }

        void forceRegisterSensors() throws HomeAssistantClient.HomeAssistantException {
            mPrefs.edit().putInt(HomeAssistantSettings.KEY_HA_SENSORS_REGISTERED, 0).apply();
            ensureSensors();
        }

        void ensureSensors() throws HomeAssistantClient.HomeAssistantException {
            if (HomeAssistantSettings.areSensorsRegistered(mPrefs)) {
                return;
            }

            AlarmInstance next = AlarmInstance.getNextFiringAlarm(mContext);
            send("register_sensor", sensorRegistration(buildNextAlarmUpdate(mContext, mPrefs, next),
                "Next alarm", "timestamp", null));

            JSONArray options = new JSONArray();
            options.put(STATE_IDLE);
            options.put(STATE_RINGING);
            options.put(STATE_SNOOZED);
            JSONObject state = buildAlarmStateUpdate(mPrefs, null, STATE_IDLE, null);
            JSONObject attributes = state.optJSONObject("attributes");
            if (attributes != null) {
                put(attributes, "options", options);
            }
            send("register_sensor", sensorRegistration(state, "Alarm state", "enum", null));

            HomeAssistantSettings.setSensorsRegistered(mPrefs);
        }

        @NonNull
        private JSONObject send(@NonNull String type, @NonNull Object data) throws HomeAssistantClient.HomeAssistantException {
            String webhookId = HomeAssistantSettings.getWebhookId(mPrefs);
            HomeAssistantClient client = new HomeAssistantClient(HomeAssistantSettings.getUrl(mPrefs),
                HomeAssistantSettings.getToken(mPrefs));

            try {
                return client.sendWebhook(Objects.requireNonNull(webhookId), type, data);
            } catch (HomeAssistantClient.HomeAssistantException e) {
                if (e.failure != HomeAssistantClient.Failure.WEBHOOK_GONE || TextUtils.isEmpty(HomeAssistantSettings.getToken(mPrefs))) {
                    throw e;
                }

                // The device was deleted in Home Assistant: register it again, once.
                LogUtils.i("HomeAssistant: device removed from Home Assistant, registering again");
                HomeAssistantSettings.clearWebhook(mPrefs);
                String newWebhookId = client.registerDevice(buildRegistration(mContext, mPrefs));
                mPrefs.edit().putString(HomeAssistantSettings.KEY_HA_WEBHOOK_ID, newWebhookId).apply();

                if (!"register_sensor".equals(type)) {
                    ensureSensors();
                }
                return client.sendWebhook(newWebhookId, type, data);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Payloads

    @NonNull
    static JSONObject buildRegistration(@NonNull Context context, @NonNull SharedPreferences prefs) {
        JSONObject registration = new JSONObject();
        put(registration, "device_id", HomeAssistantSettings.getDeviceId(prefs));
        put(registration, "app_id", BuildConfig.APPLICATION_ID);
        put(registration, "app_name", "Clock");
        put(registration, "app_version", BuildConfig.VERSION_NAME);
        put(registration, "device_name", HomeAssistantSettings.getDeviceName(prefs));
        put(registration, "manufacturer", Build.MANUFACTURER);
        put(registration, "model", Build.MODEL);
        put(registration, "os_name", "Android");
        put(registration, "os_version", Build.VERSION.RELEASE);
        put(registration, "supports_encryption", false);
        put(registration, "app_data", new JSONObject());
        return registration;
    }

    @NonNull
    private static JSONObject sensorRegistration(@NonNull JSONObject update, @NonNull String name,
                                                 @NonNull String deviceClass, @Nullable String entityCategory) {
        JSONObject sensor = new JSONObject();
        put(sensor, "unique_id", update.optString("unique_id"));
        put(sensor, "type", "sensor");
        put(sensor, "name", name);
        put(sensor, "device_class", deviceClass);
        put(sensor, "icon", update.optString("icon"));
        put(sensor, "state", update.opt("state"));
        put(sensor, "attributes", update.optJSONObject("attributes"));
        if (entityCategory != null) {
            put(sensor, "entity_category", entityCategory);
        }
        return sensor;
    }

    @NonNull
    static JSONObject buildNextAlarmUpdate(@NonNull Context context, @NonNull SharedPreferences prefs,
                                           @Nullable AlarmInstance next) {
        JSONObject update = new JSONObject();
        put(update, "unique_id", SENSOR_NEXT_ALARM);
        put(update, "type", "sensor");

        JSONObject attributes = new JSONObject();
        if (next == null) {
            put(update, "state", JSONObject.NULL);
            put(update, "icon", "mdi:alarm-off");
        } else {
            put(update, "state", formatTimestamp(next.getAlarmTime()));
            put(update, "icon", "mdi:alarm");
            put(attributes, "time", String.format(Locale.ROOT, "%02d:%02d", next.mHour, next.mMinute));
            if (next.mAlarmId != null) {
                put(attributes, "alarm_id", next.mAlarmId);
            }
            if (HomeAssistantSettings.shareLabels(prefs)) {
                put(attributes, "label", next.mLabel == null ? "" : next.mLabel);
            }

            Alarm alarm = next.mAlarmId == null ? null : Alarm.getAlarm(context.getContentResolver(), next.mAlarmId);
            put(attributes, "repeat", alarm == null ? new JSONArray() : weekdays(alarm.daysOfWeek));
        }
        put(update, "attributes", attributes);
        return update;
    }

    @NonNull
    static JSONObject buildAlarmStateUpdate(@NonNull SharedPreferences prefs, @Nullable AlarmInstance instance,
                                            @NonNull String state, @Nullable Calendar snoozeUntil) {
        JSONObject update = new JSONObject();
        put(update, "unique_id", SENSOR_ALARM_STATE);
        put(update, "type", "sensor");
        put(update, "state", state);
        put(update, "icon", switch (state) {
            case STATE_RINGING -> "mdi:alarm-light";
            case STATE_SNOOZED -> "mdi:alarm-snooze";
            default -> "mdi:alarm-check";
        });

        JSONObject attributes = new JSONObject();
        if (instance != null && !STATE_IDLE.equals(state)) {
            put(attributes, "alarm_time", formatTimestamp(instance.getAlarmTime()));
            if (instance.mAlarmId != null) {
                put(attributes, "alarm_id", instance.mAlarmId);
            }
            if (HomeAssistantSettings.shareLabels(prefs)) {
                put(attributes, "label", instance.mLabel == null ? "" : instance.mLabel);
            }
        }
        if (snoozeUntil != null) {
            put(attributes, "snoozed_until", formatTimestamp(snoozeUntil));
        }
        put(update, "attributes", attributes);
        return update;
    }

    @NonNull
    private static JSONObject buildEventData(@NonNull Context context, @NonNull SharedPreferences prefs,
                                             @NonNull AlarmInstance instance, @NonNull String type,
                                             @Nullable Calendar snoozeUntil) {
        JSONObject data = new JSONObject();
        put(data, "type", type);
        put(data, "device_name", HomeAssistantSettings.getDeviceName(prefs));
        put(data, "alarm_time", formatTimestamp(instance.getAlarmTime()));
        put(data, "time", String.format(Locale.ROOT, "%02d:%02d", instance.mHour, instance.mMinute));
        if (instance.mAlarmId != null) {
            put(data, "alarm_id", instance.mAlarmId);
        }
        if (HomeAssistantSettings.shareLabels(prefs)) {
            put(data, "label", instance.mLabel == null ? "" : instance.mLabel);
        }
        if (snoozeUntil != null) {
            put(data, "snoozed_until", formatTimestamp(snoozeUntil));
        }
        return data;
    }

    @NonNull
    private static JSONArray weekdays(@NonNull Weekdays weekdays) {
        JSONArray days = new JSONArray();
        int[] calendarDays = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        String[] names = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
        for (int i = 0; i < calendarDays.length; i++) {
            if (weekdays.isBitOn(calendarDays[i])) {
                days.put(names[i]);
            }
        }
        return days;
    }

    /**
     * @return an ISO 8601 timestamp with the UTC offset, as Home Assistant expects for timestamp
     * sensors, e.g. {@code 2026-11-14T07:00:00+01:00}.
     */
    @VisibleForTesting
    @NonNull
    static String formatTimestamp(@NonNull Calendar calendar) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        format.setTimeZone(calendar.getTimeZone());
        return format.format(new Date(calendar.getTimeInMillis()));
    }

    @NonNull
    private static JSONArray array(@NonNull JSONObject object) {
        JSONArray array = new JSONArray();
        array.put(object);
        return array;
    }

    private static void put(@NonNull JSONObject object, @NonNull String key, @Nullable Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
