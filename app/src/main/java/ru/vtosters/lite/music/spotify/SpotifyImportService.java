package ru.vtosters.lite.music.spotify;

import android.content.Context;

import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.vtosters.lite.music.wave.TrackMatcher;

public final class SpotifyImportService {
    private static final long SEARCH_DELAY_MS = 260L;

    private SpotifyImportService() {
    }

    public interface Progress {
        void onProgress(int done, int total, String status);
    }

    public interface Callback {
        void onDone(Result result);

        void onError(Throwable error);
    }

    public static final class Result {
        public int matched;
        public int approximate;
        public int notFound;
        public int added;
        public int removed;
        public boolean created;
        public String playlistName;
        public int ownerId;
        public int playlistId;

        public String summary() {
            if (created) {
                return "Импортировано: " + matched + " треков"
                        + (approximate > 0 ? ", приблизительно " + approximate : "")
                        + (notFound > 0 ? ", не найдено " + notFound : "");
            }

            StringBuilder builder = new StringBuilder("Синхронизировано");
            if (added > 0 || removed > 0) {
                builder.append(": +").append(added).append(" / -").append(removed);
            } else {
                builder.append(": изменений нет");
            }
            if (notFound > 0) {
                builder.append(", не найдено ").append(notFound);
            }
            return builder.toString();
        }
    }

    public static void importPlaylist(Context context, SpotifyPlaylist playlist, Progress progress, Callback callback) {
        try {
            List<SpotifyTrack> tracks = SpotifyApi.getPlaylistTracks(context, playlist.id);
            importTracks(context, playlist.id, playlist.name, tracks, progress, callback);
        } catch (Throwable error) {
            callback.onError(error);
        }
    }

    public static void syncPlaylist(Context context, SpotifyPlaylist playlist, Progress progress, Callback callback) {
        SpotifySyncStore.Entry existing = SpotifySyncStore.get(context, playlist.id);
        if (existing == null || !existing.valid()) {
            importPlaylist(context, playlist, progress, callback);
            return;
        }

        try {
            List<SpotifyTrack> tracks = SpotifyApi.getPlaylistTracks(context, playlist.id);
            syncTracks(context, playlist.id, playlist.name, tracks, progress, callback);
        } catch (Throwable error) {
            callback.onError(error);
        }
    }

    public static void importTracks(
            Context context,
            String key,
            String playlistName,
            List<SpotifyTrack> spotifyTracks,
            Progress progress,
            Callback callback
    ) {
        try {
            Result result = new Result();
            result.playlistName = playlistName;

            int ownerId = VkPlaylistApi.currentUserId();
            if (ownerId == 0) {
                throw new IllegalStateException("VK is not authorized");
            }

            List<Match> matches = matchTracks(spotifyTracks, progress, result);
            if (matches.isEmpty()) {
                throw new IllegalStateException("Не найдено ни одного трека");
            }

            List<MusicTrack> vkTracks = toTracks(matches);
            Playlist created = VkPlaylistApi.createPlaylist(
                    ownerId,
                    playlistName,
                    "Импортировано из Spotify",
                    new ArrayList<>()
            );

            try {
                VkPlaylistApi.addTracks(ownerId, created.a, vkTracks);
            } catch (Throwable addError) {
                try {
                    VkPlaylistApi.deletePlaylist(ownerId, created.a);
                } catch (Throwable ignored) {
                }
                throw addError;
            }

            SpotifySyncStore.Entry entry = new SpotifySyncStore.Entry();
            entry.spotifyId = key;
            entry.name = playlistName;
            entry.vkOwnerId = ownerId;
            entry.vkPlaylistId = created.a;
            entry.lastSync = System.currentTimeMillis();
            for (Match match : matches) {
                entry.tracks.put(match.spotify.id, match.track.y1());
            }
            SpotifySyncStore.put(context, entry);

            result.created = true;
            result.ownerId = ownerId;
            result.playlistId = created.a;
            result.added = vkTracks.size();
            callback.onDone(result);
        } catch (Throwable error) {
            callback.onError(error);
        }
    }

