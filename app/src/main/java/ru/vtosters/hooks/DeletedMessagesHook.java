package ru.vtosters.hooks;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import com.vk.core.network.Network;
import com.vk.dto.common.AttachmentWithMedia;
import com.vk.im.engine.events.OnMsgUpdateEvent;
import com.vk.im.engine.internal.longpoll.tasks.MsgDeleteLpTask;
import com.vk.im.engine.internal.storage.StorageEnvironment;
import com.vk.im.engine.models.Image;
import com.vk.im.engine.models.ImageList;
import com.vk.im.engine.models.attaches.Attach;
import com.vk.im.engine.models.attaches.AttachImage;
import com.vk.im.engine.models.attaches.AttachWithImage;
import com.vk.im.engine.models.messages.Msg;
import com.vk.im.engine.models.messages.MsgFromUser;
import com.vk.im.engine.models.messages.NestedMsg;
import com.vk.libsqliteext.CustomSqliteExtensionsKt;
import com.vtosters.lite.attachments.PhotoAttachment;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import ru.vtosters.lite.concurrent.VTExecutors;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ru.vtosters.hooks.other.Preferences.getBoolValue;
import static ru.vtosters.lite.utils.AndroidUtils.getGlobalContext;

public class DeletedMessagesHook {
    private static Set<Integer> sDeletedMessagesList = new HashSet<>();
    private static SQLiteDatabase sVKSQLiteDatabase;
    private static int sBodyIndex = -1;
    private static DeletedMessagesDBHelper sVTDatabase;

    public static void reloadMessagesList() {
        if (sVTDatabase == null) {
            sVTDatabase = new DeletedMessagesDBHelper();
        }
        sDeletedMessagesList = new HashSet<>(sVTDatabase.loadAllMessages());
    }

    public static void grabVKDatabase(StorageEnvironment storageEnvironment) {
        sVKSQLiteDatabase = storageEnvironment.a();
    }

    public static boolean hook() {
        return getBoolValue("undeletemsg", false);
    }

    public static boolean isDeletedMessage(int msgId) {
        return hook() && sDeletedMessagesList.contains(msgId);
    }

    public static void applyTransparency(View view, Msg msg) {
        if (view == null) return;

        view.setAlpha(msg != null && isDeletedMessage(msg.C1()) ? 0.55f : 1f);
    }

    public static void setBodyDBParser(Msg msg) {
        if (!(msg instanceof MsgFromUser)) return;

        MsgFromUser msgFromUser = (MsgFromUser) msg;
        List<NestedMsg> nestedMsgs = msgFromUser.w0();

        if (!sDeletedMessagesList.isEmpty()) {
            checkForNestedMsg(nestedMsgs);
        }

        if (sDeletedMessagesList.contains(msg.C1())) {
            restoreDeletedPhotos(msg.C1(), msgFromUser.J0(), nestedMsgs);
            editTextOfMsg(msgFromUser);
        }
    }

    private static void checkForNestedMsg(List<NestedMsg> nestedMsgs) {
        for (NestedMsg nestedMsg : nestedMsgs) {
            List<NestedMsg> nested = nestedMsg.w0();
            if (!nested.isEmpty()) checkForNestedMsg(nested);

            nestedMsg.d(EncryptionMessagesHook.decryptMessage(nestedMsg.f(), nestedMsg.getFrom().getId()));
        }
    }

    private static void editTextOfMsg(MsgFromUser msgFromUser) {
        String body = msgFromUser.f();
        String decrypted = EncryptionMessagesHook.decryptMessage(body, msgFromUser.v1());

        if (!decrypted.equals(body)) {
            msgFromUser.d(decrypted);
        }
    }

