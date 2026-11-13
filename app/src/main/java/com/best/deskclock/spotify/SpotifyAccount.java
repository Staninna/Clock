// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.best.deskclock.BuildConfig;
import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.utils.LogUtils;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The user's Spotify account: logging in with OAuth 2.0 (authorization code with PKCE, so no
 * secret is stored in the app), and keeping the access token fresh.
 *
 * <p>Logging in needs the Client ID of a Spotify app registered on
 * https://developer.spotify.com/dashboard, with {@link #getRedirectUri()} as redirect URI. It is
 * built into the app ({@code -PspotifyClientId=...}) or entered by the user.</p>
 *
 * <p>Logging in is optional: Spotify alarms also work with pasted links. With an account, the
 * user can browse their playlists and search Spotify, and the alarm can ask Spotify (Premium) to
 * play on this phone through the Web API, which is more reliable.</p>
 */
public final class SpotifyAccount {

    /** Read playlists, and control the playback for alarms. */
    static final String SCOPES = "playlist-read-private playlist-read-collaborative "
        + "user-read-private user-read-playback-state user-modify-playback-state";

    public static final String KEY_CLIENT_ID = "key_spotify_client_id";
    public static final String KEY_ACCESS_TOKEN = "key_spotify_access_token";
    public static final String KEY_REFRESH_TOKEN = "key_spotify_refresh_token";
    public static final String KEY_EXPIRES_AT = "key_spotify_expires_at";
    public static final String KEY_USER_NAME = "key_spotify_user_name";
    public static final String KEY_USER_ID = "key_spotify_user_id";
    public static final String KEY_PREMIUM = "key_spotify_premium";
    private static final String KEY_PKCE_VERIFIER = "key_spotify_pkce_verifier";
    private static final String KEY_PKCE_STATE = "key_spotify_pkce_state";

    /** Keys that must never leave the device in a settings backup. */
    public static final String[] PRIVATE_KEYS = {
        KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT, KEY_USER_NAME, KEY_USER_ID, KEY_PREMIUM,
        KEY_PKCE_VERIFIER, KEY_PKCE_STATE
    };

    private static final long EXPIRY_MARGIN_MS = 60_000;

