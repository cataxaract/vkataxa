// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.repository;

import com.vk.api.audio.AudioAdd;
import com.vk.api.audio.AudioAddToPlaylist;
import com.vk.api.audio.AudioDelete;
import com.vk.api.audio.AudioGet;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ru.vtosters.lite.concurrent.VTExecutors;
import ru.vtosters.lite.core.log.VtLog;
import ru.vtosters.lite.core.music.MusicRepository;
import ru.vtosters.lite.core.state.MutableStore;
import ru.vtosters.lite.core.state.Store;
import ru.vtosters.lite.music.cache.MusicCacheImpl;
import ru.vtosters.lite.music.cache.delegate.PlaylistCacheDbDelegate;
import ru.vtosters.lite.music.cache.helpers.TracklistHelper;
import ru.vtosters.lite.utils.AccountManagerUtils;
import ru.vtosters.lite.utils.AndroidUtils;

/**
 * Default {@link MusicRepository} (UI-BE-01): wraps the existing music cache and VK API.
 * The offline cache part is production-ready; the like part is the MU-01 foundation and keeps
 * its index in memory only (no TTL/persistence yet).
 */
public final class MusicRepositoryImpl implements MusicRepository {

    private static final String TAG = "MusicRepo";
    private static final int LIKED_PAGE = 100;
    private static final int LIKED_MAX = 20_000;
    private static final long API_TIMEOUT_SECONDS = 30L;

    private static final MusicRepositoryImpl INSTANCE = new MusicRepositoryImpl();

    private final MutableStore<List<MusicTrack>> cachedTracksStore =
            new MutableStore<>(Collections.emptyList());
    private final MutableStore<List<Playlist>> cachedPlaylistsStore =
            new MutableStore<>(Collections.emptyList());
    private final MutableStore<List<MusicTrack>> likedTracksStore =
            new MutableStore<>(Collections.emptyList());
    private final MutableStore<List<MusicTrack>> emptyTracksStore =
            new MutableStore<>(Collections.emptyList());

    private final Map<String, MutableStore<Boolean>> likedFlags = new ConcurrentHashMap<>();
    private final Set<String> likedIds = ConcurrentHashMap.newKeySet();
    private final Object likedLock = new Object();
    private final AtomicBoolean likedLoading = new AtomicBoolean();

    private volatile boolean cacheLoaded;
    private volatile boolean likedLoaded;

    private MusicRepositoryImpl() {
    }

    public static MusicRepository get() {
        return INSTANCE;
    }

    static MusicRepositoryImpl instance() {
        return INSTANCE;
    }

    @Override
    public Store<Boolean> isLiked(String trackId) {
        MutableStore<Boolean> store = likedFlags.computeIfAbsent(
                trackId == null ? "" : trackId,
                id -> new MutableStore<>(likedIds.contains(id)));
        ensureLikedLoaded();
        return store;
    }

    @Override
    public void like(MusicTrack track) {
        String id = idOf(track);
        if (id == null || likedIds.contains(id)) {
            return;
        }

        VTExecutors.getIoExecutor().execute(() -> {
            try {
                new AudioAdd(track, null)
                        .a(new ApiCallback<Integer>() {
                            @Override
                            public void a(Integer result) {
                                markLiked(track);
                            }

                            @Override
                            public void a(VKApiExecutionException e) {
                                VtLog.e(TAG, "like failed: " + e.d(), e);
                            }
                        })
                        .a();
            } catch (Throwable t) {
                VtLog.e(TAG, "like request failed", t);
            }
        });
    }

    @Override
    public void unlike(MusicTrack track) {
        String id = idOf(track);
        if (id == null || !likedIds.contains(id)) {
            return;
        }

        VTExecutors.getIoExecutor().execute(() -> {
            try {
                new AudioDelete(track)
                        .a(new ApiCallback<Boolean>() {
                            @Override
                            public void a(Boolean result) {
                                markUnliked(track);
                            }

                            @Override
                            public void a(VKApiExecutionException e) {
                                VtLog.e(TAG, "unlike failed: " + e.d(), e);
                            }
                        })
                        .a();
            } catch (Throwable t) {
                VtLog.e(TAG, "unlike request failed", t);
            }
        });
    }

