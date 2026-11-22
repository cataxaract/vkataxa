package ru.vtosters.lite.downloaders;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import androidx.core.content.FileProvider;
import com.vk.core.dialogs.alert.VkAlertDialog;
import com.vtosters.lite.R;
import ru.vtosters.lite.ui.activities.APKInstallActivity;
import ru.vtosters.lite.utils.AndroidUtils;

import java.io.File;

public class OTADownloader {

    private static volatile boolean sRegistered = false;
    private static volatile long sDownloadId = -1L;
    private static volatile String sCommitSHA = "";

    private static final BroadcastReceiver callback = new BroadcastReceiver() {
        @SuppressLint("Range")
        @Override
        public void onReceive(Context context, Intent intent) {
            var extras = intent.getExtras();
            if (extras == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

            long id = extras.getLong(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != sDownloadId) return;

            try {
                var query = new DownloadManager.Query();
                query.setFilterById(id);

                var manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) return;

                try (var cursor = manager.query(query)) {
                    if (!cursor.moveToFirst()) return;

                    var status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        var uriStr = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                        File apk = uriStr != null ? new File(uriStr.replace("file://", "")) : null;

                        if (apk == null || !apk.exists()) {
                            apk = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VTLite-" + sCommitSHA + ".apk");
                        }

                        if (apk.exists()) {
                            var uri = FileProvider.getUriForFile(context, "com.vtosters.lite.common.VKFileProvider", apk);
                            APKInstallActivity.installOta(context, uri);
                        } else {
                            AndroidUtils.sendToast(context.getString(R.string.downloaderr));
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        AndroidUtils.sendToast(context.getString(R.string.downloaderr));
                    }
                }
            } catch (Throwable th) {
                th.printStackTrace();
                AndroidUtils.sendToast(context.getString(R.string.downloaderr));
            } finally {
                sDownloadId = -1L;
                sRegistered = false;
                try {
                    context.getApplicationContext().unregisterReceiver(this);
                } catch (Throwable ignored) {
                    // Receiver was already unregistered.
                }
            }
        }
    };

    public static void downloadBuild(String url, String commitSHA) {
        var context = AndroidUtils.getGlobalContext();
        try {
            var uri = Uri.parse(url);

            synchronized (OTADownloader.class) {
                if (!sRegistered) {
                    context.registerReceiver(callback, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                    sRegistered = true;
                }
            }

            sCommitSHA = commitSHA;

            var request = new DownloadManager.Request(uri);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle(AndroidUtils.getString("downloading_update"));
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VTLite-" + commitSHA + ".apk");

            var manager = (DownloadManager) AndroidUtils.getGlobalContext().getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager is unavailable");

            sDownloadId = manager.enqueue(request);
        } catch (Exception e) {
            e.printStackTrace();

            if (sRegistered && sDownloadId == -1L) {
                sRegistered = false;
                try {
                    context.unregisterReceiver(callback);
                } catch (Throwable ignored) {
                    // Receiver was already unregistered.
                }
            }

            new VkAlertDialog.Builder(context)
                    .setTitle(R.string.update_error)
                    .setMessage(R.string.update_error_text)
                    .setPositiveButton(R.string.no,
                            (dialog, which) -> dialog.cancel()
                    )
                    .setNeutralButton(R.string.yes,
                            (dialog, which) -> {
                                var intent = new Intent(Intent.ACTION_VIEW)
                                        .setData(Uri.parse("https://github.com/cataxaract/vkataxa/releases/latest/download/VTLite.apk"));
                                context.startActivity(intent);
                            }
                    )
                    .show();
        }
    }
}
