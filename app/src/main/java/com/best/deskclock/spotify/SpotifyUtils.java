// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.best.deskclock.R;
import com.best.deskclock.utils.LogUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers to use Spotify content (a track, album, playlist, artist, show or episode) as an
 * alarm sound.
 *
 * <p>An alarm that uses Spotify stores a {@code spotify-alarm://} URI as its ringtone, for example
 * {@code spotify-alarm://playlist/37i9dQZF1DX0UrRvztWcAU?title=Wake%20Up%20Happy}. Keeping it
 * hierarchical lets the rest of the app read the title with {@link Uri#getQueryParameter(String)}
 * like any other ringtone, and never confuse it with a playable audio file.</p>
 *
 * <p>No Spotify SDK, account or network permission is needed: the alarm hands the content over to
 * the Spotify app installed on the device.</p>
 */
public final class SpotifyUtils {

    /** The package name of the official Spotify app. */
    public static final String SPOTIFY_PACKAGE = "com.spotify.music";

    /** The scheme of the alarm ringtone URIs pointing to Spotify content. */
    public static final String SCHEME = "spotify-alarm";

    private static final String QUERY_TITLE = "title";

    /** Content types that Spotify can start playing from a link. */
    public enum Type {
        TRACK("track", R.string.spotify_type_track),
        ALBUM("album", R.string.spotify_type_album),
        PLAYLIST("playlist", R.string.spotify_type_playlist),
        ARTIST("artist", R.string.spotify_type_artist),
        SHOW("show", R.string.spotify_type_show),
        EPISODE("episode", R.string.spotify_type_episode);

        public final String key;
        @StringRes
        public final int labelRes;

        Type(String key, @StringRes int labelRes) {
            this.key = key;
            this.labelRes = labelRes;
        }

        @Nullable
        static Type fromKey(@Nullable String key) {
            if (key == null) {
                return null;
            }

            for (Type type : values()) {
                if (type.key.equals(key.toLowerCase(Locale.ROOT))) {
                    return type;
                }
            }

            return null;
        }
    }

    /**
     * A piece of Spotify content, e.g. the playlist with id {@code 37i9dQZF1DX0UrRvztWcAU}.
     *
     * @param title optional name chosen by the user, shown instead of a generic label
     */
    public record Source(@NonNull Type type, @NonNull String id, @Nullable String title) {

        /** @return the Spotify URI, e.g. {@code spotify:playlist:37i9dQZF1DX0UrRvztWcAU} */
        @NonNull
        public String spotifyUri() {
            return "spotify:" + type.key + ":" + id;
        }

        /** @return the public web link, e.g. {@code https://open.spotify.com/playlist/37i9...} */
        @NonNull
        public String webUrl() {
            return "https://open.spotify.com/" + type.key + "/" + id;
        }

        /** @return the URI stored as the alarm ringtone. */
        @NonNull
        public Uri toAlarmUri() {
            Uri.Builder builder = new Uri.Builder()
                .scheme(SCHEME)
                .authority(type.key)
                .appendPath(id);

            if (!TextUtils.isEmpty(title)) {
                builder.appendQueryParameter(QUERY_TITLE, title);
            }

            return builder.build();
        }

        @NonNull
        public Source withTitle(@Nullable String newTitle) {
            String trimmed = newTitle == null ? "" : newTitle.trim();
            return new Source(type, id, trimmed.isEmpty() ? null : trimmed);
        }

        /**
         * @return the user's title, or a generic label such as "Spotify playlist" when there is none.
         */
        @NonNull
        public String displayTitle(@NonNull Context context) {
            if (!TextUtils.isEmpty(title)) {
                return title;
            }

            return context.getString(R.string.spotify_untitled_source, context.getString(type.labelRes));
        }
    }

    // Spotify ids are base62. Locale segments such as "intl-nl" may precede the type in web links.
    private static final Pattern WEB_LINK = Pattern.compile(
        "^(?:https?://)?open\\.spotify\\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(?:embed/)?"
            + "(track|album|playlist|artist|show|episode)/([A-Za-z0-9]{10,40})(?:[/?#].*)?$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SPOTIFY_URI = Pattern.compile(
        "^spotify:(?:user:[^:]+:)?(track|album|playlist|artist|show|episode):([A-Za-z0-9]{10,40})(?::play)?$",
        Pattern.CASE_INSENSITIVE);

    // Finds a Spotify link inside a longer shared text such as "Listen to this on Spotify: https://...".
    private static final Pattern LINK_IN_TEXT = Pattern.compile(
        "(https?://open\\.spotify\\.com/\\S+|spotify:[A-Za-z0-9:]+(?=[\\s.,;!?)\\]]|$))", Pattern.CASE_INSENSITIVE);

    private SpotifyUtils() {
    }

    /**
     * Parses what the user pasted: a Spotify share link ({@code https://open.spotify.com/...}),
     * a Spotify URI ({@code spotify:album:...}) or a text containing one of them.
     *
     * @return the parsed source, or {@code null} if nothing usable was found
     */
    @Nullable
    public static Source parse(@Nullable CharSequence input) {
        if (input == null) {
            return null;
        }

        String text = input.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        Source source = parseSingle(text);
        if (source != null) {
            return source;
        }

        Matcher matcher = LINK_IN_TEXT.matcher(text);
        while (matcher.find()) {
            source = parseSingle(matcher.group(1));
            if (source != null) {
                return source;
            }
        }

        return null;
    }

    @Nullable
    private static Source parseSingle(@NonNull String text) {
        Matcher web = WEB_LINK.matcher(text);
        if (web.matches()) {
            return create(web.group(1), web.group(2));
        }

        Matcher uri = SPOTIFY_URI.matcher(text);
        if (uri.matches()) {
            return create(uri.group(1), uri.group(2));
        }

        return null;
    }

    @Nullable
    private static Source create(@Nullable String typeKey, @Nullable String id) {
        Type type = Type.fromKey(typeKey);
        if (type == null || id == null || id.isEmpty()) {
            return null;
        }

        return new Source(type, id, null);
    }

    /**
     * @return {@code true} if the given alarm ringtone URI points to Spotify content.
     */
    public static boolean isSpotifyAlarmUri(@Nullable Uri uri) {
        return uri != null && SCHEME.equals(uri.getScheme());
    }

    /**
     * Decodes an alarm ringtone URI created by {@link Source#toAlarmUri()}.
     *
     * @return the source, or {@code null} if the URI is not a valid Spotify alarm URI
     */
    @Nullable
    public static Source fromAlarmUri(@Nullable Uri uri) {
        if (!isSpotifyAlarmUri(uri)) {
            return null;
        }

        try {
            String id = uri.getLastPathSegment();
            Source source = create(uri.getAuthority(), id);
            if (source == null) {
                return null;
            }

            return source.withTitle(uri.getQueryParameter(QUERY_TITLE));
        } catch (Exception e) {
            LogUtils.e("Invalid Spotify alarm URI: " + uri, e);
            return null;
        }
    }

    /**
     * @return the title to display for a Spotify alarm URI.
     */
    @NonNull
    public static String getTitle(@NonNull Context context, @NonNull Uri alarmUri) {
        Source source = fromAlarmUri(alarmUri);
        return source == null
            ? context.getString(R.string.spotify_invalid_source)
            : source.displayTitle(context);
    }

    /**
     * @return {@code true} if the Spotify app is installed and enabled.
     */
    public static boolean isSpotifyInstalled(@NonNull Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(SPOTIFY_PACKAGE, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * @return an intent asking Spotify to open the given content and start playing it.
     */
    @NonNull
    public static Intent createPlayIntent(@NonNull Source source) {
        // The ":play" suffix asks Spotify to start playback instead of only showing the content.
        return new Intent(Intent.ACTION_VIEW, Uri.parse(source.spotifyUri() + ":play"))
            .setPackage(SPOTIFY_PACKAGE)
            .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://com.best.deskclock"));
    }

    /**
     * Opens Spotify (to its home screen) so the user can browse and copy a link.
     *
     * @return {@code true} if Spotify could be opened
     */
    public static boolean openSpotify(@NonNull Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);
        if (intent == null) {
            return false;
        }

        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            LogUtils.e("Unable to open Spotify", e);
            return false;
        }
    }

    /**
     * Opens the given content in Spotify without asking for playback.
     */
    public static boolean openInSpotify(@NonNull Context context, @NonNull Source source) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(source.spotifyUri()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (isSpotifyInstalled(context)) {
            intent.setPackage(SPOTIFY_PACKAGE);
        } else {
            intent.setData(Uri.parse(source.webUrl()));
        }

        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            LogUtils.e("Unable to open Spotify content", e);
            return false;
        }
    }
}
