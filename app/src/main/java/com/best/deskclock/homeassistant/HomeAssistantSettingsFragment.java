// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_CONNECTION;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_DEVICE_NAME;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_DISCONNECT;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_ENABLED;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_EXAMPLES;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_SHARE_ALARM_EVENTS;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_SHARE_LABELS;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_SHARE_NEXT_ALARM;
import static com.best.deskclock.homeassistant.HomeAssistantSettings.KEY_HA_TEST_EVENT;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.HapticFeedbackConstantsCompat;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.BaseSettingsScreenFragment;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.best.deskclock.utils.Utils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Date;

/**
 * Settings screen of the Home Assistant integration.
 */
public class HomeAssistantSettingsFragment extends BaseSettingsScreenFragment
    implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private Preference mConnectionPref;
    private SwitchPreferenceCompat mEnabledPref;
    private SwitchPreferenceCompat mShareNextAlarmPref;
    private SwitchPreferenceCompat mShareEventsPref;
    private Preference mDeviceNamePref;

    private final SharedPreferences.OnSharedPreferenceChangeListener mStatusListener = (prefs, key) -> {
        if (HomeAssistantSettings.KEY_HA_LAST_SYNC.equals(key) || HomeAssistantSettings.KEY_HA_LAST_ERROR.equals(key)
            || HomeAssistantSettings.KEY_HA_WEBHOOK_ID.equals(key)) {
            if (isAdded()) {
                requireActivity().runOnUiThread(this::refresh);
            }
        }
    };

    @Override
    protected String getFragmentTitle() {
        return getString(R.string.ha_settings_title);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_home_assistant);

        mConnectionPref = findPreference(KEY_HA_CONNECTION);
        mEnabledPref = findPreference(KEY_HA_ENABLED);
        mShareNextAlarmPref = findPreference(KEY_HA_SHARE_NEXT_ALARM);
        mShareEventsPref = findPreference(KEY_HA_SHARE_ALARM_EVENTS);
        mDeviceNamePref = findPreference(KEY_HA_DEVICE_NAME);

        for (String key : new String[]{KEY_HA_CONNECTION, KEY_HA_DEVICE_NAME, KEY_HA_EXAMPLES, KEY_HA_TEST_EVENT, KEY_HA_DISCONNECT}) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceClickListener(this);
            }
        }

        for (String key : new String[]{KEY_HA_ENABLED, KEY_HA_SHARE_NEXT_ALARM, KEY_HA_SHARE_ALARM_EVENTS, KEY_HA_SHARE_LABELS}) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener(this);
            }
        }

        getChildFragmentManager().setFragmentResultListener(HomeAssistantConnectDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mEnabledPref.setChecked(true);
                refresh();
                CustomToast.showLong(requireContext(), getAccentStyle(), getGeneralTypeface(),
                    getString(R.string.ha_connected_toast, bundle.getString(HomeAssistantConnectDialogFragment.RESULT_SERVER_NAME, "")));
            });

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        getPrefs().registerOnSharedPreferenceChangeListener(mStatusListener);
        refresh();
    }

    @Override
    public void onPause() {
        getPrefs().unregisterOnSharedPreferenceChangeListener(mStatusListener);
        super.onPause();
    }

    @Override
    protected boolean isPreferenceVisible(@NonNull Preference pref, @NonNull String key) {
        if (KEY_HA_CONNECTION.equals(key)) {
            return super.isPreferenceVisible(pref, key);
        }

        // Before connecting, only the connection entry is shown: one obvious thing to do.
        return HomeAssistantSettings.isConnected(getPrefs()) && super.isPreferenceVisible(pref, key);
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference pref, @NonNull Object newValue) {
        Utils.performHapticFeedback(getView(), isVibrationsEnabled(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);

        // The preference value is saved after this method returns: sync once it is.
        getListView().post(() -> {
            if (!isAdded()) {
                return;
            }

            if (KEY_HA_SHARE_NEXT_ALARM.equals(pref.getKey()) && !(Boolean) newValue) {
                HomeAssistant.clearNextAlarm(requireContext());
            } else if (HomeAssistantSettings.isActive(getPrefs())) {
                HomeAssistant.syncNow(requireContext(), null);
            }
            refresh();
        });

        return true;
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        switch (pref.getKey()) {
            case KEY_HA_CONNECTION -> HomeAssistantConnectDialogFragment.show(getChildFragmentManager());
            case KEY_HA_DEVICE_NAME -> showDeviceNameDialog();
            case KEY_HA_EXAMPLES -> showExamplesDialog();
            case KEY_HA_TEST_EVENT -> sendTestEvent(pref);
            case KEY_HA_DISCONNECT -> showDisconnectDialog();
        }

        return true;
    }

    private void refresh() {
        if (!isAdded() || mConnectionPref == null) {
            return;
        }

        final SharedPreferences prefs = getPrefs();
        final boolean connected = HomeAssistantSettings.isConnected(prefs);

        if (connected) {
            mConnectionPref.setTitle(getString(R.string.ha_connected_title, HomeAssistantSettings.getServerName(prefs)));

            String status;
            String error = HomeAssistantSettings.getLastError(prefs);
            long lastSync = HomeAssistantSettings.getLastSync(prefs);
            if (!TextUtils.isEmpty(error)) {
                status = getString(R.string.ha_status_error, getString(errorMessageFor(error)));
            } else if (lastSync > 0) {
                status = getString(R.string.ha_status_ok, formatTime(lastSync));
            } else {
                status = "";
            }

            String url = HomeAssistantSettings.getUrl(prefs);
            mConnectionPref.setSummary(TextUtils.isEmpty(status) ? url : url + "\n" + status);
        } else {
            mConnectionPref.setTitle(R.string.ha_not_connected_title);
            mConnectionPref.setSummary(R.string.ha_not_connected_summary);
        }

        final String prefix = HomeAssistantSettings.getEntityPrefix(prefs);
        mShareNextAlarmPref.setSummary(getString(R.string.ha_share_next_alarm_summary, "sensor." + prefix + "_next_alarm"));
        mShareEventsPref.setSummary(getString(R.string.ha_share_alarm_events_summary,
            HomeAssistant.EVENT_TYPE, "sensor." + prefix + "_alarm_state"));
        mDeviceNamePref.setSummary(HomeAssistantSettings.getDeviceName(prefs));

        invalidatePreferencesVisibility();
    }

    private int errorMessageFor(@NonNull String failureName) {
        try {
            return HomeAssistantConnectDialogFragment.errorMessage(HomeAssistantClient.Failure.valueOf(failureName));
        } catch (IllegalArgumentException e) {
            return R.string.ha_error_network;
        }
    }

    @NonNull
    private String formatTime(long time) {
        Date date = new Date(time);
        boolean today = DateFormat.format("yyyyMMdd", date).equals(DateFormat.format("yyyyMMdd", new Date()));
        String clock = DateFormat.getTimeFormat(requireContext()).format(date);
        return today ? clock : DateFormat.getMediumDateFormat(requireContext()).format(date) + " " + clock;
    }

    private void sendTestEvent(@NonNull Preference pref) {
        pref.setEnabled(false);
        HomeAssistant.sendTestEvent(requireContext(), error -> {
            pref.setEnabled(true);
            if (!isAdded()) {
                return;
            }

            if (error == null) {
                CustomToast.showLong(requireContext(), getAccentStyle(), getGeneralTypeface(),
                    getString(R.string.ha_test_event_sent, HomeAssistant.EVENT_TYPE));
            } else {
                CustomToast.showLong(requireContext(), getAccentStyle(), getGeneralTypeface(),
                    getString(HomeAssistantConnectDialogFragment.errorMessage(error.failure)));
            }
            refresh();
        });
    }

    private void showDeviceNameDialog() {
        final SharedPreferences prefs = getPrefs();
        final Typeface typeface = getGeneralTypeface();

        TextInputLayout layout = new TextInputLayout(requireContext());
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setHint(HomeAssistantSettings.getDefaultDeviceName());
        edit.setText(prefs.getString(KEY_HA_DEVICE_NAME, ""));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        edit.setSingleLine(true);
        edit.setTypeface(typeface);
        layout.addView(edit);
        layout.setHelperText(getString(R.string.ha_device_name_helper));
        layout.setTypeface(typeface);

        FrameLayout container = new FrameLayout(requireContext());
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        container.setPadding(padding, 0, padding, 0);
        container.addView(layout);

        mActiveDialog = CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_home_automation),
            getString(R.string.ha_device_name_title),
            null,
            container,
            getString(android.R.string.ok),
            (d, w) -> {
                String name = edit.getText() == null ? "" : edit.getText().toString().trim();
                prefs.edit().putString(KEY_HA_DEVICE_NAME, name).apply();
                if (HomeAssistantSettings.isConnected(prefs)) {
                    HomeAssistant.syncNow(requireContext(), null);
                }
                refresh();
            },
            getString(android.R.string.cancel),
            null,
            null,
            null,
            null,
            CustomDialog.SoftInputMode.SHOW_KEYBOARD
        );
        mActiveDialog.show();
        edit.requestFocus();
    }

    private void showExamplesDialog() {
        final SharedPreferences prefs = getPrefs();
        final Typeface typeface = getGeneralTypeface();
        final LayoutInflater inflater = LayoutInflater.from(requireContext());

        View view = inflater.inflate(R.layout.home_assistant_examples_dialog, null);
        LinearLayout container = view.findViewById(R.id.examples_container);
        TextView intro = view.findViewById(R.id.examples_intro);
        intro.setTypeface(typeface);

        for (HomeAssistantExamples.Example example
            : HomeAssistantExamples.automations(requireContext(), HomeAssistantSettings.getEntityPrefix(prefs))) {

            View item = inflater.inflate(R.layout.home_assistant_example_item, container, false);
            TextView title = item.findViewById(R.id.example_title);
            TextView code = item.findViewById(R.id.example_code);
            Button copy = item.findViewById(R.id.example_copy);

            title.setText(example.title());
            title.setTypeface(typeface);
            code.setText(example.yaml());
            copy.setTypeface(typeface);
            copy.setOnClickListener(v -> {
                ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText(example.title(), example.yaml()));
                CustomToast.show(requireContext(), getAccentStyle(), typeface, R.string.ha_examples_copied);
            });

            container.addView(item);
        }

        mActiveDialog = CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_home_automation),
            getString(R.string.ha_examples_title),
            null,
            view,
            getString(R.string.ha_examples_open),
            (d, w) -> openInHomeAssistant("/config/automation/edit/new"),
            getString(R.string.ha_examples_close),
            null,
            null,
            null,
            null,
            CustomDialog.SoftInputMode.NONE
        );
        mActiveDialog.show();
    }

    private void openInHomeAssistant(@NonNull String path) {
        String url = HomeAssistantSettings.getUrl(getPrefs());
        if (TextUtils.isEmpty(url)) {
            return;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url + path)));
        } catch (ActivityNotFoundException e) {
            CustomToast.show(requireContext(), getAccentStyle(), getGeneralTypeface(), R.string.ha_no_browser);
        }
    }

    private void showDisconnectDialog() {
        mActiveDialog = CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_home_automation),
            getString(R.string.ha_disconnect_title),
            getString(R.string.ha_disconnect_message, HomeAssistantSettings.getServerName(getPrefs())),
            null,
            getString(R.string.ha_disconnect_confirm),
            (d, w) -> {
                HomeAssistantSettings.disconnect(getPrefs());
                mEnabledPref.setChecked(false);
                refresh();
            },
            getString(android.R.string.cancel),
            null,
            null,
            null,
            null,
            CustomDialog.SoftInputMode.NONE
        );
        mActiveDialog.show();
    }
}