    public static void updateDialog(MsgDeleteLpTask msgDeleteLpTask) {
        var cursor = getMessageFromDatabaseById(msgDeleteLpTask.d);
        if (cursor == null) return;

        @SuppressLint("Range")
        int localId = cursor.getInt(cursor.getColumnIndex("local_id"));

        var className = DeletedMessagesHook.class.getSimpleName();
        var imEnvironment = msgDeleteLpTask.b;
        var cint = msgDeleteLpTask.c;
        imEnvironment.a(className, new OnMsgUpdateEvent(className, cint, localId));
    }

    public static void hookDeletedMessageId(MsgDeleteLpTask msgDeleteLpTask) {
        if (!hook()) return;

        var messageId = msgDeleteLpTask.d;

        Cursor c = getMessageFromDatabaseById(messageId);
        if (c == null) return;

        if (sBodyIndex == -1) sBodyIndex = c.getColumnIndex("body");

        saveDeletedPhotos(msgDeleteLpTask, messageId);
        sDeletedMessagesList.add(messageId);
        sVTDatabase.saveDeletedMessage(messageId);
    }

    private static void saveDeletedPhotos(MsgDeleteLpTask msgDeleteLpTask, int messageId) {
        try {
            Msg msg = msgDeleteLpTask.b.a0().j().f(messageId);
            if (msg instanceof MsgFromUser) {
                MsgFromUser msgFromUser = (MsgFromUser) msg;
                saveDeletedPhotos(messageId, msgFromUser.J0(), msgFromUser.w0());
            }
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to save deleted photos", th);
        }
    }

    private static void saveDeletedPhotos(int messageId, List<Attach> attaches, List<NestedMsg> nestedMsgs) {
        if (attaches != null) {
            for (Attach attach : attaches) {
                if (attach instanceof AttachImage) {
                    saveDeletedPhoto(messageId, (AttachImage) attach);
                }
            }
        }

        if (nestedMsgs != null) {
            for (NestedMsg nestedMsg : nestedMsgs) {
                if (nestedMsg != null) {
                    saveDeletedPhotos(messageId, nestedMsg.J0(), nestedMsg.w0());
                }
            }
        }
    }

