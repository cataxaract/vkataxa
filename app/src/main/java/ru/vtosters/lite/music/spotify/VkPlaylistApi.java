package ru.vtosters.lite.music.spotify;

import com.vk.api.audio.AudioAddToPlaylist;
import com.vk.api.audio.AudioDeletePlaylist;
import com.vk.api.audio.AudioGet;
import com.vk.api.audio.AudioRemoveFromPlaylist;
import com.vk.api.audio.AudioSavePlaylist;
import com.vk.api.audio.AudioSearch;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ru.vtosters.lite.utils.AccountManagerUtils;

final class VkPlaylistApi {
    private static final int PAGE = 100;
    private static final int ADD_CHUNK = 50;

    private VkPlaylistApi() {
    }

    static int currentUserId() {
        return AccountManagerUtils.getUserId();
    }

    static List<MusicTrack> search(String query, int count) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<MusicTrack>> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

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

    static Playlist createPlaylist(int ownerId, String title, String description, List<MusicTrack> initial) throws Exception {
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

    static void addTracks(int ownerId, int playlistId, List<MusicTrack> tracks) throws Exception {
        for (int start = 0; start < tracks.size(); start += ADD_CHUNK) {
            int end = Math.min(start + ADD_CHUNK, tracks.size());
            AudioAddToPlaylist.b builder = new AudioAddToPlaylist.b()
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
            Thread.sleep(350L);
        }
    }

    static void removeTracks(int ownerId, int playlistId, List<MusicTrack> tracks) throws Exception {
        for (int start = 0; start < tracks.size(); start += ADD_CHUNK) {
            int end = Math.min(start + ADD_CHUNK, tracks.size());
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
            Thread.sleep(350L);
        }
    }

    static List<MusicTrack> getPlaylistTracks(int ownerId, int playlistId) throws Exception {
        List<MusicTrack> result = new ArrayList<>();
        for (int offset = 0; offset < 2000; offset += PAGE) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<MusicTrack>> page = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();

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
            Thread.sleep(250L);
        }
        return result;
    }

    private static void await(CountDownLatch latch, AtomicReference<Throwable> error) throws Exception {
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("VK API timeout");
        }
        Throwable throwable = error.get();
        if (throwable != null) {
            throw new IllegalStateException(throwable);
        }
    }
}
