package ru.vtosters.lite.music.wave;

import com.vk.api.audio.AudioSearch;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;

import java.util.ArrayList;
import java.util.List;

final class VkTrackSearch {
    private VkTrackSearch() {
    }

    interface Callback {
        void onSuccess(List<MusicTrack> tracks);

        void onError(Throwable error);
    }

    static void search(String query, int count, Callback callback) {
        new AudioSearch(query, false, false, 0, count, 0)
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