    private static void saveDeletedPhoto(int messageId, AttachImage attachImage) {
        Image image = getDisplayImage(attachImage);
        if (image == null) return;

        File file = getDeletedPhotoFile(messageId, attachImage.getLocalId(), attachImage.getId(), image.getWidth(), image.getHeight());
        if (file.exists() && file.length() > 0) return;

        String url = image.u1();
        if (TextUtils.isEmpty(url)) return;

        if (url.startsWith("http")) {
            VTExecutors.getIoExecutor().execute(() -> downloadPhoto(url, file));
            return;
        }

        File localFile = attachImage.i();
        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            VTExecutors.getIoExecutor().execute(() -> copyPhoto(localFile, file));
        }
    }

    private static Image getDisplayImage(AttachImage attachImage) {
        Image image = attachImage.r() ? attachImage.j() : attachImage.k();

        if (image == null) {
            image = attachImage.r() ? attachImage.k() : attachImage.j();
        }

        return image;
    }

    private static void downloadPhoto(String url, File file) {
        File tmp = new File(file.getParentFile(), file.getName() + ".part");

        try {
            Request request = new Request.a().b(url).a();

            try (Response response = Network.b(Network.ClientType.CLIENT_IMAGE_LOADER).a(request).execute()) {
                if (!response.h() || response.a() == null) return;

                try (BufferedSink sink = Okio.a(Okio.b(tmp))) {
                    sink.a(response.a().f());
                }
            }

            if (tmp.length() > 0) {
                if (file.exists()) file.delete();
                if (!tmp.renameTo(file)) {
                    Log.w("DeletedMessagesHook", "Failed to save deleted photo: " + file);
                }
            }
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to download deleted photo", th);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void copyPhoto(File source, File file) {
        File tmp = new File(file.getParentFile(), file.getName() + ".part");

        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to copy deleted photo", th);
            tmp.delete();
            return;
        }

        if (tmp.length() > 0) {
            if (file.exists()) file.delete();
            if (!tmp.renameTo(file)) {
                Log.w("DeletedMessagesHook", "Failed to save deleted photo: " + file);
            }
        }

        if (tmp.exists()) tmp.delete();
    }

    private static void restoreDeletedPhotos(int messageId, List<Attach> attaches, List<NestedMsg> nestedMsgs) {
        if (attaches != null) {
            for (Attach attach : attaches) {
                if (!(attach instanceof AttachImage)) continue;

                AttachImage attachImage = (AttachImage) attach;
                File localFile = attachImage.i();
                if (localFile != null && localFile.exists() && localFile.length() > 0) continue;

                Image image = findRestoredImage(messageId, attachImage);
                if (image != null) {
                    attachImage.a(new ImageList(image));
                }
            }
        }

        if (nestedMsgs != null) {
            for (NestedMsg nestedMsg : nestedMsgs) {
                if (nestedMsg != null) {
                    restoreDeletedPhotos(messageId, nestedMsg.J0(), nestedMsg.w0());
                }
            }
        }
    }

    public static boolean saveDeletedPhotoToGallery(AttachmentWithMedia attachment) {
        if (!hook() || !(attachment instanceof PhotoAttachment)) return false;

        int photoId = attachment.getId();
        if (photoId == 0) return false;

        File source = findDeletedPhotoForPhotoId(photoId);

        return source != null && saveFileToGallery(source);
    }

    public static boolean saveDeletedImageToGallery(AttachWithImage attachment) {
        if (!hook() || !(attachment instanceof AttachImage)) return false;

        AttachImage image = (AttachImage) attachment;
        File source = image.i();

        if (source == null || !source.exists() || source.length() == 0) {
            source = findDeletedPhotoForPhotoId(image.getId());
        }

        return source != null && source.exists() && source.length() > 0 && saveFileToGallery(source);
    }

    private static boolean saveFileToGallery(File source) {
        Context context = getGlobalContext();
        String name = "vkataxa_" + System.currentTimeMillis() + ".jpg";

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                saveToGalleryModern(context, source, name);
            } else {
                saveToGalleryLegacy(context, source, name);
            }

            Toast.makeText(context, "Фото сохранено", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to save deleted photo to gallery", th);
            return false;
        }
    }

    private static File findDeletedPhotoForPhotoId(int photoId) {
        File[] files = getDeletedPhotosDir().listFiles();
        if (files == null) return null;

        String marker = "_" + photoId + "_";

        for (File file : files) {
            if (file.isFile() && file.length() > 0 && file.getName().contains(marker)) {
                return file;
            }
        }

        return null;
    }

    private static void saveToGalleryModern(Context context, File source, String name) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/vkataxa");

        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Failed to create MediaStore entry");

        try (InputStream in = new FileInputStream(source);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Failed to open MediaStore output");

            copyStream(in, out);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(uri, done, null, null);
    }

    private static void saveToGalleryLegacy(Context context, File source, String name) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "vkataxa");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Failed to create gallery directory");

        File dest = new File(dir, name);

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        }

        MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;

        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    private static Image findRestoredImage(int messageId, AttachImage attachImage) {
        int attachLocalId = attachImage.getLocalId();
        ImageList localList = attachImage.o();

        if (localList != null) {
            for (Image image : localList.u1()) {
                File file = getExistingDeletedPhotoFile(messageId, attachLocalId, attachImage.getId(), image.getWidth(), image.getHeight());
                if (file != null) {
                    return new Image(image.getWidth(), image.getHeight(), Uri.fromFile(file).toString());
                }
            }
        }

        ImageList remoteList = attachImage.p();

        if (remoteList != null) {
            for (Image image : remoteList.u1()) {
                File file = getExistingDeletedPhotoFile(messageId, attachLocalId, attachImage.getId(), image.getWidth(), image.getHeight());
                if (file != null) {
                    return new Image(image.getWidth(), image.getHeight(), Uri.fromFile(file).toString());
                }
            }
        }

        File legacyFile = getLegacyDeletedPhotoFile(messageId, attachLocalId);
        if (legacyFile.exists() && legacyFile.length() > 0) {
            Image image = getDisplayImage(attachImage);
            int width = image != null ? image.getWidth() : -1;
            int height = image != null ? image.getHeight() : -1;

            File file = migrateDeletedPhotoFile(legacyFile, messageId, attachLocalId, attachImage.getId(), width, height);

            return new Image(width, height, Uri.fromFile(file).toString());
        }

        return null;
    }

    private static File migrateDeletedPhotoFile(File source, int messageId, int attachLocalId, int photoId, int width, int height) {
        File target = getDeletedPhotoFile(messageId, attachLocalId, photoId, width, height);
        if (target.exists() || source.equals(target)) return target.exists() ? target : source;

        return source.renameTo(target) ? target : source;
    }

    private static File getExistingDeletedPhotoFile(int messageId, int attachLocalId, int photoId, int width, int height) {
        File file = getDeletedPhotoFile(messageId, attachLocalId, photoId, width, height);
        if (file.exists() && file.length() > 0) return file;

        file = getDeletedPhotoFile(messageId, attachLocalId, width, height);
        if (file.exists() && file.length() > 0) {
            return migrateDeletedPhotoFile(file, messageId, attachLocalId, photoId, width, height);
        }

        return null;
    }

    private static File getDeletedPhotoFile(int messageId, int attachLocalId, int photoId, int width, int height) {
        return new File(getDeletedPhotosDir(), messageId + "_" + attachLocalId + "_" + photoId + "_" + width + "x" + height + ".jpg");
    }

    private static File getDeletedPhotoFile(int messageId, int attachLocalId, int width, int height) {
        return new File(getDeletedPhotosDir(), messageId + "_" + attachLocalId + "_" + width + "x" + height + ".jpg");
    }

    private static File getLegacyDeletedPhotoFile(int messageId, int attachLocalId) {
        return new File(getDeletedPhotosDir(), messageId + "_" + attachLocalId + ".jpg");
    }

    private static File getDeletedPhotosDir() {
        File dir = new File(getGlobalContext().getFilesDir(), "deleted_msgs_photos");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static Cursor getMessageFromDatabaseById(int messageId) {
        String query = "SELECT * FROM messages WHERE vk_id = " + messageId;
        Cursor c = CustomSqliteExtensionsKt.a(sVKSQLiteDatabase, query);
        if (!c.moveToFirst()) return null;
        return c;
    }

    public static void deleteMessageFromDB(int messageId) {
        sVKSQLiteDatabase.execSQL("DELETE FROM messages WHERE vk_id = " + messageId);
    }

    public static class DeletedMessagesDBHelper extends SQLiteOpenHelper {
        public DeletedMessagesDBHelper() {
            super(getGlobalContext(), "deleted_msgs", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("create table deleted_msgs (id integer primary key, msgId integer)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        }

        public void saveDeletedMessage(int msgId) {
            SQLiteDatabase sqLiteDatabase = getWritableDatabase();

            ContentValues contentValues = new ContentValues();
            contentValues.put("msgId", msgId);

            sqLiteDatabase.insert("deleted_msgs", null, contentValues);
            sqLiteDatabase.close();
        }

        public List<Integer> loadAllMessages() {
            List<Integer> msgIdsList = new ArrayList<>();

            Cursor cursor = getReadableDatabase().query("deleted_msgs", null, null, null, null, null, null);
            if (cursor.moveToFirst()) {
                int msgIdIndex = cursor.getColumnIndex("msgId");

                do {
                    msgIdsList.add(cursor.getInt(msgIdIndex));
                } while (cursor.moveToNext());
                cursor.close();
            }

            return msgIdsList;
        }
    }
}