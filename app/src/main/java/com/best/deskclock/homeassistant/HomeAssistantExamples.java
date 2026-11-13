// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import android.content.Context;

import androidx.annotation.NonNull;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.R;

import java.util.List;

/**
 * Ready-to-paste Home Assistant automations using this device's entities and events.
 */
public final class HomeAssistantExamples {

    /** An automation, as YAML for Home Assistant's automation editor (⋮ → Edit in YAML). */
    public record Example(@NonNull String title, @NonNull String yaml) {
    }

    private HomeAssistantExamples() {
    }

    /**
     * @param prefix the entity id prefix of this device, e.g. {@code pixel_8_clock}
     */
    @NonNull
    public static List<Example> automations(@NonNull Context context, @NonNull String prefix) {
        String nextAlarm = "sensor." + prefix + "_next_alarm";
        String deviceName = yamlString(HomeAssistantSettings.getDeviceName(
            DeskClockApplication.getDefaultSharedPreferences(context)));

        Example sunrise = new Example(context.getString(R.string.ha_example_sunrise),
            "alias: Sunrise before my alarm\n"
                + "triggers:\n"
                + "  - trigger: time\n"
                + "    at:\n"
                + "      entity_id: " + nextAlarm + "\n"
                + "      offset: \"-00:20:00\"\n"
                + "actions:\n"
                + "  - action: light.turn_on\n"
                + "    target:\n"
                + "      entity_id: light.bedroom\n"
                + "    data:\n"
                + "      brightness_pct: 100\n"
                + "      transition: 1200\n");

        Example morning = new Example(context.getString(R.string.ha_example_dismissed),
            "alias: Good morning routine\n"
                + "triggers:\n"
                + "  - trigger: event\n"
                + "    event_type: " + HomeAssistant.EVENT_TYPE + "\n"
                + "    event_data:\n"
                + "      type: " + HomeAssistant.TYPE_DISMISSED + "\n"
                + "      device_name: " + deviceName + "\n"
                + "actions:\n"
                + "  - action: switch.turn_on\n"
                + "    target:\n"
                + "      entity_id: switch.coffee_machine\n");

        Example snooze = new Example(context.getString(R.string.ha_example_snoozed),
            "alias: Lights up a bit when snoozing\n"
                + "triggers:\n"
                + "  - trigger: event\n"
                + "    event_type: " + HomeAssistant.EVENT_TYPE + "\n"
                + "    event_data:\n"
                + "      type: " + HomeAssistant.TYPE_SNOOZED + "\n"
                + "actions:\n"
                + "  - action: light.turn_on\n"
                + "    target:\n"
                + "      entity_id: light.bedroom\n"
                + "    data:\n"
                + "      brightness_pct: 30\n");

        return List.of(sunrise, morning, snooze);
    }

    @NonNull
    private static String yamlString(@NonNull String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
