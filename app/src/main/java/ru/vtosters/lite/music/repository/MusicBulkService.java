// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.repository;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.vk.api.audio.AudioAddToPlaylist;
import com.vk.api.audio.AudioDelete;
import com.vk.api.base.ApiCallback;
import com.vk.api.sdk.exceptions.VKApiExecutionException;
import com.vk.dto.music.MusicTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import bruhcollective.itaysonlab.libvkx.client.LibVKXClient;
import ru.vtosters.lite.core.log.VtLog;
import ru.vtosters.lite.core.net.RateLimiter;
import ru.vtosters.lite.downloaders.AudioDownloader;
import ru.vtosters.lite.downloaders.notifications.NotificationChannels;
import ru.vtosters.lite.music.cache.helpers.PlaylistHelper;
import ru.vtosters.lite.music.callback.MusicCallbackBuilder;
import ru.vtosters.lite.music.downloader.TrackDownloader;
import ru.vtosters.lite.music.interfaces.Callback;
import ru.vtosters.lite.music.notification.MusicNotificationBuilder;

/**
 * MU-02: queued bulk operations with bounded parallelism, rate limiting (SEC-03), progress and
 * cancellation. Captcha (error 14) stops the queue instead of being retried.
 */
public final class MusicBulkService {

    public enum Action {
        ADD_TO_PLAYLIST,
        REMOVE_FROM_MY_MUSIC,
        DOWNLOAD,
        CACHE
    }

    public interface Listener {
        void onProgress(int done, int total);

        void onFinished(int ok, int failed, boolean canceled, boolean captcha);
    }

    private static final String TAG = "MusicBulk";
    private static final int THREADS = 3;
    private static final int PLAYLIST_CHUNK = 50;
    private static final long MIN_INTERVAL_MS = 200L;
    private static final int NOTIFICATION_ID = 0x4D553032;

    private static final Object LOCK = new Object();
    private static final NotificationManagerCompat NOTIFICATION_MANAGER =
            NotificationChannels.getNotificationManager();

    private static volatile Job sJob;

    private MusicBulkService() {
    }

    public static boolean isRunning() {
        return sJob != null;
    }

    public static void cancel() {
        Job job = sJob;
        if (job != null) {
            job.cancel();
        }
    }

    public static boolean start(List<MusicTrack> tracks, Action action, String playlistId, Listener listener) {
        if (tracks == null || tracks.isEmpty() || action == null) {
            return false;
        }
        if (action == Action.ADD_TO_PLAYLIST && parsePlaylistId(playlistId) == null) {
            return false;
        }

        Job job = new Job(new ArrayList<>(tracks), action, playlistId, listener);
        synchronized (LOCK) {
            if (sJob != null) {
                return false;
            }
            sJob = job;
        }
        job.start();
        return true;
    }

