package ru.vtosters.lite.music.wave;

import com.vk.dto.music.MusicTrack;
import ru.vtosters.lite.utils.music.MusicTrackUtils;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TrackMatcher {
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "feat", "ft", "featuring", "remaster", "remastered", "version", "edit",
            "radio", "mix", "live", "extended", "original", "prod", "remix",
            "deluxe", "explicit", "album", "single"
    ));

    private TrackMatcher() {
    }

    public static final class Result {
        public final MusicTrack track;
        public final double score;

        Result(MusicTrack track, double score) {
            this.track = track;
            this.score = score;
        }
    }

    public static String query(String artist, String title) {
        String cleanArtist = cleanQueryPart(artist);
        String cleanTitle = cleanQueryPart(title);
        if (cleanArtist.isEmpty()) {
            return cleanTitle;
        }
        if (cleanTitle.isEmpty()) {
            return cleanArtist;
        }
        return cleanArtist + " " + cleanTitle;
    }

    public static String titleQuery(String title) {
        return cleanQueryPart(title);
    }

    private static String cleanQueryPart(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static Result pickBest(List<MusicTrack> tracks, String artist, String title, int durationSeconds) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }

        Result best = null;
        for (MusicTrack track : tracks) {
            if (track == null || track.f == null) {
                continue;
            }
            double score = score(artist, title, durationSeconds, track);
            if (best == null || score > best.score) {
                best = new Result(track, score);
            }
        }
        return best;
    }

    public static double score(String artist, String title, int durationSeconds, MusicTrack track) {
        String wantedArtist = normalize(artist);
        String wantedTitle = normalize(title);
        String foundArtist = normalize(MusicTrackUtils.getArtists(track));
        String foundTitle = normalize(track.f);

        double artistScore = similarity(wantedArtist, foundArtist);
        double titleScore = similarity(wantedTitle, foundTitle);
        double durationScore = durationScore(durationSeconds, track.h);

        return artistScore * 0.35 + titleScore * 0.45 + durationScore * 0.20;
    }

    public static boolean acceptable(Result result) {
        return result != null && result.score >= 0.62;
    }

    private static double durationScore(int expected, int actual) {
        if (expected <= 0 || actual <= 0) {
            return 0.5;
        }
        int delta = Math.abs(expected - actual);
        if (delta <= 3) {
            return 1.0;
        }
        if (delta >= 30) {
            return 0.0;
        }
        return 1.0 - (delta / 30.0);
    }

    private static double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        if (left.contains(right) || right.contains(left)) {
            return 0.82;
        }

        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);

        double jaccard = (double) intersection.size() / union.size();
        if (jaccard >= 0.75) {
            return 0.72;
        }
        return jaccard * 0.65;
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : value.split(" ")) {
            if (!token.isEmpty() && !NOISE.contains(token) && token.length() > 1) {
                result.add(token);
            }
        }
        return result;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String result = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();

        return result.replaceAll("\\s+", " ");
    }
}
