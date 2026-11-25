// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.bulk;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.vk.api.audio.AudioGetPlaylists;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.core.dialogs.alert.VkAlertDialog;
import com.vk.dto.music.MusicTrack;
import com.vk.dto.music.Playlist;
import com.vk.dto.music.UserPlaylists;
import com.vtosters.lite.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ru.vtosters.lite.concurrent.VTExecutors;
import ru.vtosters.lite.core.flags.FeatureFlags;
import ru.vtosters.lite.core.log.VtLog;
import ru.vtosters.lite.music.repository.MusicBulkService;
import ru.vtosters.lite.music.repository.MusicRepositoryImpl;
import ru.vtosters.lite.utils.AccountManagerUtils;
import ru.vtosters.lite.utils.AndroidUtils;
import ru.vtosters.lite.utils.music.MusicTrackUtils;

/**
 * TEMP-UI(MU-02): long-press on a music row opens a multi-choice dialog; the action panel is a
 * normal dialog and the running queue has its own progress dialog. Remove after the redesign.
 */
public final class MusicBulkController {

    private static final String TAG = "MusicBulk";
    private static final int MAX_TRACKS_IN_DIALOG = 300;

    private static final Set<RecyclerView> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> LISTENING =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> SCAN_SCHEDULED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> ROWS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MusicBulkController() {
    }

