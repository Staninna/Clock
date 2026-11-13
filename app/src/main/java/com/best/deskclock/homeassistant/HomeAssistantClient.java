// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * Minimal Home Assistant HTTP client, without any dependency.
 *
 * <p>It uses two Home Assistant APIs:</p>
 * <ul>
 *     <li>The REST API, authenticated with a long-lived access token, to check the connection and
 *     to register this device with the {@code mobile_app} integration (like the official
 *     companion app does).</li>
 *     <li>The {@code mobile_app} webhook returned by the registration, to create and update
 *     sensors and fire events. The webhook needs no token.</li>
 * </ul>
 */
public final class HomeAssistantClient {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    /** The reason a request failed, with a message the user can act on. */
    public enum Failure {
        INVALID_URL,
        HOST_NOT_FOUND,
        CONNECTION_REFUSED,
        TIMEOUT,
        SSL,
        UNAUTHORIZED,
        NOT_HOME_ASSISTANT,
        MOBILE_APP_MISSING,
        /** The webhook no longer exists: the device was removed from Home Assistant. */
        WEBHOOK_GONE,
        SERVER_ERROR,
        NETWORK
    }

    /** Thrown when a request fails. */
    public static final class HomeAssistantException extends Exception {
        public final Failure failure;
        public final int httpCode;

        HomeAssistantException(@NonNull Failure failure, int httpCode, @Nullable String message, @Nullable Throwable cause) {
            super(message == null ? failure.name() : message, cause);
            this.failure = failure;
            this.httpCode = httpCode;
        }
    }

    /** What the server reported about itself. */
    public record ServerInfo(@NonNull String version, @NonNull String locationName, boolean hasMobileApp) {
    }

    /** Marker for an empty response body. */
    private static final JSONObject EMPTY_RESPONSE = new JSONObject();

    private final String mBaseUrl;
    @Nullable
    private final String mToken;

    /**
     * @param baseUrl the server address, e.g. {@code http://homeassistant.local:8123}
     * @param token   a long-lived access token, or {@code null} to only use the webhook
     */
    public HomeAssistantClient(@NonNull String baseUrl, @Nullable String token) {
        mBaseUrl = normalizeUrl(baseUrl);
        mToken = token;
    }

    /**
     * Cleans up an address typed by the user: adds the scheme and the default port when missing,
     * and removes trailing slashes and any path (such as {@code /lovelace/0}).
     *
     * @return the normalized address, or an empty string if it can't be used
     */
    @NonNull
    public static String normalizeUrl(@Nullable String input) {
        if (input == null) {
            return "";
        }

        String url = input.trim();
        if (url.isEmpty()) {
            return "";
        }

        boolean hasScheme = url.matches("(?i)^https?://.*");
        if (!hasScheme) {
            url = "http://" + url;
        }

        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            if (host == null || host.isEmpty() || host.contains(" ")) {
                return "";
            }

            int port = parsed.getPort();
            // Without scheme and port, the user most likely typed "homeassistant.local" or an IP:
            // Home Assistant listens on 8123 by default.
            if (port == -1 && !hasScheme) {
                port = 8123;
            }

            String scheme = parsed.getProtocol().toLowerCase(Locale.ROOT);
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }

            return scheme + "://" + host + (port == -1 ? "" : ":" + port);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * @return {@code true} if the address is unencrypted and doesn't look like a local network
     * address, so the token could be read by others on the way.
     */
    public static boolean isInsecurePublicUrl(@NonNull String normalizedUrl) {
        if (!normalizedUrl.startsWith("http://")) {
            return false;
        }

        String host;
        try {
            host = new URL(normalizedUrl).getHost().replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return false;
        }

        boolean local = host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")
            || host.endsWith(".internal") || host.endsWith(".home.arpa") || !host.contains(".")
            || host.matches("^(10|127)\\..*")
            || host.matches("^192\\.168\\..*")
            || host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")
            || host.matches("^100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\..*") // CGNAT, e.g. Tailscale
            || host.startsWith("fd") || host.startsWith("fe80") || host.equals("::1");

        return !local;
    }

    @NonNull
    public String getBaseUrl() {
        return mBaseUrl;
    }

