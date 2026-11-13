// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.ringtone;

import static android.view.View.GONE;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.databinding.RingtoneItemSoundBinding;
import com.best.deskclock.spotify.SpotifyAccount;
import com.best.deskclock.uidata.UiConfig;

/**
 * Displays the "Add from Spotify" row of the ringtone picker.
 */
public class SpotifyAddViewHolder extends RecyclerView.ViewHolder {

    private final RingtoneItemSoundBinding mBinding;

    public SpotifyAddViewHolder(@NonNull View itemView, @NonNull UiConfig.Fonts fonts) {
        super(itemView);

        mBinding = RingtoneItemSoundBinding.bind(itemView);
        mBinding.soundImageSelected.setVisibility(GONE);
        mBinding.deleteRingtoneButton.setVisibility(GONE);
        mBinding.ringtoneName.setTypeface(fonts.general());
    }

    public void bind(@NonNull SpotifyAddHolder holder, @NonNull RingtoneAdapter.OnRingtoneClickListener listener) {
        if (holder.isBrowse()) {
            mBinding.ringtoneImage.setImageDrawable(
                AppCompatResources.getDrawable(itemView.getContext(), R.drawable.ic_library_music));
            mBinding.ringtoneName.setText(SpotifyAccount.isLoggedIn(itemView.getContext())
                ? R.string.spotify_browse_row
                : R.string.spotify_login_row);
            itemView.setOnClickListener(v -> listener.onBrowseSpotifyClick());
        } else {
            mBinding.ringtoneImage.setImageDrawable(AppCompatResources.getDrawable(itemView.getContext(), R.drawable.ic_add));
            mBinding.ringtoneName.setText(R.string.spotify_add_row);
            itemView.setOnClickListener(v -> listener.onAddSpotifyClick());
        }
    }
}
