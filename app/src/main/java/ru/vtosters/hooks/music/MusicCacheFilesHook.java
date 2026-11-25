package ru.vtosters.hooks.music;

import ru.vtosters.lite.core.guard.HookGuard;
import ru.vtosters.lite.music.cache.MusicCacheImpl;
import ru.vtosters.lite.utils.music.MusicCacheStorageUtils;

import java.io.File;

public class MusicCacheFilesHook {
    public static File getTrackFile(String trackId) {
        return HookGuard.safe("MusicCache", null, () -> MusicCacheStorageUtils.getTrackFile(trackId));
    }

    public static boolean isTrackExist(String trackId) {
        return HookGuard.safe("MusicCache", false, () -> MusicCacheImpl.isCachedTrack(trackId));
    }
}