    @Override
    public Store<List<Playlist>> playlists() {
        ensureCacheLoaded();
        return cachedPlaylistsStore;
    }

    @Override
    public void addToPlaylist(String playlistId, List<String> trackIds) {
        int[] ids = parsePlaylistId(playlistId);
        if (ids == null || trackIds == null || trackIds.isEmpty()) {
            return;
        }

        // TODO(MU-02): chunking and RateLimiter for bulk playlist operations.
        VTExecutors.getIoExecutor().execute(() -> {
            try {
                AudioAddToPlaylist.b builder = new AudioAddToPlaylist.b()
                        .a(ids[0])
                        .b(ids[1]);
                for (String trackId : trackIds) {
                    if (trackId != null && !trackId.isEmpty()) {
                        builder.a(trackId);
                    }
                }
                builder.a()
                        .a(new ApiCallback<AudioAddToPlaylist.c>() {
                            @Override
                            public void a(AudioAddToPlaylist.c result) {
                                VtLog.i(TAG, "addToPlaylist ok: " + playlistId);
                            }

                            @Override
                            public void a(VKApiExecutionException e) {
                                VtLog.e(TAG, "addToPlaylist failed: " + e.d(), e);
                            }
                        })
                        .a();
            } catch (Throwable t) {
                VtLog.e(TAG, "addToPlaylist request failed", t);
            }
        });
    }

    @Override
    public Store<List<MusicTrack>> likedTracks() {
        ensureLikedLoaded();
        return likedTracksStore;
    }

    @Override
    public Store<List<MusicTrack>> latestByArtist(String artistId, int limit) {
        // TODO(MU-05): artist latest tracks require the catalog API; empty until that card.
        return emptyTracksStore;
    }

    @Override
    public void refreshLiked() {
        if (!likedLoading.compareAndSet(false, true)) {
            return;
        }

        VTExecutors.getIoExecutor().execute(() -> {
            try {
                List<MusicTrack> tracks = loadLikedSync();
                synchronized (likedLock) {
                    likedIds.clear();
                    for (MusicTrack track : tracks) {
                        String id = idOf(track);
                        if (id != null) {
                            likedIds.add(id);
                        }
                    }
                    likedTracksStore.set(Collections.unmodifiableList(new ArrayList<>(tracks)));
                    for (Map.Entry<String, MutableStore<Boolean>> entry : likedFlags.entrySet()) {
                        entry.getValue().set(likedIds.contains(entry.getKey()));
                    }
                }
                likedLoaded = true;
            } catch (Throwable t) {
                likedLoaded = false;
                VtLog.e(TAG, "refreshLiked failed", t);
            } finally {
                likedLoading.set(false);
            }
        });
    }

    @Override
    public long cachedTrackCount() {
        try {
            return MusicCacheImpl.getTracksCount();
        } catch (Throwable t) {
            VtLog.e(TAG, "cachedTrackCount failed", t);
            return 0L;
        }
    }

    @Override
    public boolean isCacheEmpty() {
        try {
            return MusicCacheImpl.isEmpty();
        } catch (Throwable t) {
            VtLog.e(TAG, "isCacheEmpty failed", t);
            return true;
        }
    }

    @Override
    public boolean hasCachedPlaylists() {
        try {
            return MusicCacheImpl.hasPlaylist();
        } catch (Throwable t) {
            VtLog.e(TAG, "hasCachedPlaylists failed", t);
            return false;
        }
    }

    @Override
    public boolean isCachedTrack(String trackId) {
        try {
            return trackId != null && MusicCacheImpl.isCachedTrack(trackId);
        } catch (Throwable t) {
            VtLog.e(TAG, "isCachedTrack failed", t);
            return false;
        }
    }

