package ru.vtosters.lite.music.wave;

import android.content.Context;

import com.vk.api.audio.AudioGet;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;
import com.vk.music.player.PlayerModel;

import java.util.List;

import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.lite.utils.AccountManagerUtils;

public final class WaveRepository {
    private static final VkRecommendationSource VK = new VkRecommendationSource();
    private static final LastFmRecommendationSource LASTFM = new LastFmRecommendationSource();

    private WaveRepository() {
    }

    public interface Callback {
        void onSuccess(List<MusicTrack> tracks, String source);

        void onError(Throwable error);
    }

    public static void load(Context context, PlayerModel player, String mode, int limit, boolean force, Callback callback) {
        String preference = Preferences.getPreferences().getString("wave_source", "auto");
        boolean lastFm = "lastfm".equals(mode) || "lastfm".equals(preference);

        if (!force) {
            List<MusicTrack> cached = WaveCache.load(context, cacheKey(mode));
            if (cached != null) {
                callback.onSuccess(cached, WaveCache.loadSource(context, cacheKey(mode)));
                return;
            }
        }

        MusicTrack seed = player == null ? null : player.S();
        if (lastFm) {
            loadLastFm(context, seed, mode, limit, callback);
        } else {
            loadVk(context, seed, mode, limit, true, callback);
        }
    }

    private static String cacheKey(String mode) {
        return mode + "_" + AccountManagerUtils.getUserId();
    }

    private static void loadVk(
            Context context,
            MusicTrack seed,
            String mode,
            int limit,
            boolean mayUseRandomSeed,
            Callback callback
    ) {
        VK.load(seed, limit, new RecommendationSource.Callback() {
            @Override
            public void onSuccess(List<MusicTrack> tracks) {
                if (tracks != null && !tracks.isEmpty()) {
                    WaveCache.save(context, cacheKey(mode), "VK", tracks);
                    callback.onSuccess(tracks, "VK");
                    return;
                }

                if (mayUseRandomSeed) {
                    randomSeed(context, seed, mode, limit, callback);
                } else {
                    loadLastFm(context, seed, mode, limit, callback);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (mayUseRandomSeed) {
                    randomSeed(context, seed, mode, limit, callback);
                } else {
                    loadLastFm(context, seed, mode, limit, callback);
                }
            }
        });
    }

    private static void randomSeed(Context context, MusicTrack seed, String mode, int limit, Callback callback) {
        if (seed != null) {
            loadVk(context, seed, mode, limit, false, callback);
            return;
        }

        int userId = AccountManagerUtils.getUserId();
        if (userId == 0) {
            callback.onError(new IllegalStateException("Not logged in"));
            return;
        }

        new AudioGet(userId)
                .p()
                .b(1)
                .a(new ApiCallback<List<MusicTrack>>() {
                    @Override
                    public void a(List<MusicTrack> tracks) {
                        MusicTrack random = tracks == null || tracks.isEmpty() ? null : tracks.get(0);
                        if (random == null) {
                            callback.onError(new IllegalStateException("No audio for seed"));
                        } else {
                            loadVk(context, random, mode, limit, false, callback);
                        }
                    }

                    @Override
                    public void a(VKApiExecutionException error) {
                        callback.onError(error);
                    }
                })
                .a();
    }

    private static void loadLastFm(Context context, MusicTrack seed, String mode, int limit, Callback callback) {
        if (seed == null) {
            randomSeedForLastFm(context, mode, limit, callback);
            return;
        }

        LASTFM.load(seed, limit, new RecommendationSource.Callback() {
            @Override
            public void onSuccess(List<MusicTrack> tracks) {
                if (tracks == null || tracks.isEmpty()) {
                    callback.onError(new IllegalStateException("No recommendations"));
                } else {
                    WaveCache.save(context, cacheKey(mode), "Last.fm", tracks);
                    callback.onSuccess(tracks, "Last.fm");
                }
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    private static void randomSeedForLastFm(Context context, String mode, int limit, Callback callback) {
        int userId = AccountManagerUtils.getUserId();
        if (userId == 0) {
            callback.onError(new IllegalStateException("Not logged in"));
            return;
        }

        new AudioGet(userId)
                .p()
                .b(1)
                .a(new ApiCallback<List<MusicTrack>>() {
                    @Override
                    public void a(List<MusicTrack> tracks) {
                        MusicTrack random = tracks == null || tracks.isEmpty() ? null : tracks.get(0);
                        if (random == null) {
                            callback.onError(new IllegalStateException("No audio for seed"));
                        } else {
                            loadLastFm(context, random, mode, limit, callback);
                        }
                    }

                    @Override
                    public void a(VKApiExecutionException error) {
                        callback.onError(error);
                    }
                })
                .a();
    }
}
