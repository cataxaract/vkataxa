package ru.vtosters.lite.music.spotify;

import android.util.Log;

import com.vk.api.audio.AudioAdd;
import com.vk.api.audio.AudioAddToPlaylist;
import com.vk.api.audio.AudioDeletePlaylist;
import com.vk.api.audio.AudioGet;
import com.vk.api.audio.AudioRemoveFromPlaylist;
import com.vk.api.audio.AudioSavePlaylist;
import com.vk.api.audio.AudioSearch;
import com.vk.api.base.ApiCallback;
import com.vk.api.base.ApiConfig;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.api.sdk.o.VKRequest;
import com.vk.api.audio.AudioGetPlaylists;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;
import com.vk.dto.music.UserPlaylists;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ru.vtosters.lite.core.net.RateLimiter;
import ru.vtosters.lite.utils.AccountManagerUtils;

final class VkPlaylistApi {
    private static final String TAG = "VKataxaImport";
    private static final int PAGE = 100;
    private static final int ADD_CHUNK = 10;
    private static final int SEARCH_BATCH = 10;
    private static final int ADD_BUDGET_PER_HOUR = 60;
    private static final long ADD_BUDGET_WINDOW_MS = 60 * 60 * 1000L;
    private static final ArrayDeque<Long> ADD_TIMES = new ArrayDeque<>();
    private static final RateLimiter LIMITER = new RateLimiter(350L);

    private VkPlaylistApi() {
    }

    static int currentUserId() {
        return AccountManagerUtils.getUserId();
    }

