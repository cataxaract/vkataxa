package ru.vtosters.lite.music.wave;

import android.os.Handler;
import android.os.Looper;

import com.vk.dto.music.MusicTrack;
import ru.vtosters.lite.utils.music.MusicTrackUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LastFmRecommendationSource implements RecommendationSource {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_SEARCHES = 12;

    @Override
    public void load(MusicTrack seed, int limit, Callback callback) {
        if (seed == null) {
            callback.onError(new IllegalArgumentException("seed is required"));
            return;
        }

        String artist = MusicTrackUtils.getArtists(seed);
        if (artist.isEmpty() || seed.f == null || seed.f.isEmpty()) {
            callback.onError(new IllegalArgumentException("seed has no artist or title"));
            return;
        }

        LastFmClient.getSimilarTracks(artist, seed.f, Math.min(limit, 30), new LastFmClient.Callback() {
            @Override
            public void onSuccess(List<LastFmClient.SimilarTrack> tracks) {
                if (tracks.isEmpty()) {
                    callback.onError(new IllegalStateException("Last.fm returned no similar tracks"));
                    return;
                }

                List<LastFmClient.SimilarTrack> wanted = tracks.subList(0, Math.min(tracks.size(), MAX_SEARCHES));
                List<MusicTrack> result = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                search(wanted, 0, result, seen, callback);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    private void search(
            List<LastFmClient.SimilarTrack> wanted,
            int index,
            List<MusicTrack> result,
            Set<String> seen,
            Callback callback
    ) {
        if (index >= wanted.size()) {
            if (result.isEmpty()) {
                callback.onError(new IllegalStateException("No similar tracks were found in VK"));
            } else {
                callback.onSuccess(result);
            }
            return;
        }

        LastFmClient.SimilarTrack similar = wanted.get(index);
        VkTrackSearch.search(TrackMatcher.query(similar.artist, similar.title), 3, new VkTrackSearch.Callback() {
            @Override
            public void onSuccess(List<MusicTrack> tracks) {
                TrackMatcher.Result best = TrackMatcher.pickBest(
                        tracks,
                        similar.artist,
                        similar.title,
                        similar.duration
                );
                if (TrackMatcher.acceptable(best) && seen.add(best.track.A1())) {
                    result.add(best.track);
                }
                MAIN.postDelayed(() -> search(wanted, index + 1, result, seen, callback), 120);
            }

            @Override
            public void onError(Throwable error) {
                MAIN.postDelayed(() -> search(wanted, index + 1, result, seen, callback), 120);
            }
        });
    }
}
