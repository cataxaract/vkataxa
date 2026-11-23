package ru.vtosters.lite.music.wave;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.vtosters.lite.di.singleton.VtOkHttpClient;
import ru.vtosters.lite.utils.AndroidUtils;

final class LastFmClient {
    private static final String KEY = "5965d63402414776c54c266db0211746";
    private static final String SECRET = "d982180eed13275bb948e41cf225f88f";
    private static final String URL = "https://ws.audioscrobbler.com/2.0/";
    private static final OkHttpClient CLIENT = VtOkHttpClient.getInstance();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LastFmClient() {
    }

    static final class SimilarTrack {
        final String artist;
        final String title;
        final int duration;

        SimilarTrack(String artist, String title, int duration) {
            this.artist = artist;
            this.title = title;
            this.duration = duration;
        }
    }

    interface Callback {
        void onSuccess(List<SimilarTrack> tracks);

        void onError(Throwable error);
    }

    static void getSimilarTracks(String artist, String title, int limit, Callback callback) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("api_key", KEY);
        params.put("method", "track.getSimilar");
        params.put("artist", artist);
        params.put("track", title);
        params.put("limit", String.valueOf(limit));
        params.put("autocorrect", "1");

        fetch(params, new okhttp3.Callback() {
            @Override
            public void a(Call call, IOException e) {
                MAIN.post(() -> callback.onError(e));
            }

            @Override
            public void a(Call call, Response response) {
                try {
                    String body = response.a().g();
                    List<SimilarTrack> tracks = parse(body);
                    MAIN.post(() -> callback.onSuccess(tracks));
                } catch (Exception e) {
                    MAIN.post(() -> callback.onError(e));
                }
            }
        });
    }

    private static List<SimilarTrack> parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject similar = root.optJSONObject("similartracks");
        JSONArray items = similar == null ? null : similar.optJSONArray("track");
        List<SimilarTrack> tracks = new ArrayList<>();
        if (items == null) {
            return tracks;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "");
            JSONObject artist = item.optJSONObject("artist");
            String artistName = artist == null ? "" : artist.optString("name", "");
            int duration = item.optInt("duration", 0);
            if (!name.isEmpty() && !artistName.isEmpty()) {
                tracks.add(new SimilarTrack(artistName, name, duration));
            }
        }
        return tracks;
    }

    private static String sign(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.append(entry.getKey()).append(entry.getValue());
        }
        return AndroidUtils.MD5(builder.append(SECRET).toString());
    }

    private static void fetch(TreeMap<String, String> params, okhttp3.Callback callback) {
        params.put("api_sig", sign(params));
        params.put("format", "json");

        FormBody.a body = new FormBody.a(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            body.a(entry.getKey(), entry.getValue());
        }

        Request request = new Request.a()
                .b(URL)
                .a(body.a())
                .a();

        CLIENT.a(request).a(callback);
    }
}
