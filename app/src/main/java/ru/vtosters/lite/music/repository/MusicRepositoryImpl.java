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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.lite.concurrent.VTExecutors;
import ru.vtosters.lite.core.log.VtLog;
import ru.vtosters.lite.core.music.MusicRepository;
import ru.vtosters.lite.core.music.MusicSorter;
import ru.vtosters.lite.core.state.MutableStore;
import ru.vtosters.lite.core.state.Store;
import ru.vtosters.lite.music.cache.MusicCacheImpl;
import ru.vtosters.lite.music.cache.delegate.PlaylistCacheDbDelegate;
import ru.vtosters.lite.music.cache.helpers.TracklistHelper;
import ru.vtosters.lite.utils.AccountManagerUtils;
import ru.vtosters.lite.utils.AndroidUtils;
import ru.vtosters.lite.utils.NetworkUtils;

/**
 * Default {@link MusicRepository} (UI-BE-01/MU-01): wraps the existing music cache and VK API.
 * Liked ids are persisted per account in {@link MusicLikesDb}; the full liked list is refreshed
 * from audio.get with a 15 minute TTL. The like/unlike calls are deduplicated locally.
 */
public final class MusicRepositoryImpl implements MusicRepository {

    private static final String TAG = "MusicRepo";
    private static final String PREF_SORT_MODE = "music_sort_mode";
    private static final int LIKED_PAGE = 100;
    private static final int LIKED_MAX = 20_000;
    private static final long API_TIMEOUT_SECONDS = 30L;
    private static final long LIKED_TTL_MS = 15 * 60 * 1000L;
    private static final long LIKED_FAILURE_BACKOFF_MS = 60 * 1000L;

    private static final MusicRepositoryImpl INSTANCE = new MusicRepositoryImpl();

    private final MutableStore<List<MusicTrack>> cachedTracksStore =
            new MutableStore<>(Collections.emptyList());
    private final MutableStore<List<Playlist>> cachedPlaylistsStore =
            new MutableStore<>(Collections.emptyList());
    private final MutableStore<List<MusicTrack>> likedTracksStore =
            new MutableStore<>(Collections.emptyList());
    private final MutableStore<List<MusicTrack>> emptyTracksStore =
            new MutableStore<>(Collections.emptyList());

    private volatile List<MusicTrack> rawCachedTracks = Collections.emptyList();
    private volatile List<MusicTrack> rawLikedTracks = Collections.emptyList();
    private volatile List<Playlist> rawCachedPlaylists = Collections.emptyList();
    private volatile MusicRepository.SortMode sortMode = loadSortMode();

    private final Map<String, MutableStore<Boolean>> likedFlags = new ConcurrentHashMap<>();
    private final Set<String> likedIds = ConcurrentHashMap.newKeySet();
    private final Object likedLock = new Object();
    private final Object likedLoadLock = new Object();
    private final AtomicBoolean likedLoading = new AtomicBoolean();

    private volatile boolean cacheLoaded;
    private volatile boolean likedIndexLoaded;
    private volatile boolean likedSynced;
    private volatile long lastLikedAttemptAt;

    private MusicRepositoryImpl() {
    }

    public static MusicRepository get() {
        return INSTANCE;
    }

    static MusicRepositoryImpl instance() {
        return INSTANCE;
    }

    @Override
    public MusicRepository.SortMode sortMode() {
        return sortMode;
    }

    @Override
    public void setSortMode(MusicRepository.SortMode mode) {
        MusicRepository.SortMode value = mode == null
                ? MusicRepository.SortMode.DATE_ADDED : mode;
        sortMode = value;
        try {
            Preferences.getPreferences().edit().putString(PREF_SORT_MODE, value.name()).apply();
        } catch (Throwable t) {
            VtLog.e(TAG, "sort mode persist failed", t);
        }
        publishCachedTracks();
        publishLikedTracks();
        publishCachedPlaylists();
    }

    private static MusicRepository.SortMode loadSortMode() {
        try {
            String value = Preferences.getString(PREF_SORT_MODE);
            if (value != null && !value.isEmpty()) {
                return MusicRepository.SortMode.valueOf(value);
            }
        } catch (Throwable ignored) {
        }
        return MusicRepository.SortMode.DATE_ADDED;
    }

    private void publishCachedTracks() {
        cachedTracksStore.set(MusicSorter.sortTracks(rawCachedTracks, sortMode));
    }

    private void publishLikedTracks() {
        likedTracksStore.set(MusicSorter.sortTracks(rawLikedTracks, sortMode));
    }

    private void publishCachedPlaylists() {
        cachedPlaylistsStore.set(MusicSorter.sortPlaylists(rawCachedPlaylists, sortMode));
    }

    @Override
    public Store<Boolean> isLiked(String trackId) {
        ensureLikedIndexLoaded();
        maybeRefreshLiked();
        String key = trackId == null ? "" : trackId;
        return likedFlags.computeIfAbsent(key, id -> new MutableStore<>(likedIds.contains(id)));
    }

    @Override
    public boolean isLikedNow(String trackId) {
        ensureLikedIndexLoaded();
        return trackId != null && !trackId.isEmpty() && likedIds.contains(trackId);
    }

    @Override
    public boolean hasSyncedLikes() {
        ensureLikedIndexLoaded();
        return likedSynced;
    }

