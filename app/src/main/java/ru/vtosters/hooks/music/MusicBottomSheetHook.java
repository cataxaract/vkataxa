package ru.vtosters.hooks.music;

import android.os.RemoteException;
import bruhcollective.itaysonlab.libvkx.client.LibVKXClientImpl;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;
import com.vk.music.bottomsheets.a.MusicAction;
import com.vk.music.common.MusicPlaybackLaunchContext;
import com.vtosters.lite.R;
import ru.vtosters.lite.core.flags.FeatureFlags;
import ru.vtosters.lite.core.music.MusicRepository;
import ru.vtosters.lite.downloaders.AudioDownloader;
import ru.vtosters.lite.music.repository.MusicRepositoryImpl;
import ru.vtosters.lite.utils.AndroidUtils;
import ru.vtosters.lite.utils.music.VKXUtils;

import java.util.ArrayList;

import static bruhcollective.itaysonlab.libvkx.client.LibVKXClient.*;
import static ru.vtosters.hooks.other.Preferences.milkshake;
import static ru.vtosters.lite.utils.NetworkUtils.isNetworkConnected;

public class MusicBottomSheetHook {

    private static MusicRepository repo() {
        return MusicRepositoryImpl.get();
    }

    public static ArrayList<MusicAction> hook(ArrayList<MusicAction> actions, MusicTrack musicTrack) {
        if (musicTrack.F1()) return actions;

        String trackId = asId(musicTrack);

        if (isVkxInstalled()) actions.add(getPlayInVKXAction());

        if (isIntegrationEnabled()) {
            if (VKXUtils.isVkxCached(trackId)) {
                actions.add(getRemoveCacheTrackVkxAction());
            } else if (isNetworkConnected()) {
                actions.add(addToCacheTrackVkxAction());
            }
        } else {
            if (repo().isCachedTrack(trackId)) {
                actions.add(getRemoveCacheTrackAction());
            } else if (isNetworkConnected()) {
                actions.add(addToCacheTrackAction());
            }
        }

        if (isNetworkConnected()) actions.add(downloadAsMp3Action());

        // TEMP-UI(MU-01): replace the native add/remove action with the locally indexed state.
        // Keep the native action until audio.get has been fetched once, so first-run labels are
        // not wrong for tracks that are already liked.
        if (FeatureFlags.isEnabled(FeatureFlags.Flag.MUSIC_LIKE) && isNetworkConnected()) {
            repo().isLiked(trackId); // kicks the 15 minute audio.get refresh
            if (repo().hasSyncedLikes()) {
                int insertAt = takeLikeActionIndex(actions);
                boolean liked = repo().isLikedNow(trackId);
                MusicAction action = new MusicAction(
                        liked ? R.id.music_action_remove_from_my_music : R.id.music_action_add_to_my_music,
                        liked ? R.drawable.ic_like_24 : R.drawable.ic_like_outline_24,
                        liked ? R.string.music_remove_from_my_music : R.string.music_add_to_my_music,
                        R.color.caption_gray,
                        R.string.music_talkback_empty,
                        false
                );
                if (insertAt >= 0 && insertAt <= actions.size()) {
                    actions.add(insertAt, action);
                } else {
                    actions.add(action);
                }
            }
        }

        return actions;
    }

    private static int takeLikeActionIndex(ArrayList<MusicAction> actions) {
        int insertAt = -1;
        for (int i = actions.size() - 1; i >= 0; i--) {
            int id = actions.get(i).a();
            if (id == R.id.music_action_add_to_my_music
                    || id == R.id.music_action_remove_from_my_music) {
                insertAt = i;
                actions.remove(i);
            }
        }
        return insertAt;
    }


    public static ArrayList<MusicAction> hookDownloadBTN(ArrayList<MusicAction> actions, MusicTrack musicTrack) {
        if (musicTrack.F1()) return actions;

        if (repo().isCachedTrack(musicTrack.y1())) {
            actions.add(new MusicAction(
                    R.id.music_action_toggle_download,
                    milkshake() ? R.drawable.ic_delete_outline_28 : R.drawable.ic_delete_24,
                    R.string.remove_from_cache,
                    musicTrack.F1() ? R.color.music_action_button_gray_50_alpha : R.color.caption_gray,
                    R.string.music_talkback_empty,
                    false
            ));
        } else {
            actions.add(new MusicAction(
                    R.id.music_action_toggle_download,
                    milkshake() ? R.drawable.ic_download_outline_28 : R.drawable.ic_download_24,
                    R.string.add_to_cache,
                    musicTrack.F1() ? R.color.music_action_button_gray_50_alpha : R.color.caption_gray,
                    R.string.music_talkback_empty,
                    false
            ));
        }

        return actions;
    }

    public static ArrayList<MusicAction> hook(ArrayList<MusicAction> actions, Playlist playlist) {
        if (playlist.D) return actions;

        if (isVkxInstalled()) actions.add(getPlayInVKXAction());

        if (isIntegrationEnabled()) {
            if (VKXUtils.isVkxCached(playlist.a, playlist.b)) {
                actions.add(getRemoveCacheTrackVkxAction());
            } else if (isNetworkConnected()) {
                actions.add(addToCacheTrackVkxAction());
            }
        } else {
            if (repo().isCachedPlaylist(playlist.v1())) {
                actions.add(getRemoveCacheTrackAction());
            } else if (isNetworkConnected()) {
                actions.add(addToCacheTrackAction());
            }
        }

        if (isNetworkConnected()) actions.add(downloadAsMp3Action());

        return actions;
    }

