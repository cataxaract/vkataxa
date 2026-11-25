package ru.vtosters.lite.utils.music;

import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Artist;

import java.util.stream.Collectors;

public class MusicTrackUtils {
    public static String getArtists(MusicTrack musicTrack) {
        String artists = null;
        if (musicTrack.L != null && !musicTrack.L.isEmpty()) {
            artists = musicTrack.L.stream()
                    .map(Artist::w1)
                    .filter(value -> value != null && !value.isEmpty())
                    .collect(Collectors.joining(", "));
        }
        if (artists == null || artists.isEmpty()) {
            artists = musicTrack.C;
        }
        return normalizeMetadata(artists);
    }

    private static String normalizeMetadata(String in) {
        return in == null ? "" : in.replaceAll("[\\\\/:*?\"<>|]", "");
    }
}