    /**
     * Checks the address and the token, and whether the mobile_app integration is available.
     */
    @NonNull
    public ServerInfo fetchServerInfo() throws HomeAssistantException {
        JSONObject config = request("GET", "/api/config", null, true);

        boolean hasMobileApp = false;
        if (config.has("components")) {
            String components = config.optJSONArray("components") == null ? "" : config.optJSONArray("components").toString();
            hasMobileApp = components.contains("\"mobile_app\"");
        }

        return new ServerInfo(config.optString("version", "?"), config.optString("location_name", "Home Assistant"), hasMobileApp);
    }

    /**
     * Registers this device with the mobile_app integration.
     *
     * @return the webhook id used for all later updates
     */
    @NonNull
    public String registerDevice(@NonNull JSONObject registration) throws HomeAssistantException {
        JSONObject response;
        try {
            response = request("POST", "/api/mobile_app/registrations", registration, true);
        } catch (HomeAssistantException e) {
            if (e.httpCode == 404) {
                throw new HomeAssistantException(Failure.MOBILE_APP_MISSING, 404, null, e);
            }
            throw e;
        }

        String webhookId = response.optString("webhook_id", "");
        if (webhookId.isEmpty()) {
            throw new HomeAssistantException(Failure.NOT_HOME_ASSISTANT, 200, "No webhook_id in the response", null);
        }

        return webhookId;
    }

    /**
     * Sends a message to the mobile_app webhook, e.g. {@code {"type": "fire_event", "data": {...}}}.
     */
    @NonNull
    public JSONObject sendWebhook(@NonNull String webhookId, @NonNull String type, @NonNull Object data)
        throws HomeAssistantException {

        JSONObject body = new JSONObject();
        try {
            body.put("type", type);
            body.put("data", data);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }

        try {
            JSONObject response = request("POST", "/api/webhook/" + webhookId, body, false);
            // Home Assistant answers 200 with an empty body for a webhook that doesn't exist
            // anymore (the device was deleted), while a live mobile_app webhook always answers JSON.
            if (response == EMPTY_RESPONSE) {
                throw new HomeAssistantException(Failure.WEBHOOK_GONE, 200, "Empty webhook response", null);
            }
            return response;
        } catch (HomeAssistantException e) {
            // Home Assistant answers 410 Gone when the mobile_app config entry was deleted.
            if (e.httpCode == 410 || e.httpCode == 404) {
                throw new HomeAssistantException(Failure.WEBHOOK_GONE, e.httpCode, null, e);
            }
            throw e;
        }
    }

    @NonNull
    private JSONObject request(@NonNull String method, @NonNull String path, @Nullable JSONObject body,
                               boolean authenticated) throws HomeAssistantException {

        if (mBaseUrl.isEmpty()) {
            throw new HomeAssistantException(Failure.INVALID_URL, 0, null, null);
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(mBaseUrl + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");

            if (authenticated && mToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + mToken);
            }

            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            String text = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (code == 401 || code == 403) {
                throw new HomeAssistantException(Failure.UNAUTHORIZED, code, text, null);
            } else if (code >= 300 && code < 400) {
                // Home Assistant never redirects its API: this is another web server (or a login portal).
                throw new HomeAssistantException(Failure.NOT_HOME_ASSISTANT, code, text, null);
            } else if (code >= 500) {
                throw new HomeAssistantException(Failure.SERVER_ERROR, code, text, null);
            } else if (code >= 400) {
                throw new HomeAssistantException(Failure.NOT_HOME_ASSISTANT, code, text, null);
            }

            return parseJsonObject(text);
        } catch (HomeAssistantException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new HomeAssistantException(Failure.HOST_NOT_FOUND, 0, e.getMessage(), e);
        } catch (ConnectException e) {
            throw new HomeAssistantException(Failure.CONNECTION_REFUSED, 0, e.getMessage(), e);
        } catch (SocketTimeoutException e) {
            throw new HomeAssistantException(Failure.TIMEOUT, 0, e.getMessage(), e);
        } catch (SSLException e) {
            throw new HomeAssistantException(Failure.SSL, 0, e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new HomeAssistantException(Failure.NETWORK, 0, e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @VisibleForTesting
    @NonNull
    static JSONObject parseJsonObject(@Nullable String text) throws HomeAssistantException {
        if (text == null || text.trim().isEmpty()) {
            return EMPTY_RESPONSE;
        }

        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new HomeAssistantException(Failure.NOT_HOME_ASSISTANT, 200, "Response is not JSON", e);
        }
    }

    @NonNull
    private static String readBody(@Nullable InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            // Never read huge bodies: Home Assistant answers are small.
            while ((read = in.read(buffer)) != -1 && out.size() < 512 * 1024) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
