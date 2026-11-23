package ru.vtosters.lite.music.wave;

import android.content.Context;
import android.content.SharedPreferences;

import com.vk.dto.music.MusicTrack;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class WaveCache {
    private static final String PREFS = "vt_wave_cache";
    private static final long TTL_MS = 10 * 60 * 1000L;

    private WaveCache() {
    }

    static List<MusicTrack> load(Context context, String mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString("cache_" + mode, null);
        if (raw == null) {
            return null;
        }

        try {
            JSONObject root = new JSONObject(raw);
            long savedAt = root.optLong("saved_at");
            if (System.currentTimeMillis() - savedAt > TTL_MS) {
                return null;
            }

            JSONArray items = root.optJSONArray("tracks");
            if (items == null || items.length() == 0) {
                return null;
            }

            List<MusicTrack> tracks = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    tracks.add(new MusicTrack(item));
                }
            }
            return tracks.isEmpty() ? null : tracks;
        } catch (JSONException ignored) {
            return null;
        }
    }

    static String loadSource(Context context, String mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString("cache_" + mode, null);
        if (raw == null) {
            return "VK";
        }
        try {
            String source = new JSONObject(raw).optString("source", "VK");
            return source == null || source.isEmpty() ? "VK" : source;
        } catch (JSONException ignored) {
            return "VK";
        }
    }

    static void save(Context context, String mode, String source, List<MusicTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }

        try {
            JSONArray items = new JSONArray();
            for (MusicTrack track : tracks) {
                items.put(track.J());
            }

            JSONObject root = new JSONObject();
            root.put("saved_at", System.currentTimeMillis());
            root.put("source", source);
            root.put("tracks", items);

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("cache_" + mode, root.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }
}
