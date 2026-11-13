// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.ringtone;

import android.net.Uri;

import androidx.annotation.NonNull;

/**
 * A Spotify track, album, playlist, artist, show or episode saved in the ringtone picker.
 */
public class SpotifyRingtoneHolder extends RingtoneHolder {

    SpotifyRingtoneHolder(@NonNull Uri alarmUri, @NonNull String title) {
        super(alarmUri, title);
    }

    @Override
    public int getViewType() {
        return RingtoneAdapter.VIEW_TYPE_SPOTIFY_SOUND;
    }
}