    @Override
    public boolean isCachedPlaylist(String playlistId) {
        int[] ids = parsePlaylistId(playlistId);
        if (ids == null) {
            return false;
        }
        try {
            return PlaylistCacheDbDelegate.isCachedPlaylist(
                    AndroidUtils.getGlobalContext(), ids[0] + "_" + ids[1]);
        } catch (Throwable t) {
            VtLog.e(TAG, "isCachedPlaylist failed", t);
            return false;
        }
    }

    @Override
    public boolean isOwnCachePlaylistEmpty() {
        try {
            return PlaylistCacheDbDelegate.isPlaylistEmpty(
                    AndroidUtils.getGlobalContext(), ownCachePlaylistId());
        } catch (Throwable t) {
            VtLog.e(TAG, "isOwnCachePlaylistEmpty failed", t);
            return true;
        }
    }

    @Override
    public List<String> allCachedPlaylistIds() {
        try {
            return PlaylistCacheDbDelegate.getAllPlaylistIds(AndroidUtils.getGlobalContext());
        } catch (Throwable t) {
            VtLog.e(TAG, "allCachedPlaylistIds failed", t);
            return Collections.emptyList();
        }
    }

    @Override
    public Store<List<MusicTrack>> cachedTracks() {
        ensureCacheLoaded();
        return cachedTracksStore;
    }

    @Override
    public Store<List<Playlist>> cachedPlaylists() {
        ensureCacheLoaded();
        return cachedPlaylistsStore;
    }

    @Override
    public List<Playlist> cachedPlaylistsNow() {
        List<Playlist> playlists;
        try {
            playlists = MusicCacheImpl.getPlaylists();
        } catch (Throwable t) {
            VtLog.e(TAG, "cachedPlaylistsNow failed", t);
            playlists = Collections.emptyList();
        }
        if (playlists == null) {
            playlists = Collections.emptyList();
        }
        cachedPlaylistsStore.set(Collections.unmodifiableList(new ArrayList<>(playlists)));
        return playlists;
    }

    @Override
    public List<MusicTrack> cachedPlaylistTracks(String playlistId) {
        int[] ids = parsePlaylistId(playlistId);
        if (ids == null) {
            return Collections.emptyList();
        }
        try {
            List<MusicTrack> tracks = MusicCacheImpl.getPlaylistSongs(
                    String.valueOf(ids[0]), String.valueOf(ids[1]));
            return tracks == null ? Collections.emptyList() : tracks;
        } catch (Throwable t) {
            VtLog.e(TAG, "cachedPlaylistTracks failed", t);
            return Collections.emptyList();
        }
    }

    @Override
    public Playlist cachedPlaylist(String playlistId) {
        if (parsePlaylistId(playlistId) == null) {
            return null;
        }
        try {
            return MusicCacheImpl.getPlaylistById(playlistId);
        } catch (Throwable t) {
            VtLog.e(TAG, "cachedPlaylist failed", t);
            return null;
        }
    }

    @Override
    public String ownCachePlaylistId() {
        return AccountManagerUtils.getUserId() + "_-1";
    }