    public static boolean injectOnClick(int actionId, MusicTrack track, MusicPlaybackLaunchContext context, Playlist playlist) { // musictrack inj
        // TEMP-UI(MU-01): route the native add/remove action through the deduplicating repository.
        if (FeatureFlags.isEnabled(FeatureFlags.Flag.MUSIC_LIKE) && track != null
                && isNetworkConnected()) {
            if (actionId == R.id.music_action_add_to_my_music) {
                repo().like(track);
                return true;
            }
            if (actionId == R.id.music_action_remove_from_my_music) {
                repo().unlike(track);
                return true;
            }
        }

        if (actionId == R.id.play_in_vkx)
            return tryPlayInVKX(track, context, playlist);

        if (actionId == R.id.remove_from_cache_vkx) {
            getInstance().runOnService(service -> {
                try {
                    service.deleteTrackFromCache(track.d, track.e);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
            return true;
        } else if (actionId == R.id.add_to_cache_vkx) {
            getInstance().runOnService(service -> {
                try {
                    service.addTrackToCache(track.d, track.e, (track.J != null) ? track.J : "");
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            });
            return true;
        }

        if (actionId == R.id.remove_from_cache) {
            repo().removeCachedTrack(asId(track));
            AndroidUtils.sendToast(AndroidUtils.getString("audio_deleted_from_cache"));
            return true;
        } else if (actionId == R.id.add_to_cache) {
            AudioDownloader.cacheTrack(track);
            return true;
        }

        if (actionId == R.id.download_mp3) {
            if (isIntegrationEnabled()) {
                getInstance().runOnService(service -> {
                    try {
                        service.downloadTrack(track.d, track.e, (track.J != null) ? track.J : "");
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                });
            } else {
                AudioDownloader.downloadAudio(track);
            }

            return true;
        }

        return false;
    }

    public static boolean injectOnClick(int actionId, Playlist playlist) { // playlist inj
        if (actionId == R.id.play_in_vkx) {
            return tryPlayInVKX(null, null, playlist);
        }
        
        if (actionId == R.id.remove_from_cache) {
            repo().removeCachedPlaylist(playlist.v1());
            return true;
        } else if (actionId == R.id.add_to_cache) {
            AudioDownloader.cachePlaylist(playlist);
            return true;
        }
        
        if (actionId == R.id.remove_from_cache_vkx) {
            getInstance().runOnService(service -> {
                try {
                    service.deletePlaylistFromCache(playlist.a, playlist.b);
                } catch (RemoteException e) {
                    e.fillInStackTrace();
                }
            });
            return true;
        } else if (actionId == R.id.add_to_cache_vkx) {
            getInstance().runOnService(service -> {
                try {
                    service.addPlaylistToCache(playlist.a, playlist.b, (playlist.Q != null) ? playlist.Q : "");
                } catch (RemoteException e) {
                    e.fillInStackTrace();
                }
            });
            return true;
        }

        if (actionId == R.id.download_mp3) {
            if (isIntegrationEnabled()) {
                getInstance().runOnService(service -> {
                    try {
                        service.downloadPlaylist(playlist.a, playlist.b, (playlist.Q != null) ? playlist.Q : "");
                    } catch (RemoteException e) {
                        e.fillInStackTrace();
                    }
                });
            } else {
                AudioDownloader.downloadPlaylist(playlist);
            }
            return true;
        }

        return false;
    }

    public static boolean tryPlayInVKX(MusicTrack track, MusicPlaybackLaunchContext context, Playlist playlist) {
        var trackList = new ArrayList<MusicTrack>();

        if (playlist != null) {
            trackList.addAll(playlist.R);
        } else {
            trackList.add(track);
        }

        LibVKXClientImpl.LibVKXAction action = iLibVkxService -> lambdaplay(trackList, track, iLibVkxService, context);
        return getInstance().runOnService(action);
    }

    private static MusicAction getPlayInVKXAction() {
        return new MusicAction(
                R.id.play_in_vkx,
                milkshake() ? R.drawable.ic_music_outline_24 : R.drawable.ic_music_24,
                R.string.play_in_vkx,
                R.color.caption_gray,
                R.string.music_talkback_empty,
                false
        );
    }

    private static MusicAction getRemoveCacheTrackAction() {
        return new MusicAction(
                R.id.remove_from_cache,
                milkshake() ? R.drawable.ic_delete_outline_28 : R.drawable.ic_delete_24,
                R.string.remove_from_cache,
                R.color.caption_gray,
                R.string.music_talkback_empty,
                false
        );
    }

    private static MusicAction addToCacheTrackAction() {
        return new MusicAction(
                R.id.add_to_cache,
                milkshake() ? R.drawable.ic_add_outline_28 : R.drawable.ic_add_24,
                R.string.add_to_cache,
                R.color.caption_gray,
                R.string.music_talkback_empty,
                false
        );
    }

    private static MusicAction getRemoveCacheTrackVkxAction() {
        return new MusicAction(
                R.id.remove_from_cache_vkx,
                milkshake() ? R.drawable.ic_delete_outline_28 : R.drawable.ic_delete_24,
                R.string.remove_from_cache_vkx,
                R.color.caption_gray,
                R.string.music_talkback_empty,
                false
        );
    }

    private static MusicAction addToCacheTrackVkxAction() {
        return new MusicAction(
                R.id.add_to_cache_vkx,
                milkshake() ? R.drawable.ic_add_outline_28 : R.drawable.ic_add_24,
                R.string.add_to_cache_vkx,
                R.color.caption_gray,
                R.string.music_talkback_empty,
                false
        );
    }

    private static MusicAction downloadAsMp3Action() {
        return new MusicAction(
                R.id.download_mp3,
                milkshake() ? R.drawable.ic_download_outline_28 : R.drawable.ic_download_24,
                R.string.download_mp3,
                R.color.caption_gray,
                R.string.music_talkback_empty,
                false
        );
    }
}