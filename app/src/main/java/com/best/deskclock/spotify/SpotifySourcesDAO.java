// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.best.deskclock.utils.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores the Spotify sources the user added in the ringtone picker, so they can be reused for
 * other alarms. Each entry is the alarm URI produced by {@link SpotifyUtils.Source#toAlarmUri()}.
 */
public final class SpotifySourcesDAO {

    /** Key of the preference holding a JSON array of Spotify alarm URIs, most recent first. */
    public static final String KEY_SPOTIFY_SOURCES = "key_spotify_sources";

    private SpotifySourcesDAO() {
    }

    @NonNull
    public static List<SpotifyUtils.Source> getSources(@NonNull SharedPreferences prefs) {
        List<SpotifyUtils.Source> sources = new ArrayList<>();
        String json = prefs.getString(KEY_SPOTIFY_SOURCES, null);
        if (json == null) {
            return sources;
        }

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                SpotifyUtils.Source source = SpotifyUtils.fromAlarmUri(Uri.parse(array.getString(i)));
                if (source != null) {
                    sources.add(source);
                }
            }
        } catch (JSONException e) {
            LogUtils.e("Unable to read the saved Spotify sources", e);
        }

        return sources;
    }

    /**
     * Adds a source at the top of the list. A source with the same content replaces the old one,
     * so renaming it does not create duplicates.
     */
    public static void addSource(@NonNull SharedPreferences prefs, @NonNull SpotifyUtils.Source source) {
        List<SpotifyUtils.Source> sources = getSources(prefs);
        sources.removeIf(s -> sameContent(s, source));
        sources.add(0, source);
        save(prefs, sources);
    }

    public static void removeSource(@NonNull SharedPreferences prefs, @NonNull Uri alarmUri) {
        SpotifyUtils.Source toRemove = SpotifyUtils.fromAlarmUri(alarmUri);
        if (toRemove == null) {
            return;
        }

        List<SpotifyUtils.Source> sources = getSources(prefs);
        sources.removeIf(s -> sameContent(s, toRemove));
        save(prefs, sources);
    }

    private static boolean sameContent(@NonNull SpotifyUtils.Source a, @NonNull SpotifyUtils.Source b) {
        return a.type() == b.type() && a.id().equals(b.id());
    }

    private static void save(@NonNull SharedPreferences prefs, @NonNull List<SpotifyUtils.Source> sources) {
        if (sources.isEmpty()) {
            prefs.edit().remove(KEY_SPOTIFY_SOURCES).apply();
            return;
        }

        JSONArray array = new JSONArray();
        for (SpotifyUtils.Source source : sources) {
            array.put(source.toAlarmUri().toString());
        }

        prefs.edit().putString(KEY_SPOTIFY_SOURCES, array.toString()).apply();
    }
}