    /** Login failure: the Spotify account may not use this Spotify app (development mode). */
    static final String REASON_USER_NOT_ALLOWED = "user_not_allowed";

    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> new Thread(r, "SpotifyAccount"));

    /** Result of the login, on the main thread. */
    public interface LoginCallback {
        void onLoggedIn(@NonNull SpotifyWebApi.Profile profile);

        void onFailed(@NonNull String reason);
    }

    private SpotifyAccount() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return DeskClockApplication.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static boolean isPrivateKey(@NonNull String key) {
        for (String privateKey : PRIVATE_KEYS) {
            if (privateKey.equals(key)) {
                return true;
            }
        }
        for (String privateKey : SpotifyAlarmPlayer.PRIVATE_KEYS) {
            if (privateKey.equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Client ID

    /**
     * @return the Client ID entered by the user, or the one built into the app, or {@code ""}.
     */
    @NonNull
    public static String getClientId(@NonNull Context context) {
        String userClientId = prefs(context).getString(KEY_CLIENT_ID, "");
        return TextUtils.isEmpty(userClientId) ? BuildConfig.SPOTIFY_CLIENT_ID : userClientId;
    }

    public static boolean hasClientId(@NonNull Context context) {
        return !getClientId(context).isEmpty();
    }

    /** @return {@code true} if the app was built with a Client ID, so the user never sees it. */
    public static boolean isClientIdBuiltIn() {
        return !BuildConfig.SPOTIFY_CLIENT_ID.isEmpty();
    }

    /**
     * @return {@code true} if the text looks like a Spotify Client ID (32 hexadecimal characters).
     */
    public static boolean isValidClientId(@Nullable String clientId) {
        return clientId != null && clientId.trim().matches("[0-9a-fA-F]{32}");
    }

    public static void setClientId(@NonNull Context context, @Nullable String clientId) {
        prefs(context).edit().putString(KEY_CLIENT_ID, clientId == null ? "" : clientId.trim()).apply();
    }

    /**
     * @return the redirect URI to register in the Spotify app, e.g.
     * {@code com.best.deskclock://spotify-callback}.
     */
    @NonNull
    public static String getRedirectUri() {
        return BuildConfig.APPLICATION_ID + "://spotify-callback";
    }

    // ---------------------------------------------------------------------------------------------
    // Login

    public static boolean isLoggedIn(@NonNull Context context) {
        return !TextUtils.isEmpty(prefs(context).getString(KEY_REFRESH_TOKEN, null));
    }

    @NonNull
    public static String getUserName(@NonNull Context context) {
        return prefs(context).getString(KEY_USER_NAME, "");
    }

    public static boolean isPremium(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PREMIUM, false);
    }

    /**
     * Opens the Spotify login page in the browser. Spotify then opens {@link SpotifyAuthActivity}.
     *
     * @return {@code false} if no browser could be opened
     */
    public static boolean startLogin(@NonNull Context context) {
        String verifier = randomString(64);
        String state = randomString(16);

        prefs(context).edit()
            .putString(KEY_PKCE_VERIFIER, verifier)
            .putString(KEY_PKCE_STATE, state)
            .apply();

        Uri uri = SpotifyWebApi.buildAuthorizeUri(getClientId(context), getRedirectUri(),
            codeChallenge(verifier), state, SCOPES);

        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            LogUtils.e("SpotifyAccount: no browser to log in", e);
            return false;
        }
    }

    /**
     * Completes the login with the redirect from Spotify: checks the state, exchanges the code for
     * tokens and loads the profile. The callback runs on the main thread.
     */
    static void completeLogin(@NonNull Context context, @NonNull Uri redirect, @NonNull LoginCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final SharedPreferences prefs = prefs(appContext);

        String error = redirect.getQueryParameter("error");
        String code = redirect.getQueryParameter("code");
        String state = redirect.getQueryParameter("state");
        String expectedState = prefs.getString(KEY_PKCE_STATE, null);
        String verifier = prefs.getString(KEY_PKCE_VERIFIER, null);

        if (!TextUtils.isEmpty(error)) {
            callback.onFailed(error);
            return;
        }

        if (TextUtils.isEmpty(code) || verifier == null || expectedState == null || !expectedState.equals(state)) {
            callback.onFailed("invalid_state");
            return;
        }

        EXECUTOR.execute(() -> {
            // Which step failed, to tell the user what went wrong.
            String step = "token";
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "authorization_code");
                form.put("code", code);
                form.put("redirect_uri", getRedirectUri());
                form.put("client_id", getClientId(appContext));
                form.put("code_verifier", verifier);

                JSONObject tokens = SpotifyWebApi.requestToken(form);
                // Only log in once the profile (and its Premium status) is known: a half login
                // would never use the Web API.
                step = "profile";
                SpotifyWebApi.Profile profile = SpotifyWebApi.getProfile(tokens.getString("access_token"));
                saveTokens(prefs, tokens);
                prefs.edit()
                    .putString(KEY_USER_NAME, profile.displayName())
                    .putString(KEY_USER_ID, profile.id())
                    .putBoolean(KEY_PREMIUM, profile.isPremium())
                    .remove(KEY_PKCE_VERIFIER)
                    .remove(KEY_PKCE_STATE)
                    .apply();

                LogUtils.i("SpotifyAccount: logged in" + (profile.isPremium() ? " (Premium)" : ""));
                main.post(() -> callback.onLoggedIn(profile));
            } catch (Exception e) {
                LogUtils.w("SpotifyAccount: login failed: " + e.getMessage());
                prefs.edit().remove(KEY_PKCE_VERIFIER).remove(KEY_PKCE_STATE).apply();
                final String reason;
                if (e instanceof SpotifyWebApi.SpotifyException se) {
                    if (se.isNetworkError()) {
                        reason = "network";
                    } else if ("profile".equals(step) && se.httpCode == 403) {
                        // Development mode: the account isn't in the app's User Management.
                        reason = REASON_USER_NOT_ALLOWED;
                    } else {
                        reason = step + ": " + (se.reason == null ? "" : se.reason + " ") + "(HTTP " + se.httpCode + ")";
                    }
                } else {
                    reason = step + ": " + e.getClass().getSimpleName();
                }
                main.post(() -> callback.onFailed(reason));
            }
        });
    }

    public static void logout(@NonNull Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String key : PRIVATE_KEYS) {
            editor.remove(key);
        }
        editor.apply();
    }

    // ---------------------------------------------------------------------------------------------
    // Tokens

    /**
     * @return a valid access token, refreshed if needed, or {@code null} if not logged in.
     * Must be called off the main thread.
     */
    @Nullable
    public static synchronized String getAccessToken(@NonNull Context context) throws SpotifyWebApi.SpotifyException {
        SharedPreferences prefs = prefs(context);
        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        if (TextUtils.isEmpty(refreshToken)) {
            return null;
        }

        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
        if (!TextUtils.isEmpty(accessToken) && System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS) {
            return accessToken;
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", getClientId(context));

        try {
            JSONObject tokens = SpotifyWebApi.requestToken(form);
            saveTokens(prefs, tokens);
            return tokens.optString("access_token", null);
        } catch (SpotifyWebApi.SpotifyException e) {
            if ("invalid_grant".equals(e.reason)) {
                // The user revoked the access, or the token is too old: log out.
                LogUtils.w("SpotifyAccount: refresh token refused, logging out");
                logout(context);
            }
            throw e;
        }
    }

    private static void saveTokens(@NonNull SharedPreferences prefs, @NonNull JSONObject tokens) {
        SharedPreferences.Editor editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.optString("access_token"))
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000);

        // With PKCE, Spotify may rotate the refresh token: keep the newest one.
        String refreshToken = tokens.optString("refresh_token", "");
        if (!refreshToken.isEmpty()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        editor.commit();
    }

    // ---------------------------------------------------------------------------------------------
    // PKCE

    @NonNull
    @VisibleForTesting
    static String randomString(int length) {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(random.nextInt(chars.length())));
        }
        return builder.toString();
    }

    @NonNull
    @VisibleForTesting
    static String codeChallenge(@NonNull String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