    @Override
    public void removeCachedTrack(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return;
        }
        try {
            MusicCacheImpl.removeTrack(trackId);
        } catch (Throwable t) {
            VtLog.e(TAG, "removeCachedTrack failed", t);
        } finally {
            invalidateCache();
        }
    }

    @Override
    public void removeCachedPlaylist(String playlistId) {
        if (parsePlaylistId(playlistId) == null) {
            return;
        }
        try {
            PlaylistCacheDbDelegate.deletePlaylist(AndroidUtils.getGlobalContext(), playlistId);
        } catch (Throwable t) {
            VtLog.e(TAG, "removeCachedPlaylist failed", t);
        } finally {
            invalidateCache();
        }
    }

    @Override
    public void removeAllCachedPlaylists() {
        try {
            PlaylistCacheDbDelegate.removeAllPlaylists(AndroidUtils.getGlobalContext());
        } catch (Throwable t) {
            VtLog.e(TAG, "removeAllCachedPlaylists failed", t);
        } finally {
            invalidateCache();
        }
    }

    @Override
    public void clearOwnCachedTracks() {
        try {
            PlaylistCacheDbDelegate.deletePlaylist(
                    AndroidUtils.getGlobalContext(), ownCachePlaylistId());
        } catch (Throwable t) {
            VtLog.e(TAG, "clearOwnCachedTracks failed", t);
        } finally {
            invalidateCache();
        }
    }

    @Override
    public void invalidateCache() {
        cacheLoaded = false;
    }

    private synchronized void ensureCacheLoaded() {
        if (cacheLoaded) {
            return;
        }

        List<MusicTrack> tracks;
        try {
            tracks = TracklistHelper.getMyCachedMusicTracks();
        } catch (Throwable t) {
            VtLog.e(TAG, "load cached tracks failed", t);
            tracks = Collections.emptyList();
        }
        if (tracks == null) {
            tracks = Collections.emptyList();
        }
        cachedTracksStore.set(Collections.unmodifiableList(new ArrayList<>(tracks)));
        cachedPlaylistsNow();
        cacheLoaded = true;
    }

    private void ensureLikedLoaded() {
        if (!likedLoaded) {
            refreshLiked();
        }
    }

    private List<MusicTrack> loadLikedSync() throws Exception {
        int userId = AccountManagerUtils.getUserId();
        if (userId == 0) {
            return Collections.emptyList();
        }

        List<MusicTrack> result = new ArrayList<>();
        for (int offset = 0; offset < LIKED_MAX; offset += LIKED_PAGE) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<MusicTrack>> pageRef = new AtomicReference<>();
            AtomicReference<VKApiExecutionException> errorRef = new AtomicReference<>();

            new AudioGet(userId)
                    .b(LIKED_PAGE)
                    .c(offset)
                    .a(new ApiCallback<List<MusicTrack>>() {
                        @Override
                        public void a(List<MusicTrack> tracks) {
                            pageRef.set(tracks);
                            latch.countDown();
                        }

                        @Override
                        public void a(VKApiExecutionException e) {
                            errorRef.set(e);
                            latch.countDown();
                        }
                    })
                    .a();

            if (!latch.await(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("VK API timeout");
            }
            if (errorRef.get() != null) {
                throw errorRef.get();
            }

            List<MusicTrack> page = pageRef.get();
            if (page == null || page.isEmpty()) {
                break;
            }
            result.addAll(page);
            if (page.size() < LIKED_PAGE) {
                break;
            }
        }
        return result;
    }

    void markLiked(MusicTrack track) {
        String id = idOf(track);
        if (id == null) {
            return;
        }
        synchronized (likedLock) {
            likedIds.add(id);
            MutableStore<Boolean> flag = likedFlags.get(id);
            if (flag != null) {
                flag.set(true);
            }

            List<MusicTrack> current = new ArrayList<>(likedTracksStore.get());
            for (MusicTrack existing : current) {
                if (id.equals(idOf(existing))) {
                    return;
                }
            }
            current.add(0, track);
            likedTracksStore.set(Collections.unmodifiableList(current));
        }
    }

    void markUnliked(MusicTrack track) {
        String id = idOf(track);
        if (id == null) {
            return;
        }
        synchronized (likedLock) {
            likedIds.remove(id);
            MutableStore<Boolean> flag = likedFlags.get(id);
            if (flag != null) {
                flag.set(false);
            }

            List<MusicTrack> current = new ArrayList<>();
            for (MusicTrack existing : likedTracksStore.get()) {
                if (!id.equals(idOf(existing))) {
                    current.add(existing);
                }
            }
            likedTracksStore.set(Collections.unmodifiableList(current));
        }
    }

    private static String idOf(MusicTrack track) {
        if (track == null) {
            return null;
        }
        String id = track.y1();
        return id == null || id.isEmpty() ? null : id;
    }

    private static int[] parsePlaylistId(String playlistId) {
        if (playlistId == null) {
            return null;
        }
        int separator = playlistId.indexOf('_');
        if (separator <= 0 || separator == playlistId.length() - 1) {
            return null;
        }
        try {
            int ownerId = Integer.parseInt(playlistId.substring(0, separator));
            int id = Integer.parseInt(playlistId.substring(separator + 1));
            return new int[]{ownerId, id};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
