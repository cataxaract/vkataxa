package ru.vtosters.lite.music.spotify;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import ru.vtosters.lite.core.security.KeystorePrefs;

public final class SpotifyAuthStore {
    private static final String PREFS = "vt_spotify_auth";
    private static final String ACCESS = "access_token";
    private static final String REFRESH = "refresh_token";
    private static final String EXPIRES = "expires_at";
    private static final String VERIFIER = "code_verifier";
    private static final String STATE = "oauth_state";
    private static final String NAME = "display_name";

    private static volatile boolean sMigrated;

    private SpotifyAuthStore() {
    }

    private static SharedPreferences prefs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacy(prefs);
        return prefs;
    }

    private static String getString(Context context, String key) {
        return KeystorePrefs.decrypt(prefs(context).getString(key, null));
    }

    private static void putString(Context context, String key, String value) {
        prefs(context).edit().putString(key, KeystorePrefs.encrypt(value)).apply();
    }

    private static void migrateLegacy(SharedPreferences prefs) {
        if (sMigrated) {
            return;
        }
        sMigrated = true;
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof String) {
                String value = (String) entry.getValue();
                if (value != null && !KeystorePrefs.isEncrypted(value)) {
                    if (editor == null) {
                        editor = prefs.edit();
                    }
                    editor.putString(entry.getKey(), KeystorePrefs.encrypt(value));
                }
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    public static boolean isAuthorized(Context context) {
        return getString(context, REFRESH) != null;
    }

    public static String accessToken(Context context) {
        return getString(context, ACCESS);
    }

    public static String refreshToken(Context context) {
        return getString(context, REFRESH);
    }

    public static long expiresAt(Context context) {
        return prefs(context).getLong(EXPIRES, 0L);
    }

    public static String displayName(Context context) {
        return getString(context, NAME);
    }

    public static String codeVerifier(Context context) {
        return getString(context, VERIFIER);
    }

    public static String state(Context context) {
        return getString(context, STATE);
    }

    public static void setCodeVerifier(Context context, String verifier) {
        putString(context, VERIFIER, verifier);
    }

    public static void setState(Context context, String state) {
        putString(context, STATE, state);
    }

    public static void setTokens(Context context, String access, String refresh, long expiresAt, String name) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(ACCESS, KeystorePrefs.encrypt(access))
                .putLong(EXPIRES, expiresAt);

        if (refresh != null) {
            editor.putString(REFRESH, KeystorePrefs.encrypt(refresh));
        }
        if (name != null) {
            editor.putString(NAME, KeystorePrefs.encrypt(name));
        }
        editor.apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}
