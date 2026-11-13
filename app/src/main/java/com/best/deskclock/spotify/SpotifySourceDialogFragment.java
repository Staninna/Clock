// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.databinding.SpotifySourceDialogBinding;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;

/**
 * Lets the user add (or rename) a Spotify track, album, playlist, artist, show or episode to use
 * as an alarm sound.
 *
 * <p>The user pastes a Spotify share link. If the clipboard already holds one when the dialog
 * opens (or when the user comes back from Spotify), it is filled in automatically.</p>
 */
public class SpotifySourceDialogFragment extends DialogFragment {

    private static final String TAG = "spotify_source_dialog";

    public static final String REQUEST_KEY = "spotify_source_request_key";
    public static final String RESULT_URI = "result_uri";

    private static final String ARG_SOURCE_URI = "arg_source_uri";
    private static final String STATE_LINK = "state_link";
    private static final String STATE_NAME = "state_name";

    private SpotifySourceDialogBinding mBinding;
    private Button mOkButton;
    private String mLastClipboardLink;

    private final TextWatcher mLinkWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            updateValidation();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private final ViewTreeObserver.OnWindowFocusChangeListener mFocusListener = hasFocus -> {
        // The clipboard can only be read while the app has the focus (Android 10+).
        if (hasFocus) {
            fillFromClipboard(false);
        }
    };

    /**
     * @param existing the Spotify alarm URI to edit, or {@code null} to add a new source
     */
    @NonNull
    public static SpotifySourceDialogFragment newInstance(@Nullable Uri existing) {
        SpotifySourceDialogFragment fragment = new SpotifySourceDialogFragment();
        Bundle args = new Bundle();
        if (existing != null) {
            args.putString(ARG_SOURCE_URI, existing.toString());
        }
        fragment.setArguments(args);
        return fragment;
    }

