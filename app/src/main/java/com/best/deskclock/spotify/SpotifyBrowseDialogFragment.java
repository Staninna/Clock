// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.databinding.SpotifyBrowseDialogBinding;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user pick Spotify content from their account: their playlists, or anything found with
 * the search. Also handles the one-time setup (Client ID) and the login.
 *
 * <p>The chosen content is returned like {@link SpotifySourceDialogFragment} does, with
 * {@link SpotifySourceDialogFragment#REQUEST_KEY}.</p>
 */
public class SpotifyBrowseDialogFragment extends DialogFragment {

    private static final String TAG = "spotify_browse_dialog";
    private static final long SEARCH_DELAY_MS = 400;
    private static final int MAX_PLAYLISTS = 200;

    private enum Mode { SETUP, LOGIN, BROWSE }

    private SpotifyBrowseDialogBinding mBinding;
    private Button mPositiveButton;
    private Mode mMode;
    private Typeface mTypeface;
    private final ItemsAdapter mAdapter = new ItemsAdapter();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private List<SpotifyWebApi.Item> mPlaylists;
    private int mRequestId;

    private final Runnable mSearchRunnable = this::runSearch;

    private final SharedPreferences.OnSharedPreferenceChangeListener mAccountListener = (prefs, key) -> {
        if (SpotifyAccount.KEY_USER_NAME.equals(key) || SpotifyAccount.KEY_REFRESH_TOKEN.equals(key)) {
            mHandler.post(this::updateMode);
        }
    };

    public static void show(@NonNull FragmentManager manager) {
        Utils.showDialogFragment(manager, new SpotifyBrowseDialogFragment(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        mTypeface = ThemeUtils.loadFont(SettingsDAO.getGeneralFont(getDefaultSharedPreferences(requireContext())));
        mBinding = SpotifyBrowseDialogBinding.inflate(getLayoutInflater());

        for (TextView view : new TextView[]{mBinding.setupText, mBinding.openDashboard, mBinding.loginText,
            mBinding.accountText, mBinding.logoutButton, mBinding.listTitle, mBinding.emptyText,
            mBinding.search, mBinding.clientId, mBinding.redirectUri}) {
            view.setTypeface(mTypeface);
        }

        // Setup
        mBinding.redirectUri.setText(SpotifyAccount.getRedirectUri());
        mBinding.redirectLayout.setEndIconOnClickListener(v -> copy(SpotifyAccount.getRedirectUri()));
        mBinding.openDashboard.setOnClickListener(v -> openUrl("https://developer.spotify.com/dashboard/create"));
        mBinding.clientIdLayout.setEndIconOnClickListener(v -> pasteClientId());
        mBinding.clientId.addTextChangedListener(new SimpleWatcher(this::updateButtons));

        // Browse
        mBinding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.list.setAdapter(mAdapter);
        mBinding.logoutButton.setOnClickListener(v -> {
            SpotifyAccount.logout(requireContext());
            mPlaylists = null;
            updateMode();
        });
        mBinding.search.addTextChangedListener(new SimpleWatcher(() -> {
            mHandler.removeCallbacks(mSearchRunnable);
            mHandler.postDelayed(mSearchRunnable, SEARCH_DELAY_MS);
        }));
        mBinding.search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                mHandler.removeCallbacks(mSearchRunnable);
                runSearch();
                android.view.inputmethod.InputMethodManager imm =
                    requireContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        return CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_queue_music),
            getString(R.string.spotify_browse_title),
            null,
            mBinding.getRoot(),
            getString(R.string.spotify_login_button),
            null,
            getString(android.R.string.cancel),
            null,
            null,
            null,
            alertDialog -> {
                mPositiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                mPositiveButton.setOnClickListener(v -> onPositiveClick());
                updateMode();
            },
            CustomDialog.SoftInputMode.NONE
        );
    }

    @Override
    public void onStart() {
        super.onStart();
        getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(mAccountListener);
        updateMode();
    }

    @Override
    public void onStop() {
        getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(mAccountListener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacksAndMessages(null);
        mBinding = null;
        mPositiveButton = null;
        super.onDestroyView();
    }

    private void updateMode() {
        if (mBinding == null || !isAdded()) {
            return;
        }

        Mode mode;
        if (SpotifyAccount.isLoggedIn(requireContext())) {
            mode = Mode.BROWSE;
        } else if (SpotifyAccount.hasClientId(requireContext())) {
            mode = Mode.LOGIN;
        } else {
            mode = Mode.SETUP;
        }

        mBinding.setupContainer.setVisibility(mode == Mode.SETUP ? View.VISIBLE : View.GONE);
        mBinding.loginText.setVisibility(mode == Mode.LOGIN ? View.VISIBLE : View.GONE);
        mBinding.browseContainer.setVisibility(mode == Mode.BROWSE ? View.VISIBLE : View.GONE);

        if (mode == Mode.BROWSE) {
            String name = SpotifyAccount.getUserName(requireContext());
            mBinding.accountText.setText(getString(SpotifyAccount.isPremium(requireContext())
                ? R.string.spotify_logged_in_as_premium
                : R.string.spotify_logged_in_as_free, name));

            if (mMode != Mode.BROWSE) {
                loadPlaylists();
            }
        }

        mMode = mode;
        updateButtons();
    }

    private void updateButtons() {
        if (mPositiveButton == null || mBinding == null) {
            return;
        }

        switch (mMode) {
            case SETUP -> {
                mPositiveButton.setVisibility(View.VISIBLE);
                mPositiveButton.setText(R.string.spotify_login_button);
                mPositiveButton.setEnabled(SpotifyAccount.isValidClientId(textOf(mBinding.clientId.getText())));
            }
            case LOGIN -> {
                mPositiveButton.setVisibility(View.VISIBLE);
                mPositiveButton.setText(R.string.spotify_login_button);
                mPositiveButton.setEnabled(true);
            }
            case BROWSE -> mPositiveButton.setVisibility(View.GONE);
        }
    }

    private void onPositiveClick() {
        if (mMode == Mode.SETUP) {
            String clientId = textOf(mBinding.clientId.getText()).trim();
            if (!SpotifyAccount.isValidClientId(clientId)) {
                return;
            }
            SpotifyAccount.setClientId(requireContext(), clientId);
        }

        if (!SpotifyAccount.startLogin(requireContext())) {
            Toast.makeText(requireContext(), R.string.spotify_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Browsing

    private void loadPlaylists() {
        if (mPlaylists != null) {
            showItems(mPlaylists, R.string.spotify_your_playlists, R.string.spotify_no_playlists);
            return;
        }

        final int requestId = ++mRequestId;
        showLoading(R.string.spotify_your_playlists);
        final android.content.Context appContext = requireContext().getApplicationContext();

        SpotifyAccount.EXECUTOR.execute(() -> {
            List<SpotifyWebApi.Item> items = null;
            String error = null;
            try {
                String token = SpotifyAccount.getAccessToken(appContext);
                if (token != null) {
                    items = SpotifyWebApi.getMyPlaylists(token, MAX_PLAYLISTS);
                }
            } catch (SpotifyWebApi.SpotifyException e) {
                error = e.isNetworkError() ? null : String.valueOf(e.httpCode);
            }

            final List<SpotifyWebApi.Item> result = items;
            final String errorCode = error;
            mHandler.post(() -> {
                if (mBinding == null || requestId != mRequestId) {
                    return;
                }
                if (result != null) {
                    mPlaylists = result;
                    if (textOf(mBinding.search.getText()).trim().isEmpty()) {
                        showItems(result, R.string.spotify_your_playlists, R.string.spotify_no_playlists);
                    }
                } else {
                    showError(errorCode);
                }
            });
        });
    }

    private void runSearch() {
        if (mBinding == null) {
            return;
        }

        final String query = textOf(mBinding.search.getText()).trim();
        if (query.isEmpty()) {
            mRequestId++;
            loadPlaylists();
            return;
        }

        final int requestId = ++mRequestId;
        showLoading(R.string.spotify_search_results);
        final android.content.Context appContext = requireContext().getApplicationContext();

        SpotifyAccount.EXECUTOR.execute(() -> {
            List<SpotifyWebApi.Item> items = null;
            String error = null;
            try {
                String token = SpotifyAccount.getAccessToken(appContext);
                if (token != null) {
                    items = SpotifyWebApi.search(token, query);
                }
            } catch (SpotifyWebApi.SpotifyException e) {
                error = e.isNetworkError() ? null : String.valueOf(e.httpCode);
            }

            final List<SpotifyWebApi.Item> result = items;
            final String errorCode = error;
            mHandler.post(() -> {
                if (mBinding == null || requestId != mRequestId) {
                    return;
                }
                if (result != null) {
                    showItems(result, R.string.spotify_search_results, R.string.spotify_no_results);
                } else {
                    showError(errorCode);
                }
            });
        });
    }

    private void showLoading(int titleRes) {
        mBinding.listTitle.setText(titleRes);
        mBinding.progress.setVisibility(View.VISIBLE);
        mBinding.emptyText.setVisibility(View.GONE);
    }

    private void showItems(@NonNull List<SpotifyWebApi.Item> items, int titleRes, int emptyRes) {
        mBinding.listTitle.setText(titleRes);
        mBinding.progress.setVisibility(View.GONE);
        mAdapter.setItems(items);
        mBinding.emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        mBinding.emptyText.setText(emptyRes);
        mBinding.list.scrollToPosition(0);
    }

    /**
     * @param httpCode the HTTP code of the failed request, or {@code null} for a network error
     */
    private void showError(@Nullable String httpCode) {
        mBinding.progress.setVisibility(View.GONE);
        mAdapter.setItems(new ArrayList<>());
        mBinding.emptyText.setVisibility(View.VISIBLE);
        mBinding.emptyText.setText(httpCode == null
            ? getString(R.string.spotify_error_network)
            : getString(R.string.spotify_error_http, httpCode));

        // Logged out because the access was revoked: show the login again.
        if (!SpotifyAccount.isLoggedIn(requireContext())) {
            updateMode();
        }
    }

    private void onItemChosen(@NonNull SpotifyWebApi.Item item) {
        Bundle result = new Bundle();
        result.putString(SpotifySourceDialogFragment.RESULT_URI, item.source().toAlarmUri().toString());
        getParentFragmentManager().setFragmentResult(SpotifySourceDialogFragment.REQUEST_KEY, result);
        dismissAllowingStateLoss();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private void copy(@NonNull String text) {
        ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Redirect URI", text));
        Toast.makeText(requireContext(), R.string.spotify_copied, Toast.LENGTH_SHORT).show();
    }

    private void pasteClientId() {
        ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
            if (text != null) {
                mBinding.clientId.setText(text.toString().trim());
            }
        }
    }

    private void openUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.spotify_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private static String textOf(@Nullable CharSequence text) {
        return text == null ? "" : text.toString();
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable mOnChange;

        SimpleWatcher(@NonNull Runnable onChange) {
            mOnChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            mOnChange.run();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }

    private final class ItemsAdapter extends RecyclerView.Adapter<ItemsAdapter.Holder> {
        private List<SpotifyWebApi.Item> mItems = new ArrayList<>();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void setItems(@NonNull List<SpotifyWebApi.Item> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.spotify_browse_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            SpotifyWebApi.Item item = mItems.get(position);
            holder.name.setText(item.name());
            String type = getString(item.source().type().labelRes);
            holder.subtitle.setText(item.subtitle().isEmpty() ? type : type + " · " + item.subtitle());
            SpotifyImageLoader.load(holder.cover, item.imageUrl());
            holder.itemView.setOnClickListener(v -> onItemChosen(item));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView name;
            final TextView subtitle;

            Holder(@NonNull View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.cover);
                name = itemView.findViewById(R.id.name);
                subtitle = itemView.findViewById(R.id.subtitle);
                name.setTypeface(mTypeface);
                subtitle.setTypeface(mTypeface);
            }
        }
    }
}
