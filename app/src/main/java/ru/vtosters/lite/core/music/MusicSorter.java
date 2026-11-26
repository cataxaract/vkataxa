// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.music;

import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import ru.vtosters.lite.utils.music.MusicTrackUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MU-09: stable repository-level sorting. Repository-backed lists (offline cache, liked tracks,
 * cached playlists) are sorted before they are published to the UI stores.
 */
public final class MusicSorter {

    private MusicSorter() {
    }

    public static List<MusicTrack> sortTracks(List<MusicTrack> tracks, MusicRepository.SortMode mode) {
        if (tracks == null || tracks.isEmpty()) {
            return Collections.emptyList();
        }
        if (tracks.size() < 2 || mode == null || mode == MusicRepository.SortMode.DATE_ADDED) {
            return tracks;
        }

        List<MusicTrack> copy = new ArrayList<>(tracks);
        Comparator<MusicTrack> comparator;
        switch (mode) {
            case TITLE:
                comparator = Comparator.comparing(
                        track -> track == null ? "" : safe(track.f),
                        String.CASE_INSENSITIVE_ORDER);
                break;
            case ARTIST:
                comparator = Comparator
                        .comparing((MusicTrack track) -> track == null
                                        ? "" : safe(MusicTrackUtils.getArtists(track)),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(track -> track == null ? "" : safe(track.f),
                                String.CASE_INSENSITIVE_ORDER);
                break;
            case DURATION:
                comparator = Comparator.comparingInt(track -> track == null ? 0 : track.h);
                break;
            default:
                return tracks;
        }
        copy.sort(comparator);
        return Collections.unmodifiableList(copy);
    }

    public static List<Playlist> sortPlaylists(List<Playlist> playlists, MusicRepository.SortMode mode) {
        if (playlists == null || playlists.isEmpty()) {
            return Collections.emptyList();
        }
        if (playlists.size() < 2 || mode == null || mode == MusicRepository.SortMode.DATE_ADDED) {
            return playlists;
        }

        List<Playlist> copy = new ArrayList<>(playlists);
        copy.sort(Comparator.comparing(
                playlist -> playlist == null ? "" : safe(playlist.g),
                String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(copy);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
