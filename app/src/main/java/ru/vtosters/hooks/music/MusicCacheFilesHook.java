package ru.vtosters.hooks.music;

import ru.vtosters.lite.core.guard.HookGuard;
import ru.vtosters.lite.core.music.MusicRepository;
import ru.vtosters.lite.music.repository.MusicRepositoryImpl;
import ru.vtosters.lite.utils.music.MusicCacheStorageUtils;

import java.io.File;

public class MusicCacheFilesHook {

    private static MusicRepository repo() {
        return MusicRepositoryImpl.get();
    }

    public static File getTrackFile(String trackId) {
        return HookGuard.safe("MusicCache", null, () -> MusicCacheStorageUtils.getTrackFile(trackId));
    }

    public static boolean isTrackExist(String trackId) {
        return HookGuard.safe("MusicCache", false, () -> repo().isCachedTrack(trackId));
    }
}
