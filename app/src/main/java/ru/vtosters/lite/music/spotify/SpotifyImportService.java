package ru.vtosters.lite.music.spotify;

import android.content.Context;
import android.util.Log;

import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.vtosters.lite.core.log.VtLog;
import ru.vtosters.lite.music.wave.TrackMatcher;
import ru.vtosters.lite.utils.CaptchaImageLoader;
import ru.vtosters.lite.utils.music.MusicTrackUtils;

public final class SpotifyImportService {
    private static final String TAG = "VKataxaImport";
    private static final int SEARCH_COUNT = 10;
    private static final int MATCH_BATCH = 10;

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
        public int skipped;
        public int failed;
        public boolean created;
        public boolean localImport;
        public String playlistName;
        public int ownerId;
        public int playlistId;
        public final List<String> notFoundTracks = new ArrayList<>();

        public String summary() {
            if (localImport) {
                StringBuilder builder = new StringBuilder("Добавлено в «Мою музыку»: ").append(added);
                if (skipped > 0) {
                    builder.append(", уже было: ").append(skipped);
                }
                if (notFound > 0) {
                    builder.append(", не найдено: ").append(notFound);
                }
                if (failed > 0) {
                    builder.append(", ошибок: ").append(failed);
                }
                return builder.toString();
            }

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
        CaptchaImageLoader.setAutoCancelPlaceholder(true);
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
            logImportError("import", error);
            callback.onError(error);
        } finally {
            CaptchaImageLoader.setAutoCancelPlaceholder(false);
        }
    }

    public static void importLocalMusic(
            Context context,
            List<SpotifyTrack> localTracks,
            boolean onlyNew,
            Progress progress,
            Callback callback
    ) {
        CaptchaImageLoader.setAutoCancelPlaceholder(true);
        try {
            Result result = new Result();
            result.localImport = true;

            progress.onProgress(0, localTracks.size(), "Сверяем с «Моей музыкой»...");
            List<MusicTrack> myMusic = VkPlaylistApi.getMyMusic();
            Set<String> existing = new HashSet<>();
            Map<String, List<MusicTrack>> byTitle = new HashMap<>();
            for (MusicTrack track : myMusic) {
                existing.add(track.y1());
                if (track.f != null) {
                    byTitle.computeIfAbsent(TrackMatcher.normalize(track.f), key -> new ArrayList<>())
                            .add(track);
                }
            }

            List<Match> matches = new ArrayList<>();
            List<SpotifyTrack> toSearch = new ArrayList<>();
            for (SpotifyTrack local : localTracks) {
                TrackMatcher.Result already = TrackMatcher.pickBest(
                        byTitle.get(TrackMatcher.normalize(local.title)),
                        local.artist,
                        local.title,
                        local.durationSeconds()
                );
                if (TrackMatcher.acceptable(already)) {
                    matches.add(new Match(local, already.track, already.score));
                } else {
                    toSearch.add(local);
                }
            }

            if (!toSearch.isEmpty()) {
                Result searchResult = new Result();
                matches.addAll(matchTracks(toSearch, progress, searchResult));
                result.notFound = searchResult.notFound;
                result.notFoundTracks.addAll(searchResult.notFoundTracks);
            }
            result.matched = matches.size();

            List<MusicTrack> toAdd = new ArrayList<>();
            for (Match match : matches) {
                if (onlyNew && existing.contains(match.track.y1())) {
                    result.skipped++;
                } else {
                    toAdd.add(match.track);
                }
            }

            int added = 0;
            int processed = 0;
            for (MusicTrack track : toAdd) {
                progress.onProgress(processed, toAdd.size(),
                        "Добавляем в «Мою музыку»: " + MusicTrackUtils.getArtists(track) + " — " + track.f);
                try {
                    VkPlaylistApi.addToMyMusic(track);
                    added++;
                } catch (Throwable error) {
                    if (isCaptchaError(error)) {
                        throw error;
                    }
                    result.failed++;
                }
                processed++;
            }

            result.added = added;
            Log.d(TAG, "local import: added=" + result.added + " skipped=" + result.skipped
                    + " failed=" + result.failed + " notFound=" + result.notFound);
            callback.onDone(result);
        } catch (Throwable error) {
            logImportError("local", error);
            callback.onError(error);
        } finally {
            CaptchaImageLoader.setAutoCancelPlaceholder(false);
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

        CaptchaImageLoader.setAutoCancelPlaceholder(true);
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
            logImportError("sync", error);
            callback.onError(error);
        } finally {
            CaptchaImageLoader.setAutoCancelPlaceholder(false);
        }
    }

    private static List<Match> matchTracks(
            List<SpotifyTrack> spotifyTracks,
            Progress progress,
            Result result
    ) throws Exception {
        int total = spotifyTracks.size();
        Match[] foundMatches = new Match[total];
        List<Integer> pending = new ArrayList<>();

        int done = 0;
        for (int index = 0; index < total; index++) {
            SpotifyTrack spotify = spotifyTracks.get(index);
            if (spotify.usable()) {
                pending.add(index);
            } else {
                result.notFound++;
                result.notFoundTracks.add(label(spotify));
                done++;
                progress.onProgress(done, total, "Пропуск: " + spotify.title);
            }
        }

        for (int start = 0; start < pending.size(); start += MATCH_BATCH) {
            int end = Math.min(start + MATCH_BATCH, pending.size());
            List<Integer> chunk = pending.subList(start, end);

            List<String> queries = new ArrayList<>(chunk.size());
            for (Integer index : chunk) {
                SpotifyTrack spotify = spotifyTracks.get(index);
                queries.add(TrackMatcher.query(spotify.artist, spotify.title));
            }

            List<List<MusicTrack>> found = searchWithCaptchaRetry(queries, done, total, progress);
            List<Integer> misses = new ArrayList<>();

            for (int position = 0; position < chunk.size(); position++) {
                int index = chunk.get(position);
                SpotifyTrack spotify = spotifyTracks.get(index);
                List<MusicTrack> tracks = position < found.size() ? found.get(position) : null;
                TrackMatcher.Result best = TrackMatcher.pickBest(
                        tracks,
                        spotify.artist,
                        spotify.title,
                        spotify.durationSeconds()
                );
                if (TrackMatcher.acceptable(best)) {
                    foundMatches[index] = new Match(spotify, best.track, best.score);
                } else {
                    misses.add(index);
                }
            }

            if (!misses.isEmpty()) {
                List<Integer> fallbackIndexes = new ArrayList<>(misses.size());
                List<String> fallbackQueries = new ArrayList<>(misses.size());
                for (Integer index : misses) {
                    SpotifyTrack spotify = spotifyTracks.get(index);
                    String primary = TrackMatcher.query(spotify.artist, spotify.title);
                    String fallback = TrackMatcher.titleQuery(spotify.title);
                    if (!fallback.isEmpty() && !fallback.equals(primary)) {
                        fallbackIndexes.add(index);
                        fallbackQueries.add(fallback);
                    }
                }

                if (!fallbackQueries.isEmpty()) {
                    List<List<MusicTrack>> fallback = searchWithCaptchaRetry(
                            fallbackQueries,
                            done,
                            total,
                            progress
                    );
                    for (int position = 0; position < fallbackIndexes.size(); position++) {
                        int index = fallbackIndexes.get(position);
                        SpotifyTrack spotify = spotifyTracks.get(index);
                        List<MusicTrack> tracks = position < fallback.size() ? fallback.get(position) : null;
                        TrackMatcher.Result best = TrackMatcher.pickBest(
                                tracks,
                                spotify.artist,
                                spotify.title,
                                spotify.durationSeconds()
                        );
                        if (TrackMatcher.acceptable(best)) {
                            foundMatches[index] = new Match(spotify, best.track, best.score);
                        }
                    }
                }
            }

            for (Integer index : chunk) {
                SpotifyTrack spotify = spotifyTracks.get(index);
                Match match = foundMatches[index];
                done++;
                if (match != null) {
                    result.matched++;
                    if (match.score < 0.80) {
                        result.approximate++;
                    }
                    progress.onProgress(done, total, spotify.artist + " — " + spotify.title);
                } else {
                    result.notFound++;
                    result.notFoundTracks.add(label(spotify));
                    progress.onProgress(done, total, "Не найдено: " + spotify.artist + " — " + spotify.title);
                }
            }
        }

        List<Match> matches = new ArrayList<>(result.matched);
        for (Match match : foundMatches) {
            if (match != null) {
                matches.add(match);
            }
        }
        return matches;
    }

    private static List<List<MusicTrack>> searchWithCaptchaRetry(
            List<String> queries,
            int done,
            int total,
            Progress progress
    ) throws Exception {
        // MU-10: no automatic captcha retries; the caller stops and reports the partial state.
        return VkPlaylistApi.searchBatch(queries, SEARCH_COUNT);
    }

    private static boolean isCaptchaError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof VKApiExecutionException
                    && ((VKApiExecutionException) current).d() == 14) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Can't confirm validation")
                    || message.contains("Captcha needed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void logImportError(String source, Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof VKApiExecutionException) {
                VtLog.e(TAG, source + " stopped: code=" + ((VKApiExecutionException) current).d()
                        + " msg=" + current.getMessage());
                return;
            }
            current = current.getCause();
        }
        VtLog.e(TAG, source + " stopped: " + error);
    }

    private static String label(SpotifyTrack track) {
        if (track == null) {
            return "";
        }
        if (track.artist == null || track.artist.isEmpty()) {
            return track.title == null ? "" : track.title;
        }
        return track.artist + " — " + track.title;
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