    public static void syncTracks(
            Context context,
            String key,
            String playlistName,
            List<SpotifyTrack> spotifyTracks,
            Progress progress,
            Callback callback
    ) {
        SpotifySyncStore.Entry existing = SpotifySyncStore.get(context, key);
        if (existing == null || !existing.valid()) {
            importTracks(context, key, playlistName, spotifyTracks, progress, callback);
            return;
        }

        try {
            Result result = new Result();
            result.playlistName = playlistName;
            result.ownerId = existing.vkOwnerId;
            result.playlistId = existing.vkPlaylistId;

            int ownerId = existing.vkOwnerId;
            int playlistId = existing.vkPlaylistId;

            List<Match> matches = matchTracks(spotifyTracks, progress, result);
            List<MusicTrack> desired = toTracks(matches);

            Set<String> desiredUids = new HashSet<>();
            for (MusicTrack track : desired) {
                desiredUids.add(track.y1());
            }

            List<MusicTrack> current = VkPlaylistApi.getPlaylistTracks(ownerId, playlistId);
            Set<String> currentUids = new HashSet<>();
            for (MusicTrack track : current) {
                currentUids.add(track.y1());
            }

            List<MusicTrack> toAdd = new ArrayList<>();
            for (MusicTrack track : desired) {
                if (!currentUids.contains(track.y1())) {
                    toAdd.add(track);
                }
            }

            List<MusicTrack> toRemove = new ArrayList<>();
            Set<String> importedUids = new HashSet<>(existing.tracks.values());
            for (MusicTrack track : current) {
                if (importedUids.contains(track.y1()) && !desiredUids.contains(track.y1())) {
                    toRemove.add(track);
                }
            }

            if (!toAdd.isEmpty()) {
                VkPlaylistApi.addTracks(ownerId, playlistId, toAdd);
            }
            if (!toRemove.isEmpty()) {
                VkPlaylistApi.removeTracks(ownerId, playlistId, toRemove);
            }

            Map<String, String> updatedMap = new LinkedHashMap<>();
            for (Match match : matches) {
                updatedMap.put(match.spotify.id, match.track.y1());
            }

            existing.name = playlistName;
            existing.lastSync = System.currentTimeMillis();
            existing.tracks = updatedMap;
            SpotifySyncStore.put(context, existing);

            result.added = toAdd.size();
            result.removed = toRemove.size();
            callback.onDone(result);
        } catch (Throwable error) {
            callback.onError(error);
        }
    }

    private static List<Match> matchTracks(
            List<SpotifyTrack> spotifyTracks,
            Progress progress,
            Result result
    ) throws Exception {
        List<Match> matches = new ArrayList<>();
        int total = spotifyTracks.size();
        int done = 0;

        for (SpotifyTrack spotify : spotifyTracks) {
            done++;
            if (!spotify.usable()) {
                result.notFound++;
                progress.onProgress(done, total, "Пропуск: " + spotify.title);
                continue;
            }

            String query = TrackMatcher.query(spotify.artist, spotify.title);
            List<MusicTrack> found = VkPlaylistApi.search(query, 5);
            TrackMatcher.Result best = TrackMatcher.pickBest(
                    found,
                    spotify.artist,
                    spotify.title,
                    spotify.durationSeconds()
            );

            if (TrackMatcher.acceptable(best)) {
                matches.add(new Match(spotify, best.track, best.score));
                result.matched++;
                if (best.score < 0.80) {
                    result.approximate++;
                }
                progress.onProgress(done, total, spotify.artist + " — " + spotify.title);
            } else {
                result.notFound++;
                progress.onProgress(done, total, "Не найдено: " + spotify.artist + " — " + spotify.title);
            }

            Thread.sleep(SEARCH_DELAY_MS);
        }

        return matches;
    }

    private static List<MusicTrack> toTracks(List<Match> matches) {
        List<MusicTrack> tracks = new ArrayList<>();
        for (Match match : matches) {
            if (match.track != null) {
                tracks.add(match.track);
            }
        }
        return tracks;
    }

    private static final class Match {
        final SpotifyTrack spotify;
        final MusicTrack track;
        final double score;

        Match(SpotifyTrack spotify, MusicTrack track, double score) {
            this.spotify = spotify;
            this.track = track;
            this.score = score;
        }
    }
}
