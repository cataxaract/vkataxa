package ru.vtosters.lite.music.wave;

import com.vk.api.audio.AudioGetRecommendations;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;

import java.util.ArrayList;
import java.util.List;

final class VkRecommendationSource implements RecommendationSource {
    @Override
    public void load(MusicTrack seed, int limit, Callback callback) {
        String target = seed == null ? "" : seed.y1();

        new AudioGetRecommendations(limit, target)
                .a(new ApiCallback<List<MusicTrack>>() {
                    @Override
                    public void a(List<MusicTrack> tracks) {
                        callback.onSuccess(tracks == null ? new ArrayList<>() : tracks);
                    }

                    @Override
                    public void a(VKApiExecutionException error) {
                        callback.onError(error);
                    }
                })
                .a();
    }
}
