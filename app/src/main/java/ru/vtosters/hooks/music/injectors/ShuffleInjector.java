package ru.vtosters.hooks.music.injectors;

import com.vk.dto.music.MusicTrack;
import com.vk.music.model.IpcPlayerModelImpl;
import com.vk.music.model.PlayerModelImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.vtosters.lite.core.music.MusicRepository;
import ru.vtosters.lite.music.repository.MusicRepositoryImpl;

public class ShuffleInjector {

    private static MusicRepository repo() {
        return MusicRepositoryImpl.get();
    }
    public static void shuffleTracks(IpcPlayerModelImpl ipcPlayerModel) {
        ipcPlayerModel.a(getShuffledList());
    }

    public static void shuffleTracks(PlayerModelImpl playerModelImpl) {
        playerModelImpl.a(getShuffledList());
    }

    private static List<MusicTrack> getShuffledList() {
        List<MusicTrack> shuffled = new ArrayList<>(repo().cachedTracks().get());
        Collections.shuffle(shuffled);
        return shuffled;
    }
}
