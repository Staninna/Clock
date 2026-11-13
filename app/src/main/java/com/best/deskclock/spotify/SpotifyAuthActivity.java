// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.best.deskclock.R;

/**
 * Receives the redirect from the Spotify login page ({@code <applicationId>://spotify-callback}),
 * completes the login and goes back to where the user was.
 */
public class SpotifyAuthActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(@Nullable Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            finish();
            return;
        }

        SpotifyAccount.completeLogin(this, data, new SpotifyAccount.LoginCallback() {
            @Override
            public void onLoggedIn(@NonNull SpotifyWebApi.Profile profile) {
                Toast.makeText(getApplicationContext(),
                    getString(R.string.spotify_logged_in_toast, profile.displayName()), Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onFailed(@NonNull String reason) {
                final String message;
                if ("access_denied".equals(reason)) {
                    message = getString(R.string.spotify_login_cancelled);
                } else if (SpotifyAccount.REASON_USER_NOT_ALLOWED.equals(reason)) {
                    message = getString(R.string.spotify_login_user_not_allowed);
                } else if ("network".equals(reason)) {
                    message = getString(R.string.spotify_login_network);
                } else {
                    message = getString(R.string.spotify_login_failed_reason, reason);
                }
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}
