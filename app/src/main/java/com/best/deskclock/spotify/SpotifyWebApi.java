// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Spotify Web API client (https://developer.spotify.com/documentation/web-api), without
 * any dependency. All methods do network I/O and must be called off the main thread.
 */
public final class SpotifyWebApi {

    private static final String sAccountsBaseUrl = "https://accounts.spotify.com";
    private static final String sApiBaseUrl = "https://api.spotify.com";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    /** Thrown when a request fails. */
    public static final class SpotifyException extends Exception {
        public final int httpCode;
        @Nullable
        public final String reason;

        SpotifyException(int httpCode, @Nullable String reason, @Nullable String message, @Nullable Throwable cause) {
            super(message == null ? "HTTP " + httpCode : message, cause);
            this.httpCode = httpCode;
            this.reason = reason;
        }

        /** @return {@code true} if the request failed because the network is unreachable. */
        public boolean isNetworkError() {
            return httpCode == 0;
        }
    }

    /** An item that can be played: a playlist, album, track, artist, show or episode. */
    public record Item(@NonNull SpotifyUtils.Source source, @NonNull String name, @NonNull String subtitle,
                       @Nullable String imageUrl) {
    }

    /** A Spotify Connect device, e.g. this phone. */
    public record Device(@NonNull String id, @NonNull String name, @NonNull String type, boolean isActive) {
    }

    /** The user's profile. */
    public record Profile(@NonNull String id, @NonNull String displayName, boolean isPremium) {
    }

    private SpotifyWebApi() {
    }

    // ---------------------------------------------------------------------------------------------
    // Accounts

    @NonNull
    public static Uri buildAuthorizeUri(@NonNull String clientId, @NonNull String redirectUri,
                                        @NonNull String codeChallenge, @NonNull String state, @NonNull String scopes) {
        return Uri.parse(sAccountsBaseUrl + "/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", scopes)
            .build();
    }

