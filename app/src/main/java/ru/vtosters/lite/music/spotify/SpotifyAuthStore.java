package ru.vtosters.lite.music.spotify;

import android.content.Context;
import android.content.SharedPreferences;

public final class SpotifyAuthStore {
    private static final String PREFS = "vt_spotify_auth";
    private static final String ACCESS = "access_token";
    private static final String REFRESH = "refresh_token";
    private static final String EXPIRES = "expires_at";
    private static final String VERIFIER = "code_verifier";
    private static final String STATE = "oauth_state";
    private static final String NAME = "display_name";

    private SpotifyAuthStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAuthorized(Context context) {
        return prefs(context).getString(REFRESH, null) != null;
    }

    public static String accessToken(Context context) {
        return prefs(context).getString(ACCESS, null);
    }

    public static String refreshToken(Context context) {
        return prefs(context).getString(REFRESH, null);
    }

    public static long expiresAt(Context context) {
        return prefs(context).getLong(EXPIRES, 0L);
    }

    public static String displayName(Context context) {
        return prefs(context).getString(NAME, null);
    }

    public static String codeVerifier(Context context) {
        return prefs(context).getString(VERIFIER, null);
    }

    public static String state(Context context) {
        return prefs(context).getString(STATE, null);
    }

    public static void setCodeVerifier(Context context, String verifier) {
        prefs(context).edit().putString(VERIFIER, verifier).apply();
    }

    public static void setState(Context context, String state) {
        prefs(context).edit().putString(STATE, state).apply();
    }

    public static void setTokens(Context context, String access, String refresh, long expiresAt, String name) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(ACCESS, access)
                .putLong(EXPIRES, expiresAt);

        if (refresh != null) {
            editor.putString(REFRESH, refresh);
        }
        if (name != null) {
            editor.putString(NAME, name);
        }
        editor.apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}
