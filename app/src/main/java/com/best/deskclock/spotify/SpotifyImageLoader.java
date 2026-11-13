// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the small cover images of the Spotify browser, with a memory cache.
 */
final class SpotifyImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SpotifyImageLoader() {
    }

    /**
     * Shows the image at {@code url} in the view, which may be recycled meanwhile.
     */
    static void load(@NonNull ImageView view, @Nullable String url) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null) {
            return;
        }

        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        final int size = Math.max(64, view.getLayoutParams() == null ? 144 : view.getLayoutParams().width);
        EXECUTOR.execute(() -> {
            Bitmap bitmap = download(url, size);
            if (bitmap == null) {
                return;
            }

            CACHE.put(url, bitmap);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    @Nullable
    private static Bitmap download(@NonNull String url, int size) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            try (InputStream in = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) {
                    return null;
                }
                if (bitmap.getWidth() > size * 2) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size, size * bitmap.getHeight() / bitmap.getWidth(), true);
                    bitmap.recycle();
                    return scaled;
                }
                return bitmap;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