    /**
     * Exchanges an authorization code (PKCE) or a refresh token for tokens.
     *
     * @return the token response: {@code access_token}, {@code expires_in}, and usually
     * {@code refresh_token}
     */
    @NonNull
    public static JSONObject requestToken(@NonNull Map<String, String> form) throws SpotifyException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(Uri.encode(entry.getKey())).append('=').append(Uri.encode(entry.getValue()));
        }

        return request("POST", sAccountsBaseUrl + "/api/token", null,
            body.toString().getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
    }

    // ---------------------------------------------------------------------------------------------
    // Web API

    @NonNull
    public static Profile getProfile(@NonNull String token) throws SpotifyException {
        JSONObject me = get(token, "/v1/me");
        String name = me.optString("display_name", "");
        return new Profile(me.optString("id", ""), name.isEmpty() ? me.optString("id", "Spotify") : name,
            "premium".equals(me.optString("product")));
    }

    /**
     * @return the playlists the user created or follows, up to {@code max}.
     */
    @NonNull
    public static List<Item> getMyPlaylists(@NonNull String token, int max) throws SpotifyException {
        List<Item> items = new ArrayList<>();
        String next = "/v1/me/playlists?limit=50";
        while (next != null && items.size() < max) {
            JSONObject page = get(token, next);
            JSONArray array = page.optJSONArray("items");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    Item item = parseItem(array.optJSONObject(i), "playlist");
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            next = page.isNull("next") ? null : relative(page.optString("next", null));
        }
        return items;
    }

    /**
     * Searches tracks, albums, playlists and artists.
     */
    @NonNull
    public static List<Item> search(@NonNull String token, @NonNull String query) throws SpotifyException {
        JSONObject result = get(token, "/v1/search?type=playlist,album,track,artist&limit=8&q=" + Uri.encode(query));

        List<Item> items = new ArrayList<>();
        for (String type : new String[]{"playlist", "album", "track", "artist"}) {
            JSONObject group = result.optJSONObject(type + "s");
            JSONArray array = group == null ? null : group.optJSONArray("items");
            if (array == null) {
                continue;
            }
            for (int i = 0; i < array.length(); i++) {
                Item item = parseItem(array.optJSONObject(i), type);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    @NonNull
    public static List<Device> getDevices(@NonNull String token) throws SpotifyException {
        JSONObject result = get(token, "/v1/me/player/devices");
        List<Device> devices = new ArrayList<>();
        JSONArray array = result.optJSONArray("devices");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject device = array.optJSONObject(i);
                if (device != null && !device.isNull("id")) {
                    devices.add(new Device(device.optString("id"), device.optString("name"),
                        device.optString("type"), device.optBoolean("is_active")));
                }
            }
        }
        return devices;
    }

    /**
     * Starts playing the source on the given device (Spotify Premium only).
     */
    public static void play(@NonNull String token, @NonNull String deviceId, @NonNull SpotifyUtils.Source source)
        throws SpotifyException {

        JSONObject body = new JSONObject();
        try {
            if (source.type() == SpotifyUtils.Type.TRACK || source.type() == SpotifyUtils.Type.EPISODE) {
                body.put("uris", new JSONArray().put(source.spotifyUri()));
            } else {
                body.put("context_uri", source.spotifyUri());
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }

        request("PUT", sApiBaseUrl + "/v1/me/player/play?device_id=" + Uri.encode(deviceId), token,
            body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /**
     * @return {@code true} if Spotify is currently playing the given source on any device.
     */
    public static boolean isPlaying(@NonNull String token, @NonNull SpotifyUtils.Source source) throws SpotifyException {
        JSONObject state = get(token, "/v1/me/player");
        if (!state.optBoolean("is_playing")) {
            return false;
        }

        JSONObject context = state.optJSONObject("context");
        JSONObject item = state.optJSONObject("item");
        String uri = source.spotifyUri();
        return (context != null && uri.equals(context.optString("uri")))
            || (item != null && uri.equals(item.optString("uri")));
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing

    @Nullable
    @VisibleForTesting
    static Item parseItem(@Nullable JSONObject json, @NonNull String type) {
        if (json == null) {
            return null;
        }

        SpotifyUtils.Source source = SpotifyUtils.parse(json.optString("uri", ""));
        String name = json.optString("name", "");
        if (source == null || name.isEmpty()) {
            return null;
        }

        String subtitle;
        switch (type) {
            case "playlist" -> {
                JSONObject owner = json.optJSONObject("owner");
                subtitle = owner == null ? "" : owner.optString("display_name", "");
            }
            case "album" -> subtitle = artists(json);
            case "track" -> subtitle = artists(json);
            default -> subtitle = "";
        }

        JSONArray images = json.optJSONArray("images");
        if (images == null && json.optJSONObject("album") != null) {
            images = json.optJSONObject("album").optJSONArray("images");
        }

        return new Item(source.withTitle(name), name, subtitle, smallestImage(images));
    }

    @NonNull
    private static String artists(@NonNull JSONObject json) {
        JSONArray artists = json.optJSONArray("artists");
        if (artists == null) {
            return "";
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist != null) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(artist.optString("name"));
            }
        }
        return names.toString();
    }

    /**
     * @return the smallest image that is still at least 64 px wide, for list thumbnails.
     */
    @Nullable
    private static String smallestImage(@Nullable JSONArray images) {
        if (images == null) {
            return null;
        }

        String best = null;
        int bestWidth = Integer.MAX_VALUE;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) {
                continue;
            }
            int width = image.optInt("width", 300);
            if (best == null || (width >= 64 && width < bestWidth)) {
                best = image.optString("url", null);
                bestWidth = width;
            }
        }
        return best;
    }

    @Nullable
    private static String relative(@Nullable String url) {
        if (url == null || !url.startsWith(sApiBaseUrl)) {
            return null;
        }
        return url.substring(sApiBaseUrl.length());
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP

    @NonNull
    private static JSONObject get(@NonNull String token, @NonNull String path) throws SpotifyException {
        return request("GET", sApiBaseUrl + path, token, null, null);
    }

    @NonNull
    private static JSONObject request(@NonNull String method, @NonNull String url, @Nullable String token,
                                      @Nullable byte[] body, @Nullable String contentType) throws SpotifyException {

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }

            int code = connection.getResponseCode();
            String text = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (code >= 400) {
                throw new SpotifyException(code, errorReason(text), text, null);
            }

            if (text.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(text);
        } catch (SpotifyException e) {
            throw e;
        } catch (JSONException e) {
            throw new SpotifyException(200, null, "Invalid JSON", e);
        } catch (IOException | RuntimeException e) {
            throw new SpotifyException(0, null, e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * @return the reason of an error answer, e.g. {@code PREMIUM_REQUIRED},
     * {@code NO_ACTIVE_DEVICE} or {@code invalid_grant}.
     */
    @Nullable
    private static String errorReason(@NonNull String text) {
        try {
            JSONObject json = new JSONObject(text);
            Object error = json.opt("error");
            if (error instanceof JSONObject errorObject) {
                String reason = errorObject.optString("reason", "");
                return reason.isEmpty() ? errorObject.optString("message", null) : reason;
            } else if (error instanceof String errorString) {
                return errorString;
            }
        } catch (JSONException ignored) {
            // Not JSON
        }
        return null;
    }

    @NonNull
    private static String readBody(@Nullable InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < 4 * 1024 * 1024) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
