package ru.vtosters.lite.music.wave;

import com.vk.dto.music.MusicTrack;

import java.util.List;

interface RecommendationSource {
    void load(MusicTrack seed, int limit, Callback callback);

    interface Callback {
        void onSuccess(List<MusicTrack> tracks);

        void onError(Throwable error);
    }
}
