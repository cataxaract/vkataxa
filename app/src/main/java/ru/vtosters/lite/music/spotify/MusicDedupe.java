// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.spotify;

import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ru.vtosters.lite.utils.music.MusicTrackUtils;

/**
 * MU-14: read-only duplicate report for "Моя музыка" and all VK playlists.
 * Exact duplicates are grouped by VK track id ({@link MusicTrack#y1()}); near duplicates are
 * grouped by normalized artist/title/duration. No add/delete/search calls are made here.
 */
public final class MusicDedupe {
    private static final int MAX_EXAMPLES = 5;

    private MusicDedupe() {
    }

    public static Report scan() throws Exception {
        List<Section> sections = new ArrayList<>();
        int account = VkPlaylistApi.currentUserId();
        sections.add(analyze("Моя музыка", account, 0, true, VkPlaylistApi.getMyMusic()));

        List<Playlist> playlists = VkPlaylistApi.getUserPlaylists();
        if (playlists != null) {
            for (Playlist playlist : playlists) {
                if (playlist == null) {
                    continue;
                }
                int[] ids = parsePlaylistId(playlist.v1());
                if (ids == null) {
                    continue;
                }
                String name = playlist.g == null || playlist.g.isEmpty()
                        ? "Плейлист" : playlist.g;
                sections.add(analyze(name, ids[0], ids[1], false,
                        VkPlaylistApi.getPlaylistTracks(ids[0], ids[1])));
            }
        }
        return new Report(sections);
    }

    private static Section analyze(String name, int ownerId, int playlistId, boolean myMusic,
                                   List<MusicTrack> tracks) {
        if (tracks == null) {
            tracks = new ArrayList<>();
        }

        Map<String, List<MusicTrack>> byUid = new LinkedHashMap<>();
        Map<String, List<MusicTrack>> byMeta = new LinkedHashMap<>();
        for (MusicTrack track : tracks) {
            if (track == null) {
                continue;
            }
            String uid = track.y1();
            if (uid != null && !uid.isEmpty()) {
                byUid.computeIfAbsent(uid, ignored -> new ArrayList<>()).add(track);
            }
            String artistKey = normalize(MusicTrackUtils.getArtists(track));
            String titleKey = normalize(track.f);
            if (!artistKey.isEmpty() || !titleKey.isEmpty()) {
                String meta = artistKey + "|" + titleKey + "|" + track.h;
                byMeta.computeIfAbsent(meta, ignored -> new ArrayList<>()).add(track);
            }
        }

        List<Group> exact = new ArrayList<>();
        for (List<MusicTrack> group : byUid.values()) {
            if (group.size() > 1) {
                exact.add(new Group(group));
            }
        }

        List<Group> similar = new ArrayList<>();
        for (List<MusicTrack> group : byMeta.values()) {
            if (group.size() < 2) {
                continue;
            }
            Set<String> uids = new LinkedHashSet<>();
            for (MusicTrack track : group) {
                uids.add(track.y1());
            }
            if (uids.size() > 1) {
                similar.add(new Group(group));
            }
        }

        return new Section(name, ownerId, playlistId, myMusic, tracks.size(), exact, similar);
    }

    public static String display(MusicTrack track) {
        String title = track.f == null ? "" : track.f;
        String artists = MusicTrackUtils.getArtists(track);
        if (artists == null || artists.isEmpty()) {
            return title;
        }
        return artists + " — " + title;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                result.append(c);
                space = false;
            } else if (!space && result.length() > 0) {
                result.append(' ');
                space = true;
            }
        }
        return result.toString().trim();
    }

    private static int[] parsePlaylistId(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf('_');
        if (separator <= 0 || separator == value.length() - 1) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(value.substring(0, separator)),
                    Integer.parseInt(value.substring(separator + 1))
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class Group {
        public final List<MusicTrack> tracks;

        Group(List<MusicTrack> tracks) {
            this.tracks = tracks;
        }
    }

    public static final class Section {
        public final String name;
        public final int ownerId;
        public final int playlistId;
        public final boolean myMusic;
        public final int total;
        public final List<Group> exact;
        public final List<Group> similar;

        Section(String name, int ownerId, int playlistId, boolean myMusic, int total,
                List<Group> exact, List<Group> similar) {
            this.name = name;
            this.ownerId = ownerId;
            this.playlistId = playlistId;
            this.myMusic = myMusic;
            this.total = total;
            this.exact = exact;
            this.similar = similar;
        }

        public int exactGroups() {
            return exact.size();
        }

        public int extra() {
            int result = 0;
            for (Group group : exact) {
                result += group.tracks.size() - 1;
            }
            return result;
        }

        public int similarGroups() {
            return similar.size();
        }
    }

    public static final class Report {
        public final List<Section> sections;

        Report(List<Section> sections) {
            this.sections = sections;
        }

        public String toText() {
            StringBuilder result = new StringBuilder();
            int totalTracks = 0;
            int totalExact = 0;
            int totalExtra = 0;
            int totalSimilar = 0;

            for (Section section : sections) {
                totalTracks += section.total;
                totalExact += section.exactGroups();
                totalExtra += section.extra();
                totalSimilar += section.similarGroups();

                result.append(section.name).append(": ")
                        .append(section.total).append(" треков · точных дублей ")
                        .append(section.exactGroups()).append(" (лишних ")
                        .append(section.extra()).append(") · похожих групп ")
                        .append(section.similarGroups()).append('\n');
                appendExamples(result, "точный", section.exact);
                appendExamples(result, "похоже", section.similar);
                result.append('\n');
            }

            result.append("Итого: ").append(totalTracks).append(" треков · точных групп ")
                    .append(totalExact).append(" · лишних ").append(totalExtra)
                    .append(" · похожих групп ").append(totalSimilar).append('\n')
                    .append("Отметь треки для удаления; по умолчанию ничего не выбрано.");
            return result.toString();
        }

        private static void appendExamples(StringBuilder result, String prefix, List<Group> groups) {
            for (int i = 0; i < groups.size() && i < MAX_EXAMPLES; i++) {
                Group group = groups.get(i);
                result.append("  ").append(prefix).append(": ")
                        .append(display(group.tracks.get(0)))
                        .append(" ×").append(group.tracks.size()).append('\n');
            }
        }
    }
}
