// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.music;

import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import ru.vtosters.lite.core.state.Store;

import java.util.List;

/**
 * Music data facade (F-03 contract, implemented in UI-BE-01). UI code reads music data through
 * this interface instead of calling cache helpers directly; the implementation is replaceable.
 */
public interface MusicRepository {

    enum SortMode {
        DATE_ADDED,
        TITLE,
        ARTIST,
        DURATION
    }

    SortMode sortMode();

    void setSortMode(SortMode mode);

    Store<Boolean> isLiked(String trackId);

    boolean isLikedNow(String trackId);

    boolean hasSyncedLikes();

    void like(MusicTrack track);

    void unlike(MusicTrack track);

    Store<List<Playlist>> playlists();

    void addToPlaylist(String playlistId, List<String> trackIds);

    Store<List<MusicTrack>> likedTracks();

    Store<List<MusicTrack>> latestByArtist(String artistId, int limit);

    void refreshLiked();

    long cachedTrackCount();

    boolean isCacheEmpty();

    boolean hasCachedPlaylists();

    boolean isCachedTrack(String trackId);

    boolean isCachedPlaylist(String playlistId);

    boolean isOwnCachePlaylistEmpty();

    List<String> allCachedPlaylistIds();

    Store<List<MusicTrack>> cachedTracks();

    Store<List<Playlist>> cachedPlaylists();

    List<Playlist> cachedPlaylistsNow();

    List<MusicTrack> cachedPlaylistTracks(String playlistId);

    Playlist cachedPlaylist(String playlistId);

    String ownCachePlaylistId();

    void removeCachedTrack(String trackId);

    void removeCachedPlaylist(String playlistId);

    void removeAllCachedPlaylists();

    void clearOwnCachedTracks();

    void invalidateCache();
}
