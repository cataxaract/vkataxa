package ru.vtosters.lite.services;

import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.Nullable;
import com.vtosters.lite.R;
import ru.vtosters.lite.utils.AndroidUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;
import static ru.vtosters.lite.utils.AndroidUtils.getGlobalContext;

public class LogWriterService extends Service {
    public LogWriterService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        Log.d("LogWriterService", "The service has been started");

        String log = intent != null ? intent.getStringExtra("log") : null;
        if (log == null) log = "";

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss", Locale.getDefault());
        String fileName = "VTL_log_" + dateFormat.format(new Date()) + ".txt";

        boolean saved = false;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                saved = writeModern(fileName, log);
            } else {
                saved = writeLegacy(fileName, log);
            }
        } catch (Throwable th) {
            Log.e("LogWriterService", "Failed to save log", th);
        }

        int notificationId = intent != null ? intent.getIntExtra("notificationId", 1) : 1;
        var manager = (NotificationManager) getGlobalContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(notificationId);
        }

        makeText(getGlobalContext(), AndroidUtils.getString(saved ? R.string.logfile_saved : R.string.logfile_error), LENGTH_SHORT).show();

        stopSelf();
        return START_NOT_STICKY;
    }

    private boolean writeModern(String fileName, String log) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VK/logs/com.vtosters.lite");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;

        try {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Failed to open log output");
                out.write(log.getBytes(StandardCharsets.UTF_8));
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Throwable ignored) {
                // Nothing else we can do here.
            }
            throw e;
        }
    }

    private boolean writeLegacy(String fileName, String log) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VK/logs/com.vtosters.lite");
        if (!dir.exists() && !dir.mkdirs()) return false;

        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(log.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
