// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.best.deskclock.utils.LogUtils;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Home Assistant servers on the local network. Home Assistant announces itself with mDNS
 * (zeroconf) as {@code _home-assistant._tcp}, including its name and address.
 */
public final class HomeAssistantDiscovery {

    private static final String SERVICE_TYPE = "_home-assistant._tcp.";
    private static final long DISCOVERY_DURATION_MS = 5000;

    /** A server found on the network. */
    public record Server(@NonNull String name, @NonNull String url) {
    }

    /** Called once, on the main thread, when the discovery is over. */
    public interface Callback {
        void onFinished(@NonNull List<Server> servers);
    }

    private final NsdManager mNsdManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Server> mServers = new LinkedHashMap<>();
    private final Deque<NsdServiceInfo> mToResolve = new ArrayDeque<>();
    private boolean mResolving;
    private boolean mFinished;
    private Callback mCallback;
    private NsdManager.DiscoveryListener mDiscoveryListener;

    public HomeAssistantDiscovery(@NonNull Context context) {
        mNsdManager = context.getApplicationContext().getSystemService(NsdManager.class);
    }

    public void start(@NonNull Callback callback) {
        mCallback = callback;

        if (mNsdManager == null) {
            finish();
            return;
        }

        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                LogUtils.w("HomeAssistantDiscovery: start failed " + errorCode);
                mDiscoveryListener = null;
                mHandler.post(HomeAssistantDiscovery.this::finish);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                mHandler.post(() -> {
                    mToResolve.add(serviceInfo);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }
        };

        try {
            mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
        } catch (Exception e) {
            LogUtils.w("HomeAssistantDiscovery: unable to discover: " + e.getMessage());
            mDiscoveryListener = null;
            finish();
            return;
        }

        mHandler.postDelayed(this::finish, DISCOVERY_DURATION_MS);
    }

    /**
     * Stops the discovery without calling the callback.
     */
    public void stop() {
        mCallback = null;
        finish();
    }

    // Only one resolution can run at a time on older Android versions.
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (mFinished || mResolving || mToResolve.isEmpty()) {
            return;
        }

        mResolving = true;
        NsdServiceInfo info = mToResolve.poll();
        try {
            mNsdManager.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    mHandler.post(() -> {
                        mResolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    mHandler.post(() -> {
                        mResolving = false;
                        Server server = toServer(serviceInfo);
                        if (server != null && !mServers.containsKey(server.url())) {
                            mServers.put(server.url(), server);
                        }
                        resolveNext();
                    });
                }
            });
        } catch (Exception e) {
            mResolving = false;
            resolveNext();
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static Server toServer(@NonNull NsdServiceInfo info) {
        Map<String, byte[]> attributes = info.getAttributes();
        String name = attribute(attributes, "location_name");
        if (name == null || name.isEmpty()) {
            name = info.getServiceName();
        }

        // Prefer the address Home Assistant itself advertises for the local network.
        String url = attribute(attributes, "internal_url");
        if (url == null || url.isEmpty()) {
            url = attribute(attributes, "base_url");
        }

        if (url == null || url.isEmpty()) {
            InetAddress host = info.getHost();
            if (host == null) {
                return null;
            }

            String address = host.getHostAddress();
            if (host instanceof Inet6Address) {
                address = "[" + address + "]";
            }
            url = "http://" + address + ":" + info.getPort();
        }

        url = HomeAssistantClient.normalizeUrl(url);
        return url.isEmpty() ? null : new Server(name == null ? "Home Assistant" : name, url);
    }

    @Nullable
    private static String attribute(@Nullable Map<String, byte[]> attributes, @NonNull String key) {
        if (attributes == null) {
            return null;
        }

        byte[] value = attributes.get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private void finish() {
        if (mFinished) {
            return;
        }

        mFinished = true;
        mHandler.removeCallbacksAndMessages(null);

        if (mDiscoveryListener != null) {
            try {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } catch (Exception ignored) {
                // Already stopped
            }
            mDiscoveryListener = null;
        }

        Callback callback = mCallback;
        mCallback = null;
        if (callback != null) {
            callback.onFinished(new ArrayList<>(mServers.values()));
        }
    }
}