    private static int[] parsePlaylistId(String playlistId) {
        if (playlistId == null) {
            return null;
        }
        int separator = playlistId.indexOf('_');
        if (separator <= 0 || separator == playlistId.length() - 1) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(playlistId.substring(0, separator)),
                    Integer.parseInt(playlistId.substring(separator + 1))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String titleFor(Action action) {
        switch (action) {
            case ADD_TO_PLAYLIST:
                return "Добавление в плейлист";
            case REMOVE_FROM_MY_MUSIC:
                return "Удаление из моей музыки";
            case DOWNLOAD:
                return "Скачивание выбранных треков";
            case CACHE:
            default:
                return "Добавление выбранных треков в кэш";
        }
    }

    private interface Task {
        void run() throws Exception;

        int size();
    }

    private static final class Job {
        private final List<MusicTrack> tracks;
        private final Action action;
        private final String playlistId;
        private final Listener listener;
        private final int total;
        private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        private final RateLimiter limiter = new RateLimiter(MIN_INTERVAL_MS);
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicBoolean captcha = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger ok = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final Handler main = new Handler(Looper.getMainLooper());
        private final NotificationCompat.Builder notification;
        private final Callback notificationCallback;

        private Job(List<MusicTrack> tracks, Action action, String playlistId, Listener listener) {
            this.tracks = tracks;
            this.action = action;
            this.playlistId = playlistId;
            this.listener = listener;
            this.total = tracks.size();
            this.notification = MusicNotificationBuilder.buildPlaylistDownloadNotification(
                    titleFor(action), NOTIFICATION_ID);
            this.notificationCallback = MusicCallbackBuilder.buildPlaylistCallback(
                    total, notification, NOTIFICATION_ID);
        }

        private void start() {
            if (action == Action.ADD_TO_PLAYLIST) {
                int[] ids = parsePlaylistId(playlistId);
                if (ids == null) {
                    finish();
                    return;
                }
                for (int start = 0; start < tracks.size(); start += PLAYLIST_CHUNK) {
                    int end = Math.min(start + PLAYLIST_CHUNK, tracks.size());
                    submit(new PlaylistTask(new ArrayList<>(tracks.subList(start, end)), ids[0], ids[1]));
                }
            } else {
                for (MusicTrack track : tracks) {
                    submit(new SingleTask(track));
                }
            }
            pool.shutdown();
        }

        private void submit(Task task) {
            pool.execute(() -> {
                if (canceled.get()) {
                    return;
                }
                try {
                    execute(task);
                    ok.addAndGet(task.size());
                } catch (RateLimiter.CaptchaRequiredException e) {
                    captcha.set(true);
                    cancelInternal();
                    return;
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    failed.addAndGet(task.size());
                    VtLog.e(TAG, "bulk task failed: " + action, t);
                } finally {
                    if (!finished.get()) {
                        progress(task.size());
                    }
                }
            });
        }

        private void execute(Task task) throws Exception {
            VKApiExecutionException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (canceled.get()) {
                    throw new InterruptedException("canceled");
                }
                limiter.acquire();
                try {
                    task.run();
                    return;
                } catch (VKApiExecutionException e) {
                    last = e;
                    limiter.onApiError(e.d());
                    if (e.d() != RateLimiter.ERROR_FLOOD_CONTROL) {
                        throw e;
                    }
                }
            }
            if (last != null) {
                throw last;
            }
        }

        private void progress(int count) {
            int current = done.addAndGet(count);
            int shown = Math.min(current, total);
            main.post(() -> {
                if (listener != null) {
                    listener.onProgress(shown, total);
                }
            });
            if (!canceled.get()) {
                notificationCallback.onProgress(shown);
            }
            if (current >= total) {
                finish();
            }
        }

        private void cancelInternal() {
            if (!canceled.compareAndSet(false, true)) {
                return;
            }
            pool.shutdownNow();
            finish();
        }

        private void cancel() {
            cancelInternal();
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            synchronized (LOCK) {
                if (sJob == this) {
                    sJob = null;
                }
            }

            int current = Math.min(done.get(), total);
            int success = ok.get();
            int errors = failed.get();
            boolean wasCanceled = canceled.get();
            boolean wasCaptcha = captcha.get();

            if (wasCaptcha) {
                notification
                        .setContentText("VK ограничил операцию (captcha). Остановлено: " + current + " из " + total)
                        .setProgress(0, 0, false);
                notifyNotification();
            } else if (wasCanceled) {
                notification
                        .setContentText("Отменено: " + current + " из " + total)
                        .setProgress(0, 0, false);
                notifyNotification();
            } else {
                notificationCallback.onProgress(total);
            }

            main.post(() -> {
                if (listener != null) {
                    listener.onFinished(success, errors, wasCanceled, wasCaptcha);
                }
            });
        }

        private void notifyNotification() {
            try {
                NOTIFICATION_MANAGER.notify(NOTIFICATION_ID, notification.build());
            } catch (Throwable t) {
                VtLog.e(TAG, "notification update failed", t);
            }
        }

        private final class PlaylistTask implements Task {
            private final List<MusicTrack> chunk;
            private final int ownerId;
            private final int id;

            private PlaylistTask(List<MusicTrack> chunk, int ownerId, int id) {
                this.chunk = chunk;
                this.ownerId = ownerId;
                this.id = id;
            }

            @Override
            public void run() throws Exception {
                AudioAddToPlaylist.b builder = new AudioAddToPlaylist.b()
                        .a(ownerId)
                        .b(id);
                for (MusicTrack track : chunk) {
                    builder.a(track);
                }

                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<VKApiExecutionException> error = new AtomicReference<>();
                builder.a()
                        .a(new ApiCallback<AudioAddToPlaylist.c>() {
                            @Override
                            public void a(AudioAddToPlaylist.c result) {
                                latch.countDown();
                            }

                            @Override
                            public void a(VKApiExecutionException e) {
                                error.set(e);
                                latch.countDown();
                            }
                        })
                        .a();
                await(latch, error);
            }

            @Override
            public int size() {
                return chunk.size();
            }
        }

        private final class SingleTask implements Task {
            private final MusicTrack track;

            private SingleTask(MusicTrack track) {
                this.track = track;
            }

            @Override
            public void run() throws Exception {
                switch (action) {
                    case REMOVE_FROM_MY_MUSIC:
                        removeFromMyMusic(track);
                        MusicRepositoryImpl.instance().markUnliked(track);
                        break;
                    case CACHE:
                        download(track, true);
                        MusicRepositoryImpl.instance().invalidateCache();
                        break;
                    case DOWNLOAD:
                    default:
                        download(track, false);
                        break;
                }
            }

            @Override
            public int size() {
                return 1;
            }
        }
    }

    private static void removeFromMyMusic(MusicTrack track) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<VKApiExecutionException> error = new AtomicReference<>();
        new AudioDelete(track)
                .a(new ApiCallback<Boolean>() {
                    @Override
                    public void a(Boolean result) {
                        latch.countDown();
                    }

                    @Override
                    public void a(VKApiExecutionException e) {
                        error.set(e);
                        latch.countDown();
                    }
                })
                .a();
        await(latch, error);
    }

    private static void download(MusicTrack track, boolean cache) throws Exception {
        if (cache && MusicRepositoryImpl.get().isCachedTrack(LibVKXClient.asId(track))) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Callback callback = new Callback() {
            @Override
            public void onProgress(int progress) {
            }

            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable e) {
                error.set(e);
                latch.countDown();
            }

            @Override
            public void onSizeReceived(long size, long header) {
            }
        };

        if (cache) {
            TrackDownloader.cacheTrack(track, callback, PlaylistHelper.createCachedPlaylistMetadata());
        } else {
            String path = Environment.getExternalStoragePublicDirectory(AudioDownloader.dlpath).getAbsolutePath();
            TrackDownloader.downloadTrack(track, path, callback);
        }

        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("download timeout");
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
    }

    private static void await(CountDownLatch latch, AtomicReference<VKApiExecutionException> error)
            throws Exception {
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("VK API timeout");
        }
        if (error.get() != null) {
            throw error.get();
        }
    }
}