    @Override
    public void like(MusicTrack track) {
        String id = idOf(track);
        ensureLikedIndexLoaded();
        if (id == null || likedIds.contains(id) || !NetworkUtils.isNetworkConnected()) {
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
        ensureLikedIndexLoaded();
        if (id == null || !NetworkUtils.isNetworkConnected()) {
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
        ensureLikedIndexLoaded();
        maybeRefreshLiked();
        return likedTracksStore;
    }

    @Override
    public Store<List<MusicTrack>> latestByArtist(String artistId, int limit) {
        // TODO(MU-05): artist latest tracks require the catalog API; empty until that card.
        return emptyTracksStore;
    }

    @Override
    public void refreshLiked() {
        ensureLikedIndexLoaded();
        if (!NetworkUtils.isNetworkConnected()) {
            return;
        }
        if (!likedLoading.compareAndSet(false, true)) {
            return;
        }

        lastLikedAttemptAt = System.currentTimeMillis();
        VTExecutors.getIoExecutor().execute(() -> {
            try {
                List<MusicTrack> tracks = loadLikedSync();
                Set<String> ids = new HashSet<>();
                for (MusicTrack track : tracks) {
                    String id = idOf(track);
                    if (id != null) {
                        ids.add(id);
                    }
                }
                MusicLikesDb.get().replaceAll(
                        AccountManagerUtils.getUserId(), ids, System.currentTimeMillis());

                synchronized (likedLock) {
                    likedIds.clear();
                    likedIds.addAll(ids);
                    rawLikedTracks = Collections.unmodifiableList(new ArrayList<>(tracks));
                    publishLikedTracks();
                    for (Map.Entry<String, MutableStore<Boolean>> entry : likedFlags.entrySet()) {
                        entry.getValue().set(likedIds.contains(entry.getKey()));
                    }
                }
                likedIndexLoaded = true;
                likedSynced = true;
            } catch (Throwable t) {
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
        rawCachedPlaylists = Collections.unmodifiableList(new ArrayList<>(playlists));
        publishCachedPlaylists();
        return MusicSorter.sortPlaylists(rawCachedPlaylists, sortMode);
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
            return MusicSorter.sortTracks(
                    tracks == null ? Collections.emptyList() : tracks, sortMode);
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
        rawCachedTracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        publishCachedTracks();
        cachedPlaylistsNow();
        cacheLoaded = true;
    }

    private void ensureLikedIndexLoaded() {
        if (likedIndexLoaded) {
            return;
        }
        synchronized (likedLoadLock) {
            if (likedIndexLoaded) {
                return;
            }
            Set<String> ids = Collections.emptySet();
            try {
                int account = AccountManagerUtils.getUserId();
                ids = MusicLikesDb.get().ids(account);
                likedSynced = MusicLikesDb.get().syncedAt(account) > 0L;
            } catch (Throwable t) {
                VtLog.e(TAG, "liked index load failed", t);
            }
            synchronized (likedLock) {
                likedIds.clear();
                likedIds.addAll(ids);
                for (Map.Entry<String, MutableStore<Boolean>> entry : likedFlags.entrySet()) {
                    entry.getValue().set(likedIds.contains(entry.getKey()));
                }
            }
            likedIndexLoaded = true;
        }
    }

    private void maybeRefreshLiked() {
        if (!NetworkUtils.isNetworkConnected()) {
            return;
        }
        int account = AccountManagerUtils.getUserId();
        if (account == 0) {
            return;
        }
        long syncedAt = 0L;
        try {
            syncedAt = MusicLikesDb.get().syncedAt(account);
        } catch (Throwable t) {
            VtLog.e(TAG, "liked sync time read failed", t);
        }
        long now = System.currentTimeMillis();
        if (now - syncedAt < LIKED_TTL_MS) {
            return;
        }
        if (now - lastLikedAttemptAt < LIKED_FAILURE_BACKOFF_MS) {
            return;
        }
        refreshLiked();
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
        final int account = AccountManagerUtils.getUserId();
        synchronized (likedLock) {
            likedIds.add(id);
            likedIndexLoaded = true;
            MutableStore<Boolean> flag = likedFlags.get(id);
            if (flag != null) {
                flag.set(true);
            }

            List<MusicTrack> current = new ArrayList<>(rawLikedTracks);
            for (MusicTrack existing : current) {
                if (id.equals(idOf(existing))) {
                    persistLiked(account, id, true);
                    return;
                }
            }
            current.add(0, track);
            rawLikedTracks = Collections.unmodifiableList(current);
            publishLikedTracks();
        }
        persistLiked(account, id, true);
    }

    void markUnliked(MusicTrack track) {
        String id = idOf(track);
        if (id == null) {
            return;
        }
        final int account = AccountManagerUtils.getUserId();
        synchronized (likedLock) {
            likedIds.remove(id);
            likedIndexLoaded = true;
            MutableStore<Boolean> flag = likedFlags.get(id);
            if (flag != null) {
                flag.set(false);
            }

            List<MusicTrack> current = new ArrayList<>();
            for (MusicTrack existing : rawLikedTracks) {
                if (!id.equals(idOf(existing))) {
                    current.add(existing);
                }
            }
            rawLikedTracks = Collections.unmodifiableList(current);
            publishLikedTracks();
        }
        persistLiked(account, id, false);
    }

    private static void persistLiked(int account, String trackId, boolean liked) {
        VTExecutors.getIoExecutor().execute(() -> {
            try {
                if (liked) {
                    MusicLikesDb.get().add(account, trackId);
                } else {
                    MusicLikesDb.get().remove(account, trackId);
                }
            } catch (Throwable t) {
                VtLog.e(TAG, "liked index write failed", t);
            }
        });
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
