package ru.vtosters.lite.music.spotify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.vtosters.lite.di.singleton.VtOkHttpClient;

public final class SpotifyApi {
    private static final OkHttpClient CLIENT = VtOkHttpClient.getInstance();

    private SpotifyApi() {
    }

    public static List<SpotifyPlaylist> getPlaylists(Context context) throws Exception {
        List<SpotifyPlaylist> result = new ArrayList<>();
        String next = SpotifyConfig.API_URL + "/me/playlists?limit=50&offset=0";

        while (next != null) {
            JSONObject page = new JSONObject(authorizedGet(context, next));
            JSONArray items = page.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    String id = item.optString("id", "");
                    String name = item.optString("name", "");
                    if (id.isEmpty() || name.isEmpty()) {
                        continue;
                    }

                    JSONObject owner = item.optJSONObject("owner");
                    String ownerName = owner == null ? "" : owner.optString("display_name", "");
                    JSONObject tracks = item.optJSONObject("tracks");
                    int count = tracks == null ? 0 : tracks.optInt("total", 0);
                    String image = firstImage(item.optJSONArray("images"));

                    result.add(new SpotifyPlaylist(
                            id,
                            name,
                            ownerName,
                            image,
                            count,
                            item.optString("snapshot_id", "")
                    ));
                }
            }
            next = page.optString("next", null);
            if (next != null && (next.isEmpty() || "null".equals(next))) {
                next = null;
            }
        }

        return result;
    }

    public static List<SpotifyTrack> getPlaylistTracks(Context context, String playlistId) throws Exception {
        List<SpotifyTrack> result = new ArrayList<>();
        String next = SpotifyConfig.API_URL + "/playlists/" + playlistId
                + "/tracks?limit=100&offset=0&market=from_token"
                + "&fields=items(track(id,name,duration_ms,artists(name),is_local,type)),next,total";

        while (next != null) {
            JSONObject page = new JSONObject(authorizedGet(context, next));
            JSONArray items = page.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject wrapper = items.optJSONObject(i);
                    JSONObject item = wrapper == null ? null : wrapper.optJSONObject("track");
                    if (item == null) {
                        continue;
                    }

                    String type = item.optString("type", "track");
                    boolean local = item.optBoolean("is_local", false);
                    String id = item.optString("id", "");
                    String title = item.optString("name", "");
                    int duration = item.optInt("duration_ms", 0);
                    String artist = artists(item.optJSONArray("artists"));

                    result.add(new SpotifyTrack(id, title, artist, duration, local, type));
                }
            }
            next = page.optString("next", null);
            if (next != null && (next.isEmpty() || "null".equals(next))) {
                next = null;
            }
        }

        return result;
    }

    public static String getMeName(Context context) {
        try {
            JSONObject me = new JSONObject(authorizedGet(context, SpotifyConfig.API_URL + "/me"));
            String name = me.optString("display_name", "");
            if (name.isEmpty()) {
                name = me.optString("id", "");
            }
            if (!name.isEmpty()) {
                SpotifyAuthStore.setTokens(
                        context,
                        SpotifyAuthStore.accessToken(context),
                        null,
                        SpotifyAuthStore.expiresAt(context),
                        name
                );
            }
            return name;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstImage(JSONArray images) {
        if (images == null || images.length() == 0) {
            return null;
        }
        JSONObject image = images.optJSONObject(0);
        return image == null ? null : image.optString("url", null);
    }

    private static String artists(JSONArray artists) {
        if (artists == null || artists.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            String name = artist.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(name);
        }
        return builder.toString();
    }

    private static String authorizedGet(Context context, String url) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = SpotifyAuth.accessToken(context);
            Request request = new Request.a()
                    .b(url)
                    .a("Authorization", "Bearer " + token)
                    .a("Accept", "application/json")
                    .a();

            Response response = CLIENT.a(request).execute();
            int code = response.d();

            if (code == 401 && attempt == 0) {
                response.close();
                SpotifyAuth.refresh(context);
                continue;
            }

            if (code == 429) {
                int wait = parseRetryAfter(response.a("Retry-After"));
                response.close();
                Thread.sleep(Math.min(wait, 30) * 1000L);
                continue;
            }

            if (code >= 500 && attempt < 2) {
                response.close();
                Thread.sleep(1200L);
                continue;
            }

            String raw = response.a() == null ? "" : response.a().g();
            response.close();

            if (code < 200 || code >= 300) {
                throw new IOException("Spotify HTTP " + code + ": " + raw);
            }
            return raw;
        }

        throw new IOException("Spotify request failed");
    }

    private static int parseRetryAfter(String value) {
        if (value == null) {
            return 2;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }
}