    public static void onActivityResumed(Activity activity) {
        if (activity == null || !FeatureFlags.isEnabled(FeatureFlags.Flag.MUSIC_BULK)) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        synchronized (LISTENING) {
            if (!LISTENING.add(decor)) {
                return;
            }
        }

        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        scheduleScan(decor);
                    }
                });
        scheduleScan(decor);
    }

    private static void scheduleScan(View decor) {
        synchronized (SCAN_SCHEDULED) {
            if (!SCAN_SCHEDULED.add(decor)) {
                return;
            }
        }
        decor.postDelayed(() -> {
            synchronized (SCAN_SCHEDULED) {
                SCAN_SCHEDULED.remove(decor);
            }
            scan(decor);
        }, 300L);
    }

    private static void scan(View view) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RecyclerView) {
                install((RecyclerView) child);
            }
            scan(child);
        }
    }

    private static void install(RecyclerView recyclerView) {
        if (INSTALLED.add(recyclerView)) {
            recyclerView.addOnChildAttachStateChangeListener(
                    new RecyclerView.OnChildAttachStateChangeListener() {
                        @Override
                        public void onChildViewAttachedToWindow(View view) {
                            installRow(recyclerView, view);
                        }

                        @Override
                        public void onChildViewDetachedFromWindow(View view) {
                        }
                    });
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            installRow(recyclerView, recyclerView.getChildAt(i));
        }
    }

    private static void installRow(RecyclerView recyclerView, View row) {
        if (row == null) {
            return;
        }
        View title = row.findViewById(R.id.audio_title);
        if (title == null) {
            return;
        }

        RecyclerView owner = closestRecyclerView(title);
        if (owner == null) {
            return;
        }

        View ownerRow = title;
        android.view.ViewParent parent = title.getParent();
        while (parent instanceof View && parent != owner) {
            ownerRow = (View) parent;
            parent = parent.getParent();
        }
        if (parent != owner) {
            return;
        }
        final View targetRow = ownerRow;
        if (!ROWS.add(targetRow)) {
            return;
        }

        View.OnLongClickListener listener = view -> {
            Activity activity = findActivity(view.getContext());
            if (activity == null) {
                return false;
            }
            int position = owner.getChildAdapterPosition(targetRow);
            openSelection(activity, owner, position);
            return true;
        };

        View current = title;
        while (current != null) {
            current.setOnLongClickListener(listener);
            if (current == targetRow) {
                break;
            }
            android.view.ViewParent next = current.getParent();
            current = next instanceof View ? (View) next : null;
        }
    }

    private static RecyclerView closestRecyclerView(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof RecyclerView) {
                return (RecyclerView) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static void openSelection(Activity activity, RecyclerView recyclerView, int position) {
        List<MusicTrack> tracks = MusicTrackResolver.tracks(recyclerView);
        if (tracks.isEmpty()) {
            return;
        }

        String truncated = null;
        List<MusicTrack> visibleTracks = tracks;
        if (tracks.size() > MAX_TRACKS_IN_DIALOG) {
            visibleTracks = new ArrayList<>(tracks.subList(0, MAX_TRACKS_IN_DIALOG));
            truncated = "Показаны первые " + MAX_TRACKS_IN_DIALOG + " треков";
        }

        String[] titles = new String[visibleTracks.size()];
        boolean[] checked = new boolean[visibleTracks.size()];
        MusicTrack pressed = MusicTrackResolver.trackAt(recyclerView, position);
        String pressedId = pressed == null ? null : pressed.y1();
        for (int i = 0; i < visibleTracks.size(); i++) {
            MusicTrack track = visibleTracks.get(i);
            titles[i] = titleOf(track);
            checked[i] = pressedId != null && pressedId.equals(track.y1());
        }

        List<MusicTrack> selectedTracks = visibleTracks;
        String finalTruncated = truncated;
        AlertDialog dialog = new VkAlertDialog.Builder(activity)
                .setTitle("Выбрать треки")
                .setMultiChoiceItems(titles, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Далее", null)
                .setNeutralButton("Все", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<MusicTrack> selected = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) {
                        selected.add(selectedTracks.get(i));
                    }
                }
                if (selected.isEmpty()) {
                    AndroidUtils.sendToast("Ничего не выбрано");
                    return;
                }
                dialog.dismiss();
                showActions(activity, selected);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = true;
                    dialog.getListView().setItemChecked(i, true);
                }
            });
        });
        dialog.show();
        if (finalTruncated != null) {
            AndroidUtils.sendToast(finalTruncated);
        }
    }

    private static String titleOf(MusicTrack track) {
        if (track == null) {
            return "";
        }
        String title = track.f == null ? "" : track.f;
        String artist = "";
        try {
            artist = MusicTrackUtils.getArtists(track);
        } catch (Throwable ignored) {
            // keep title only
        }
        return artist == null || artist.isEmpty() ? title : artist + " — " + title;
    }

    private static void showActions(Activity activity, List<MusicTrack> tracks) {
        new VkAlertDialog.Builder(activity)
                .setTitle("Выбрано: " + tracks.size())
                .setItems(new String[]{
                        "Добавить в плейлист",
                        "Удалить из моей музыки",
                        "Скачать",
                        "Добавить в кэш"
                }, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            choosePlaylist(activity, tracks);
                            break;
                        case 1:
                            confirmRemove(activity, tracks);
                            break;
                        case 2:
                            runJob(activity, tracks, MusicBulkService.Action.DOWNLOAD, null);
                            break;
                        case 3:
                        default:
                            runJob(activity, tracks, MusicBulkService.Action.CACHE, null);
                            break;
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void confirmRemove(Activity activity, List<MusicTrack> tracks) {
        new VkAlertDialog.Builder(activity)
                .setTitle("Удаление из моей музыки")
                .setMessage("Удалить " + tracks.size() + " треков?")
                .setPositiveButton("Удалить", (dialog, which) ->
                        runJob(activity, tracks, MusicBulkService.Action.REMOVE_FROM_MY_MUSIC, null))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void choosePlaylist(Activity activity, List<MusicTrack> tracks) {
        AlertDialog loading = new VkAlertDialog.Builder(activity)
                .setTitle("Плейлисты")
                .setMessage("Загрузка…")
                .setNegativeButton("Отмена", null)
                .create();
        loading.show();

        VTExecutors.getIoExecutor().execute(() -> {
            List<Playlist> playlists = new ArrayList<>();
            try {
                playlists.addAll(loadUserPlaylistsSync());
            } catch (Throwable t) {
                VtLog.w(TAG, "playlist list failed: " + t);
            }
            if (playlists.isEmpty()) {
                playlists.addAll(MusicRepositoryImpl.get().cachedPlaylistsNow());
            }

            List<Playlist> result = playlists;
            MAIN.post(() -> {
                loading.dismiss();
                if (result.isEmpty()) {
                    AndroidUtils.sendToast("Плейлисты не найдены");
                    return;
                }
                showPlaylistList(activity, result, tracks);
            });
        });
    }

    private static void showPlaylistList(Activity activity, List<Playlist> playlists, List<MusicTrack> tracks) {
        String[] titles = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            titles[i] = playlists.get(i).g;
        }
        new VkAlertDialog.Builder(activity)
                .setTitle("Добавить в плейлист")
                .setItems(titles, (dialog, which) -> {
                    Playlist playlist = playlists.get(which);
                    runJob(activity, tracks, MusicBulkService.Action.ADD_TO_PLAYLIST, playlist.v1());
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static List<Playlist> loadUserPlaylistsSync() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UserPlaylists> result = new AtomicReference<>();
        AtomicReference<VKApiExecutionException> error = new AtomicReference<>();

        new AudioGetPlaylists.b(AccountManagerUtils.getUserId())
                .a()
                .a(new ApiCallback<UserPlaylists>() {
                    @Override
                    public void a(UserPlaylists value) {
                        result.set(value);
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();

        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("VK API timeout");
        }
        if (error.get() != null) {
            throw error.get();
        }
        UserPlaylists value = result.get();
        return value == null ? Collections.emptyList() : value.b();
    }

    private static void runJob(Activity activity, List<MusicTrack> tracks,
                               MusicBulkService.Action action, String playlistId) {
        if (MusicBulkService.isRunning()) {
            AndroidUtils.sendToast("Массовая операция уже выполняется");
            return;
        }

        AlertDialog progress = new VkAlertDialog.Builder(activity)
                .setTitle(titleFor(action))
                .setMessage("0 из " + tracks.size())
                .setNegativeButton("Отмена", (dialog, which) -> MusicBulkService.cancel())
                .setCancelable(false)
                .create();
        progress.show();

        boolean started = MusicBulkService.start(tracks, action, playlistId,
                new MusicBulkService.Listener() {
                    @Override
                    public void onProgress(int done, int total) {
                        progress.setMessage(done + " из " + total);
                    }

                    @Override
                    public void onFinished(int ok, int failed, boolean canceled, boolean captcha) {
                        progress.dismiss();
                        if (captcha) {
                            AndroidUtils.sendToast(
                                    "VK ограничил операцию (captcha). Остановлено: " + ok + " из " + tracks.size());
                        } else if (canceled) {
                            AndroidUtils.sendToast("Отменено: " + ok + " из " + tracks.size());
                        } else if (failed > 0) {
                            AndroidUtils.sendToast("Готово: " + ok + ", ошибок: " + failed);
                        } else {
                            AndroidUtils.sendToast("Готово: " + ok + " из " + tracks.size());
                        }
                    }
                });

        if (!started) {
            progress.dismiss();
            AndroidUtils.sendToast("Не удалось запустить операцию");
        }
    }

    private static String titleFor(MusicBulkService.Action action) {
        switch (action) {
            case ADD_TO_PLAYLIST:
                return "Добавление в плейлист";
            case REMOVE_FROM_MY_MUSIC:
                return "Удаление из моей музыки";
            case DOWNLOAD:
                return "Скачивание";
            case CACHE:
            default:
                return "Добавление в кэш";
        }
    }
}
