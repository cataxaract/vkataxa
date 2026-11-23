package ru.vtosters.lite.music.spotify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpotifySyncStore {
    private static final Object LOCK = new Object();
    private static final String FILE = "spotify_sync.json";

    private SpotifySyncStore() {
    }

    public static final class Entry {
        public String spotifyId;
        public String name;
        public int vkOwnerId;
        public int vkPlaylistId;
        public long lastSync;
        public Map<String, String> tracks = new LinkedHashMap<>();

        public boolean valid() {
            return spotifyId != null && vkOwnerId != 0 && vkPlaylistId != 0;
        }
    }

    public static List<Entry> all(Context context) {
        synchronized (LOCK) {
            Map<String, Entry> map = read(context);
            return new ArrayList<>(map.values());
        }
    }

    public static Entry get(Context context, String spotifyId) {
        synchronized (LOCK) {
            return read(context).get(spotifyId);
        }
    }

    public static void put(Context context, Entry entry) {
        synchronized (LOCK) {
            Map<String, Entry> map = read(context);
            map.put(entry.spotifyId, entry);
            write(context, map);
        }
    }

    public static void remove(Context context, String spotifyId) {
        synchronized (LOCK) {
            Map<String, Entry> map = read(context);
            map.remove(spotifyId);
            write(context, map);
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    private static Map<String, Entry> read(Context context) {
        Map<String, Entry> result = new LinkedHashMap<>();
        File file = file(context);
        if (!file.exists()) {
            return result;
        }

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = input.read(data);
            if (read <= 0) {
                return result;
            }

            JSONObject root = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            JSONArray items = root.optJSONArray("playlists");
            if (items == null) {
                return result;
            }

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }

                Entry entry = new Entry();
                entry.spotifyId = item.optString("spotify_id", "");
                entry.name = item.optString("name", "");
                entry.vkOwnerId = item.optInt("vk_owner_id", 0);
                entry.vkPlaylistId = item.optInt("vk_playlist_id", 0);
                entry.lastSync = item.optLong("last_sync", 0L);

                JSONObject tracks = item.optJSONObject("tracks");
                if (tracks != null) {
                    java.util.Iterator<String> keys = tracks.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        entry.tracks.put(key, tracks.optString(key, ""));
                    }
                }

                if (!entry.spotifyId.isEmpty()) {
                    result.put(entry.spotifyId, entry);
                }
            }
        } catch (Exception ignored) {
        }

        return result;
    }

    private static void write(Context context, Map<String, Entry> map) {
        try {
            JSONArray items = new JSONArray();
            for (Entry entry : map.values()) {
                JSONObject item = new JSONObject();
                item.put("spotify_id", entry.spotifyId);
                item.put("name", entry.name);
                item.put("vk_owner_id", entry.vkOwnerId);
                item.put("vk_playlist_id", entry.vkPlaylistId);
                item.put("last_sync", entry.lastSync);

                JSONObject tracks = new JSONObject();
                for (Map.Entry<String, String> track : entry.tracks.entrySet()) {
                    tracks.put(track.getKey(), track.getValue());
                }
                item.put("tracks", tracks);
                items.put(item);
            }

            JSONObject root = new JSONObject();
            root.put("playlists", items);

            try (FileOutputStream output = new FileOutputStream(file(context))) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