    static List<Playlist> getUserPlaylists() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UserPlaylists> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        LIMITER.acquire();
        new AudioGetPlaylists.b(currentUserId())
                .a()
                .a(new ApiCallback<UserPlaylists>() {
                    @Override
                    public void a(UserPlaylists value) {
                        result.set(value);
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();

        await(latch, error);
        UserPlaylists value = result.get();
        return value == null ? new ArrayList<>() : new ArrayList<>(value.b());
    }

    static List<MusicTrack> search(String query, int count) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<MusicTrack>> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        LIMITER.acquire();
        new AudioSearch(query, false, false, 0, count, 0)
                .a(new ApiCallback<List<MusicTrack>>() {
                    @Override
                    public void a(List<MusicTrack> tracks) {
                        result.set(tracks);
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();

        await(latch, error);
        List<MusicTrack> tracks = result.get();
        return tracks == null ? new ArrayList<>() : tracks;
    }

    static List<List<MusicTrack>> searchBatch(List<String> queries, int count) throws Exception {
        List<List<MusicTrack>> results = new ArrayList<>();
        for (int start = 0; start < queries.size(); start += SEARCH_BATCH) {
            int end = Math.min(start + SEARCH_BATCH, queries.size());
            List<String> chunk = queries.subList(start, end);
            List<List<MusicTrack>> batch = executeSearch(chunk, count);
            if (batch != null && batch.size() == chunk.size()) {
                results.addAll(batch);
                continue;
            }
            for (String query : chunk) {
                try {
                    results.add(search(query, count));
                } catch (VKApiExecutionException error) {
                    if (error.d() == 14 || error.d() == RateLimiter.ERROR_FLOOD_CONTROL) {
                        throw error;
                    }
                    results.add(new ArrayList<>());
                } catch (Exception error) {
                    if (isCaptchaFailure(error)) {
                        throw error;
                    }
                    results.add(new ArrayList<>());
                } catch (Throwable ignored) {
                    results.add(new ArrayList<>());
                }
            }
        }
        return results;
    }

    private static boolean isCaptchaFailure(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message != null && message.contains("Can't confirm validation");
    }

    private static List<List<MusicTrack>> executeSearch(List<String> queries, int count) throws Exception {
        try {
            StringBuilder code = new StringBuilder("var r=[];");
            for (String query : queries) {
                JSONObject params = new JSONObject();
                params.put("q", query);
                params.put("count", count);
                params.put("search_own", 0);
                params.put("performer_only", 0);
                params.put("offset", 0);
                code.append("r.push(API.audio.search(").append(params).append("));");
            }
            code.append("return r;");

            VKRequest<JSONObject> request = new VKRequest<>("execute");
            request.a("code", code.toString());
            LIMITER.acquire();
            JSONObject root = request.a(ApiConfig.e);
            JSONArray responses = root == null ? null : root.optJSONArray("response");
            if (responses == null || responses.length() != queries.size()) {
                return null;
            }

            List<List<MusicTrack>> results = new ArrayList<>();
            for (int i = 0; i < responses.length(); i++) {
                JSONObject item = responses.optJSONObject(i);
                if (item == null || item.has("error") || item.has("error_code")) {
                    return null;
                }
                JSONArray items = item.optJSONArray("items");
                if (items == null) {
                    items = item.optJSONArray("audios");
                }
                List<MusicTrack> tracks = new ArrayList<>();
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject raw = items.optJSONObject(j);
                        if (raw == null) {
                            continue;
                        }
                        try {
                            MusicTrack track = MusicTrack.W.a(raw);
                            if (track != null) {
                                tracks.add(track);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                results.add(tracks);
            }
            return results;
        } catch (VKApiExecutionException error) {
            Log.d(TAG, "execute failed code=" + error.d() + " msg=" + error.getMessage());
            if (error.d() == 14) {
                throw error;
            }
            return null;
        } catch (Exception error) {
            Log.d(TAG, "execute failed: " + error);
            if (isCaptchaFailure(error)) {
                throw error;
            }
            return null;
        } catch (Throwable error) {
            Log.d(TAG, "execute crashed: " + error);
            return null;
        }
    }

    static Playlist createPlaylist(int ownerId, String title, String description, List<MusicTrack> initial) throws Exception {
        LIMITER.acquire();
        AudioSavePlaylist.b builder = new AudioSavePlaylist.b()
                .a(ownerId)
                .b(0)
                .b(title)
                .a(description);

        int limit = Math.min(initial.size(), 100);
        for (int i = 0; i < limit; i++) {
            builder.a(initial.get(i));
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Playlist> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        builder.a().a(new ApiCallback<Playlist>() {
            @Override
            public void a(Playlist playlist) {
                result.set(playlist);
                latch.countDown();
            }

            @Override
            public void a(VKApiExecutionException e) {
                error.set(e);
                latch.countDown();
            }
        }).a();

        await(latch, error);
        Playlist playlist = result.get();
        if (playlist == null || playlist.a == 0) {
            throw new IllegalStateException("VK did not return playlist id");
        }
        return playlist;
    }

    static void deletePlaylist(int ownerId, int playlistId) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        LIMITER.acquire();
        new AudioDeletePlaylist(playlistId, ownerId)
                .a(new ApiCallback<Boolean>() {
                    @Override
                    public void a(Boolean result) {
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();

        await(latch, error);
    }

    interface ChunkListener {
        void onChunkAdded(List<MusicTrack> chunk);
    }

    static void addTracks(int ownerId, int playlistId, List<MusicTrack> tracks) throws Exception {
        addTracks(ownerId, playlistId, tracks, null);
    }

    static void addTracks(
            int ownerId,
            int playlistId,
            List<MusicTrack> tracks,
            ChunkListener listener
    ) throws Exception {
        for (int start = 0; start < tracks.size(); start += ADD_CHUNK) {
            int end = Math.min(start + ADD_CHUNK, tracks.size());
            List<MusicTrack> chunk = new ArrayList<>(tracks.subList(start, end));
            reserveAddBudget(chunk.size());
            LIMITER.acquire();
            AudioAddToPlaylist.b builder = new AudioAddToPlaylist.b()
                    .a(ownerId)
                    .b(playlistId);
            for (MusicTrack track : chunk) {
                builder.a(track);
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();

            builder.a().a(new ApiCallback() {
                @Override
                public void a(Object o) {
                    latch.countDown();
                }

                @Override
                public void a(VKApiExecutionException e) {
                    error.set(e);
                    latch.countDown();
                }
            }).a();

            await(latch, error);
            if (listener != null) {
                listener.onChunkAdded(chunk);
            }
        }
    }

    static void removeTracks(int ownerId, int playlistId, List<MusicTrack> tracks) throws Exception {
        for (int start = 0; start < tracks.size(); start += ADD_CHUNK) {
            int end = Math.min(start + ADD_CHUNK, tracks.size());
            LIMITER.acquire();
            AudioRemoveFromPlaylist.b builder = new AudioRemoveFromPlaylist.b()
                    .a(ownerId)
                    .b(playlistId);
            for (int i = start; i < end; i++) {
                builder.a(tracks.get(i));
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();

            builder.a().a(new ApiCallback() {
                @Override
                public void a(Object o) {
                    latch.countDown();
                }

                @Override
                public void a(VKApiExecutionException e) {
                    error.set(e);
                    latch.countDown();
                }
            }).a();

            await(latch, error);
        }
    }

    static List<MusicTrack> getPlaylistTracks(int ownerId, int playlistId) throws Exception {
        List<MusicTrack> result = new ArrayList<>();
        for (int offset = 0; offset < 2000; offset += PAGE) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<MusicTrack>> page = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();

            LIMITER.acquire();
            new AudioGet(ownerId)
                    .d(playlistId)
                    .b(PAGE)
                    .c(offset)
                    .a(new ApiCallback<List<MusicTrack>>() {
                        @Override
                        public void a(List<MusicTrack> tracks) {
                            page.set(tracks);
                            latch.countDown();
                        }

                        @Override
                        public void a(VKApiExecutionException e) {
                            error.set(e);
                            latch.countDown();
                        }
                    })
                    .a();

            await(latch, error);
            List<MusicTrack> tracks = page.get();
            if (tracks == null || tracks.isEmpty()) {
                break;
            }
            result.addAll(tracks);
            if (tracks.size() < PAGE) {
                break;
            }
        }
        return result;
    }

    static List<MusicTrack> getMyMusic() throws Exception {
        List<MusicTrack> result = new ArrayList<>();
        int count = 100;
        for (int offset = 0; offset < 20000; offset += count) {
            List<MusicTrack> page = getMyMusicPage(count, offset);
            if (page.isEmpty()) {
                break;
            }
            result.addAll(page);
            if (page.size() < count) {
                break;
            }
        }
        return result;
    }

    private static List<MusicTrack> getMyMusicPage(int count, int offset) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<MusicTrack>> page = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        LIMITER.acquire();
        new AudioGet(currentUserId())
                .b(count)
                .c(offset)
                .a(new ApiCallback<List<MusicTrack>>() {
                    @Override
                    public void a(List<MusicTrack> tracks) {
                        page.set(tracks);
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();

        await(latch, error);
        List<MusicTrack> tracks = page.get();
        return tracks == null ? new ArrayList<>() : tracks;
    }

    static void addToMyMusic(MusicTrack track) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        reserveAddBudget(1);
        LIMITER.acquire();
        new AudioAdd(track, null)
                .a(new ApiCallback<Integer>() {
                    @Override
                    public void a(Integer result) {
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();

        await(latch, error);
    }

    private static void reserveAddBudget(int count) throws ImportBudgetExceededException {
        if (count <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (ADD_TIMES) {
            while (!ADD_TIMES.isEmpty() && now - ADD_TIMES.peekFirst() > ADD_BUDGET_WINDOW_MS) {
                ADD_TIMES.removeFirst();
            }
            if (ADD_TIMES.size() + count > ADD_BUDGET_PER_HOUR) {
                throw new ImportBudgetExceededException(ADD_BUDGET_PER_HOUR);
            }
            for (int i = 0; i < count; i++) {
                ADD_TIMES.addLast(now);
            }
        }
    }

    private static void await(CountDownLatch latch, AtomicReference<Throwable> error) throws Exception {
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("VK API timeout");
        }
        Throwable throwable = error.get();
        if (throwable instanceof VKApiExecutionException) {
            VKApiExecutionException apiError = (VKApiExecutionException) throwable;
            if (apiError.d() == RateLimiter.ERROR_FLOOD_CONTROL) {
                LIMITER.onApiError(apiError.d());
            }
            throw apiError;
        }
        if (throwable != null) {
            throw new IllegalStateException(throwable);
        }
    }
}
