// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.databinding.HomeAssistantConnectDialogBinding;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;
import com.google.android.material.color.MaterialColors;

import java.util.List;

/**
 * Connects the app to Home Assistant: the user enters (or discovers) the server address and pastes
 * a long-lived access token. The dialog stays open until the connection works, and explains what
 * to fix when it doesn't.
 */
public class HomeAssistantConnectDialogFragment extends DialogFragment {

    private static final String TAG = "home_assistant_connect_dialog";

    public static final String REQUEST_KEY = "home_assistant_connect_request_key";
    public static final String RESULT_SERVER_NAME = "result_server_name";

    private static final String STATE_URL = "state_url";
    private static final String STATE_TOKEN = "state_token";

    private HomeAssistantConnectDialogBinding mBinding;
    private Button mConnectButton;
    private HomeAssistantDiscovery mDiscovery;
    private boolean mBusy;

    private final TextWatcher mWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            hideStatus();
            updateButtons();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    @NonNull
    public static HomeAssistantConnectDialogFragment newInstance() {
        return new HomeAssistantConnectDialogFragment();
    }

    public static void show(@NonNull FragmentManager manager) {
        Utils.showDialogFragment(manager, newInstance(), TAG);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mBinding != null) {
            outState.putString(STATE_URL, textOf(mBinding.editUrl.getText()));
            outState.putString(STATE_TOKEN, textOf(mBinding.editToken.getText()));
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final SharedPreferences prefs = getDefaultSharedPreferences(requireContext());
        final Typeface typeface = ThemeUtils.loadFont(SettingsDAO.getGeneralFont(prefs));

        mBinding = HomeAssistantConnectDialogBinding.inflate(getLayoutInflater());
        mBinding.textInputLayoutUrl.setTypeface(typeface);
        mBinding.editUrl.setTypeface(typeface);
        mBinding.textInputLayoutToken.setTypeface(typeface);
        mBinding.createToken.setTypeface(typeface);
        mBinding.statusText.setTypeface(typeface);

        String url = HomeAssistantSettings.getUrl(prefs);
        String token = HomeAssistantSettings.getToken(prefs);
        if (savedInstanceState != null) {
            url = savedInstanceState.getString(STATE_URL, url);
            token = savedInstanceState.getString(STATE_TOKEN, token);
        }

        mBinding.editUrl.setText(url);
        mBinding.editToken.setText(token);
        mBinding.editUrl.addTextChangedListener(mWatcher);
        mBinding.editToken.addTextChangedListener(mWatcher);

        mBinding.textInputLayoutUrl.setEndIconOnClickListener(v -> discover());
        mBinding.createToken.setOnClickListener(v -> openTokenPage());
        mBinding.editToken.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && canConnect()) {
                connect();
                return true;
            }
            return false;
        });

        return CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_home_automation),
            getString(R.string.ha_connect_title),
            null,
            mBinding.getRoot(),
            getString(R.string.ha_connect_button),
            null,
            getString(android.R.string.cancel),
            null,
            null,
            null,
            alertDialog -> {
                mConnectButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                // Keep the dialog open until the connection succeeds.
                mConnectButton.setOnClickListener(v -> connect());
                updateButtons();

                if (TextUtils.isEmpty(textOf(mBinding.editUrl.getText()))) {
                    discover();
                }
            },
            CustomDialog.SoftInputMode.SHOW_KEYBOARD
        );
    }

    @Override
    public void onDestroyView() {
        if (mDiscovery != null) {
            mDiscovery.stop();
            mDiscovery = null;
        }
        if (mBinding != null) {
            mBinding.editUrl.removeTextChangedListener(mWatcher);
            mBinding.editToken.removeTextChangedListener(mWatcher);
        }
        mBinding = null;
        mConnectButton = null;

        super.onDestroyView();
    }

    private void discover() {
        if (mBinding == null || mBusy) {
            return;
        }

        if (mDiscovery != null) {
            mDiscovery.stop();
        }

        showStatus(true, getString(R.string.ha_discovering), false);
        mDiscovery = new HomeAssistantDiscovery(requireContext());
        mDiscovery.start(servers -> {
            if (mBinding == null) {
                return;
            }

            if (servers.isEmpty()) {
                // Discovery only helps: when it finds nothing, quietly let the user type.
                if (TextUtils.isEmpty(textOf(mBinding.editUrl.getText()))) {
                    showStatus(false, getString(R.string.ha_discover_none), false);
                } else {
                    hideStatus();
                }
            } else if (servers.size() == 1) {
                useDiscovered(servers.get(0));
            } else {
                chooseServer(servers);
            }
        });
    }

    private void chooseServer(@NonNull List<HomeAssistantDiscovery.Server> servers) {
        hideStatus();
        CharSequence[] names = new CharSequence[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            names[i] = servers.get(i).name() + "\n" + servers.get(i).url();
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ha_discover_choose)
            .setItems(names, (d, which) -> useDiscovered(servers.get(which)))
            .show();
    }

    private void useDiscovered(@NonNull HomeAssistantDiscovery.Server server) {
        if (mBinding == null) {
            return;
        }

        mBinding.editUrl.setText(server.url());
        showStatus(false, getString(R.string.ha_discover_found, server.name()), false);
        mBinding.editToken.requestFocus();
    }

    private void openTokenPage() {
        String url = HomeAssistantClient.normalizeUrl(textOf(mBinding.editUrl.getText()));
        if (url.isEmpty()) {
            mBinding.textInputLayoutUrl.setError(getString(R.string.ha_url_needed_for_token));
            return;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url + "/profile/security"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            showStatus(false, getString(R.string.ha_no_browser), true);
        }
    }

    private boolean canConnect() {
        return mBinding != null
            && !HomeAssistantClient.normalizeUrl(textOf(mBinding.editUrl.getText())).isEmpty()
            && !textOf(mBinding.editToken.getText()).trim().isEmpty();
    }

    private void updateButtons() {
        if (mConnectButton != null) {
            mConnectButton.setEnabled(!mBusy && canConnect());
        }
        if (mBinding != null) {
            mBinding.textInputLayoutUrl.setError(null);

            String url = HomeAssistantClient.normalizeUrl(textOf(mBinding.editUrl.getText()));
            mBinding.textInputLayoutUrl.setHelperText(getString(HomeAssistantClient.isInsecurePublicUrl(url)
                ? R.string.ha_url_not_secure
                : R.string.ha_url_helper));
        }
    }

    private void connect() {
        if (!canConnect() || mBusy) {
            return;
        }

        if (mDiscovery != null) {
            mDiscovery.stop();
            mDiscovery = null;
        }

        final String url = textOf(mBinding.editUrl.getText());
        final String token = textOf(mBinding.editToken.getText()).trim();

        mBusy = true;
        updateButtons();
        showStatus(true, getString(R.string.ha_connecting), false);

        HomeAssistant.connect(requireContext(), url, token, new HomeAssistant.ConnectCallback() {
            @Override
            public void onConnected(@NonNull HomeAssistantClient.ServerInfo info) {
                mBusy = false;
                if (!isAdded()) {
                    return;
                }

                Bundle result = new Bundle();
                result.putString(RESULT_SERVER_NAME, info.locationName());
                getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
                dismissAllowingStateLoss();
            }

            @Override
            public void onFailed(@NonNull HomeAssistantClient.HomeAssistantException error) {
                mBusy = false;
                if (mBinding == null) {
                    return;
                }

                showStatus(false, getString(errorMessage(error.failure)), true);
                updateButtons();
            }
        });
    }

    /**
     * @return a message that tells the user what to fix.
     */
    @StringRes
    public static int errorMessage(@NonNull HomeAssistantClient.Failure failure) {
        return switch (failure) {
            case INVALID_URL -> R.string.ha_error_invalid_url;
            case HOST_NOT_FOUND -> R.string.ha_error_host_not_found;
            case CONNECTION_REFUSED -> R.string.ha_error_refused;
            case TIMEOUT -> R.string.ha_error_timeout;
            case SSL -> R.string.ha_error_ssl;
            case UNAUTHORIZED -> R.string.ha_error_unauthorized;
            case NOT_HOME_ASSISTANT -> R.string.ha_error_not_home_assistant;
            case MOBILE_APP_MISSING -> R.string.ha_error_mobile_app;
            case WEBHOOK_GONE -> R.string.ha_error_webhook_gone;
            case SERVER_ERROR -> R.string.ha_error_server;
            case NETWORK -> R.string.ha_error_network;
        };
    }

    private void showStatus(boolean progress, @NonNull String text, boolean isError) {
        if (mBinding == null) {
            return;
        }

        mBinding.statusContainer.setVisibility(View.VISIBLE);
        mBinding.statusProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
        mBinding.statusText.setText(text);

        int color = isError
            ? ContextCompat.getColor(requireContext(), R.color.md_theme_error)
            : MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        mBinding.statusText.setTextColor(ColorStateList.valueOf(color));
    }

    private void hideStatus() {
        if (mBinding != null && !mBusy) {
            mBinding.statusContainer.setVisibility(View.GONE);
        }
    }

    @NonNull
    private static String textOf(@Nullable CharSequence text) {
        return text == null ? "" : text.toString();
    }
}
