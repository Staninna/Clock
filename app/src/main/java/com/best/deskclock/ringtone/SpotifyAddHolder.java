// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.ringtone;

/**
 * The "Add from Spotify" row of the ringtone picker.
 */
public class SpotifyAddHolder implements RingtoneAdapter.RingtoneItem {

    @Override
    public int getViewType() {
        return RingtoneAdapter.VIEW_TYPE_SPOTIFY_ADD;
    }
}
