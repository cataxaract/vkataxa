package ru.vtosters.lite.music.spotify;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.vtosters.lite.di.singleton.VtOkHttpClient;

public final class SpotifyAuth {
    private static final OkHttpClient CLIENT = VtOkHttpClient.getInstance();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SpotifyAuth() {
    }

    public static String createAuthUrl(Context context) throws Exception {
        byte[] verifierBytes = new byte[64];
        RANDOM.nextBytes(verifierBytes);
        String verifier = Base64.encodeToString(
                verifierBytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );

        byte[] stateBytes = new byte[24];
        RANDOM.nextBytes(stateBytes);
        String state = Base64.encodeToString(
                stateBytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );

        SpotifyAuthStore.setCodeVerifier(context, verifier);
        SpotifyAuthStore.setState(context, state);

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

        return new Uri.Builder()
                .scheme("https")
                .authority("accounts.spotify.com")
                .appendPath("authorize")
                .appendQueryParameter("client_id", SpotifyConfig.CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", SpotifyConfig.REDIRECT_URI)
                .appendQueryParameter("scope", SpotifyConfig.SCOPES)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("state", state)
                .build()
                .toString();
    }

    public static void exchangeCode(Context context, String code, String state) throws Exception {
        String expectedState = SpotifyAuthStore.state(context);
        if (expectedState == null || !expectedState.equals(state)) {
            throw new SecurityException("Spotify state mismatch");
        }

        String verifier = SpotifyAuthStore.codeVerifier(context);
        if (verifier == null) {
            throw new IllegalStateException("Spotify code verifier is missing");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", SpotifyConfig.REDIRECT_URI);
        form.put("client_id", SpotifyConfig.CLIENT_ID);
        form.put("code_verifier", verifier);

        JSONObject response = tokenRequest(form);
        saveTokens(context, response);
    }

    public static void refresh(Context context) throws Exception {
        String refreshToken = SpotifyAuthStore.refreshToken(context);
        if (refreshToken == null) {
            throw new IllegalStateException("Spotify refresh token is missing");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", SpotifyConfig.CLIENT_ID);

        JSONObject response = tokenRequest(form);
        saveTokens(context, response);
    }

    public static String accessToken(Context context) throws Exception {
        String token = SpotifyAuthStore.accessToken(context);
        long expiresAt = SpotifyAuthStore.expiresAt(context);
        if (token == null || expiresAt <= System.currentTimeMillis() + 60_000L) {
            refresh(context);
            token = SpotifyAuthStore.accessToken(context);
        }
        if (token == null) {
            throw new IllegalStateException("Spotify is not authorized");
        }
        return token;
    }

    private static JSONObject tokenRequest(Map<String, String> form) throws Exception {
        FormBody.a body = new FormBody.a(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : form.entrySet()) {
            body.a(entry.getKey(), entry.getValue());
        }

        Request request = new Request.a()
                .b(SpotifyConfig.TOKEN_URL)
                .a("Accept", "application/json")
                .a(body.a())
                .a();

        Response response = CLIENT.a(request).execute();
        int code = response.d();
        String raw = response.a() == null ? "" : response.a().g();
        response.close();

        if (code < 200 || code >= 300) {
            throw new IOException("Spotify token HTTP " + code + ": " + raw);
        }
        return new JSONObject(raw);
    }

    private static void saveTokens(Context context, JSONObject response) {        String access = response.optString("access_token", null);
        String refresh = response.optString("refresh_token", null);
        long expiresIn = response.optLong("expires_in", 3600L);
        long expiresAt = System.currentTimeMillis() + expiresIn * 1000L - 60_000L;

        SpotifyAuthStore.setTokens(context, access, refresh, expiresAt, null);
    }
}
