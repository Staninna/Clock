// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.ringtone;

/**
 * An action row of the Spotify section of the ringtone picker: browse the user's Spotify account,
 * or add content from a link.
 */
public class SpotifyAddHolder implements RingtoneAdapter.RingtoneItem {

    private final boolean mBrowse;

    /**
     * @param browse {@code true} for the "browse your Spotify" row, {@code false} for "add from link"
     */
    SpotifyAddHolder(boolean browse) {
        mBrowse = browse;
    }

    boolean isBrowse() {
        return mBrowse;
    }

    @Override
    public int getViewType() {
        return RingtoneAdapter.VIEW_TYPE_SPOTIFY_ADD;
    }
}