    public static void show(@NonNull FragmentManager manager, @NonNull SpotifySourceDialogFragment fragment) {
        Utils.showDialogFragment(manager, fragment, TAG);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mBinding != null) {
            outState.putString(STATE_LINK, textOf(mBinding.editLink.getText()));
            outState.putString(STATE_NAME, textOf(mBinding.editName.getText()));
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Typeface typeface = ThemeUtils.loadFont(SettingsDAO.getGeneralFont(getDefaultSharedPreferences(requireContext())));
        mBinding = SpotifySourceDialogBinding.inflate(getLayoutInflater());

        mBinding.textInputLayoutLink.setTypeface(typeface);
        mBinding.editLink.setTypeface(typeface);
        mBinding.textInputLayoutName.setTypeface(typeface);
        mBinding.editName.setTypeface(typeface);
        mBinding.spotifyInfo.setTypeface(typeface);
        mBinding.openSpotify.setTypeface(typeface);

        String link = "";
        String name = "";
        SpotifyUtils.Source existing = SpotifyUtils.fromAlarmUri(parseArgUri());
        if (existing != null) {
            link = existing.webUrl();
            name = existing.title() == null ? "" : existing.title();
        }

        if (savedInstanceState != null) {
            link = savedInstanceState.getString(STATE_LINK, link);
            name = savedInstanceState.getString(STATE_NAME, name);
        }

        mBinding.editLink.setText(link);
        mBinding.editName.setText(name);
        mBinding.editLink.addTextChangedListener(mLinkWatcher);

        mBinding.textInputLayoutLink.setEndIconOnClickListener(v -> fillFromClipboard(true));

        mBinding.editName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && parseLink() != null) {
                deliverResult();
                dismiss();
                return true;
            }
            return false;
        });

        final boolean spotifyInstalled = SpotifyUtils.isSpotifyInstalled(requireContext());
        if (spotifyInstalled) {
            mBinding.openSpotify.setOnClickListener(v -> SpotifyUtils.openSpotify(requireContext()));
        } else {
            mBinding.openSpotify.setVisibility(View.GONE);
            mBinding.openSpotifyDivider.setVisibility(View.GONE);
            mBinding.spotifyInfo.setText(R.string.spotify_not_installed_info);
        }

        return CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_queue_music),
            getString(existing == null ? R.string.spotify_add_title : R.string.spotify_edit_title),
            null,
            mBinding.getRoot(),
            getString(existing == null ? R.string.spotify_add_button : android.R.string.ok),
            (d, w) -> deliverResult(),
            getString(android.R.string.cancel),
            null,
            null,
            null,
            alertDialog -> {
                mOkButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                updateValidation();
            },
            CustomDialog.SoftInputMode.SHOW_KEYBOARD
        );
    }

    @Override
    public void onStart() {
        super.onStart();

        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(mFocusListener);
        }
    }

    @Override
    public void onStop() {
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().getViewTreeObserver().removeOnWindowFocusChangeListener(mFocusListener);
        }

        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (mBinding != null) {
            mBinding.editLink.removeTextChangedListener(mLinkWatcher);
        }
        mBinding = null;
        mOkButton = null;

        super.onDestroyView();
    }

    /**
     * Fills the link field with a Spotify link found in the clipboard.
     *
     * @param userRequested {@code true} if the user pressed the paste button: the current text is
     *                      replaced and a message is shown when there is nothing to paste
     */
    private void fillFromClipboard(boolean userRequested) {
        if (mBinding == null) {
            return;
        }

        String current = textOf(mBinding.editLink.getText());
        if (!userRequested && SpotifyUtils.parse(current) != null) {
            // Never overwrite a valid link the user already entered.
            return;
        }

        String clip = readClipboard();
        SpotifyUtils.Source source = SpotifyUtils.parse(clip);
        if (source == null) {
            if (userRequested) {
                Toast.makeText(requireContext(), R.string.spotify_clipboard_empty, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Only auto-fill each copied link once, so clearing the field is respected.
        if (!userRequested && source.webUrl().equals(mLastClipboardLink)) {
            return;
        }

        mLastClipboardLink = source.webUrl();
        mBinding.editLink.setText(source.webUrl());
        mBinding.editLink.setSelection(textOf(mBinding.editLink.getText()).length());
    }

    @Nullable
    private String readClipboard() {
        try {
            ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return null;
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }

            CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
            return text == null ? null : text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void updateValidation() {
        if (mBinding == null) {
            return;
        }

        String link = textOf(mBinding.editLink.getText());
        SpotifyUtils.Source source = SpotifyUtils.parse(link);

        if (source != null) {
            mBinding.textInputLayoutLink.setError(null);
            mBinding.textInputLayoutLink.setHelperText(getString(R.string.spotify_link_detected,
                getString(source.type().labelRes)));
        } else if (link.trim().isEmpty() || SpotifyUtils.looksLikeLinkInProgress(link)) {
            mBinding.textInputLayoutLink.setError(null);
            mBinding.textInputLayoutLink.setHelperText(getString(R.string.spotify_link_helper));
        } else {
            mBinding.textInputLayoutLink.setError(getString(R.string.spotify_link_invalid));
        }

        if (mOkButton != null) {
            mOkButton.setEnabled(source != null);
        }
    }

    @Nullable
    private SpotifyUtils.Source parseLink() {
        return mBinding == null ? null : SpotifyUtils.parse(textOf(mBinding.editLink.getText()));
    }

    private void deliverResult() {
        SpotifyUtils.Source source = parseLink();
        if (source == null) {
            return;
        }

        source = source.withTitle(textOf(mBinding.editName.getText()));

        Bundle result = new Bundle();
        result.putString(RESULT_URI, source.toAlarmUri().toString());
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
    }

    @Nullable
    private Uri parseArgUri() {
        String uri = requireArguments().getString(ARG_SOURCE_URI);
        return TextUtils.isEmpty(uri) ? null : Uri.parse(uri);
    }

    @NonNull
    private static String textOf(@Nullable CharSequence text) {
        return text == null ? "" : text.toString();
    }
}
