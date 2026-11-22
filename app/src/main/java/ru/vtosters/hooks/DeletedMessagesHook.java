package ru.vtosters.hooks;

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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ru.vtosters.hooks.other.Preferences.getBoolValue;
import static ru.vtosters.lite.utils.AndroidUtils.getGlobalContext;

public class DeletedMessagesHook {
    private static final Set<Integer> sDeletedLocalIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> sDeletedVkIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> sDeletedLegacyVkIds = ConcurrentHashMap.newKeySet();
    private static volatile SQLiteDatabase sVKSQLiteDatabase;
    private static volatile DeletedMessagesDBHelper sVTDatabase;

    public static void reloadMessagesList() {
        DeletedMessagesDBHelper helper = getDatabase();
        if (helper == null) return;

        Set<Integer> localIds = ConcurrentHashMap.newKeySet();
        Set<Integer> vkIds = ConcurrentHashMap.newKeySet();
        Set<Integer> legacyVkIds = ConcurrentHashMap.newKeySet();

        try {
            helper.loadDeletedIds(localIds, vkIds, legacyVkIds);
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to load deleted message ids", th);
            return;
        }

        sDeletedLocalIds.clear();
        sDeletedLocalIds.addAll(localIds);
        sDeletedVkIds.clear();
        sDeletedVkIds.addAll(vkIds);
        sDeletedLegacyVkIds.clear();
        sDeletedLegacyVkIds.addAll(legacyVkIds);
    }

    private static DeletedMessagesDBHelper getDatabase() {
        DeletedMessagesDBHelper helper = sVTDatabase;
        if (helper != null) return helper;

        synchronized (DeletedMessagesHook.class) {
            helper = sVTDatabase;
            if (helper == null) {
                helper = new DeletedMessagesDBHelper();
                sVTDatabase = helper;
            }
        }

        return helper;
    }

    public static void grabVKDatabase(StorageEnvironment storageEnvironment) {
        sVKSQLiteDatabase = storageEnvironment.a();
    }

    public static boolean hook() {
        return getBoolValue("undeletemsg", false);
    }

    private static boolean photosEnabled() {
        return getBoolValue("undeletemsg_photos", true);
    }

    private static boolean transparencyEnabled() {
        return getBoolValue("undeletemsg_transparency", true);
    }

    public static boolean isDeletedMessage(int msgId) {
        return hook() && sDeletedVkIds.contains(msgId);
    }

    public static boolean isDeletedMessage(Msg msg) {
        if (!hook() || msg == null) return false;

        int localId = msg.getLocalId();
        return (localId != 0 && sDeletedLocalIds.contains(localId)) || sDeletedLegacyVkIds.contains(msg.C1());
    }

    public static boolean isDeletedMessage(NestedMsg msg) {
        if (!hook() || msg == null) return false;

        int localId = msg.getLocalId();
        return (localId != 0 && sDeletedLocalIds.contains(localId)) || sDeletedVkIds.contains(msg.t1());
    }

    private static boolean hasDeletedMessages() {
        return !sDeletedLocalIds.isEmpty() || !sDeletedVkIds.isEmpty() || !sDeletedLegacyVkIds.isEmpty();
    }

    public static void applyTransparency(View view, Msg msg) {
        if (view == null) return;

        try {
            float alpha = isDeletedMessage(msg) && transparencyEnabled() ? 0.55f : 1f;
            if (view.getAlpha() != alpha) {
                view.setAlpha(alpha);
            }
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to apply message transparency", th);
        }
    }

    public static void setBodyDBParser(Msg msg) {
        if (!(msg instanceof MsgFromUser)) return;

        try {
            MsgFromUser msgFromUser = (MsgFromUser) msg;
            List<NestedMsg> nestedMsgs = msgFromUser.w0();

            if (hasDeletedMessages()) {
                checkForNestedMsg(nestedMsgs);
            }

            if (isDeletedMessage(msgFromUser)) {
                restoreDeletedPhotos(msg.C1(), msgFromUser.J0(), nestedMsgs);
                editTextOfMsg(msgFromUser);
            }
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to parse deleted message", th);
        }
    }

    private static void checkForNestedMsg(List<NestedMsg> nestedMsgs) {
        if (nestedMsgs == null) return;

        for (NestedMsg nestedMsg : nestedMsgs) {
            if (nestedMsg == null) continue;

            try {
                List<NestedMsg> nested = nestedMsg.w0();
                if (nested != null && !nested.isEmpty()) checkForNestedMsg(nested);

                String body = nestedMsg.f();
                if (body == null || nestedMsg.getFrom() == null) continue;

                nestedMsg.d(EncryptionMessagesHook.decryptMessage(body, nestedMsg.getFrom().getId()));
            } catch (Throwable th) {
                Log.w("DeletedMessagesHook", "Failed to parse nested message", th);
            }
        }
    }

    private static void editTextOfMsg(MsgFromUser msgFromUser) {
        String body = msgFromUser.f();
        if (body == null) return;

        String decrypted = EncryptionMessagesHook.decryptMessage(body, msgFromUser.v1());

        if (decrypted != null && !decrypted.equals(body)) {
            msgFromUser.d(decrypted);
        }
    }

    public static void updateDialog(MsgDeleteLpTask msgDeleteLpTask) {
        var className = DeletedMessagesHook.class.getSimpleName();
        var imEnvironment = msgDeleteLpTask.b;
        var peerId = msgDeleteLpTask.c;
        Integer localId = findLocalId(peerId, msgDeleteLpTask.d);

        if (imEnvironment != null && localId != null) {
            imEnvironment.a(className, new OnMsgUpdateEvent(className, peerId, localId));
        }
    }

    public static void hookDeletedMessageId(MsgDeleteLpTask msgDeleteLpTask) {
        if (!hook()) return;

        var peerId = msgDeleteLpTask.c;
        var messageId = msgDeleteLpTask.d;

        saveDeletedPhotos(msgDeleteLpTask, messageId);

        Integer localId = findLocalId(peerId, messageId);

        sDeletedVkIds.add(messageId);
        if (localId != null) {
            sDeletedLocalIds.add(localId);
        } else {
            sDeletedLegacyVkIds.add(messageId);
        }

        DeletedMessagesDBHelper helper = getDatabase();
        if (helper != null) {
            try {
                helper.saveDeletedMessage(localId, peerId, messageId);
            } catch (Throwable th) {
                Log.w("DeletedMessagesHook", "Failed to save deleted message id", th);
            }
        }
    }

    private static void saveDeletedPhotos(MsgDeleteLpTask msgDeleteLpTask, int messageId) {
        if (!photosEnabled()) return;

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
        if (!photosEnabled()) return;

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
        if (attachImage == null) return null;

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
        if (!photosEnabled()) return;

        if (attaches != null) {
            for (Attach attach : attaches) {
                if (!(attach instanceof AttachImage)) continue;

                try {
                    AttachImage attachImage = (AttachImage) attach;
                    File localFile = attachImage.i();
                    if (localFile != null && localFile.exists() && localFile.length() > 0) continue;

                    Image image = findRestoredImage(messageId, attachImage);
                    if (image != null) {
                        attachImage.a(new ImageList(image));
                    }
                } catch (Throwable th) {
                    Log.w("DeletedMessagesHook", "Failed to restore deleted photo", th);
                }
            }
        }

        if (nestedMsgs != null) {
            for (NestedMsg nestedMsg : nestedMsgs) {
                if (nestedMsg == null) continue;

                try {
                    restoreDeletedPhotos(messageId, nestedMsg.J0(), nestedMsg.w0());
                } catch (Throwable th) {
                    Log.w("DeletedMessagesHook", "Failed to restore nested deleted photo", th);
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
        if (attachImage == null) return null;

        int attachLocalId = attachImage.getLocalId();
        int photoId = attachImage.getId();
        ImageList localList = attachImage.o();

        if (localList != null) {
            List<Image> localImages = localList.u1();

            if (localImages != null) {
                for (Image image : localImages) {
                    if (image == null) continue;

                    File file = getExistingDeletedPhotoFile(messageId, attachLocalId, photoId, image.getWidth(), image.getHeight());
                    if (file != null) {
                        return new Image(image.getWidth(), image.getHeight(), Uri.fromFile(file).toString());
                    }
                }
            }
        }

        ImageList remoteList = attachImage.p();

        if (remoteList != null) {
            List<Image> remoteImages = remoteList.u1();

            if (remoteImages != null) {
                for (Image image : remoteImages) {
                    if (image == null) continue;

                    File file = getExistingDeletedPhotoFile(messageId, attachLocalId, photoId, image.getWidth(), image.getHeight());
                    if (file != null) {
                        return new Image(image.getWidth(), image.getHeight(), Uri.fromFile(file).toString());
                    }
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

    private static Integer findLocalId(int peerId, int messageId) {
        SQLiteDatabase db = sVKSQLiteDatabase;
        if (db == null) return null;

        String query = "SELECT local_id FROM messages WHERE dialog_id = " + peerId + " AND vk_id = " + messageId + " LIMIT 1";

        try (Cursor cursor = CustomSqliteExtensionsKt.a(db, query)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Throwable th) {
            Log.w("DeletedMessagesHook", "Failed to resolve message local id", th);
        }

        return null;
    }

    public static void deleteMessageFromDB(int messageId) {
        SQLiteDatabase db = sVKSQLiteDatabase;
        if (db != null) {
            db.execSQL("DELETE FROM messages WHERE vk_id = " + messageId);
        }
    }

    public static class DeletedMessagesDBHelper extends SQLiteOpenHelper {
        private static final int DB_VERSION = 2;

        public DeletedMessagesDBHelper() {
            super(getGlobalContext(), "deleted_msgs", null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("create table deleted_msgs (id integer primary key, msgId integer, peerId integer, localId integer)");
            createIndexes(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion >= 2) return;

            addColumn(db, "peerId");
            addColumn(db, "localId");

            try {
                db.execSQL("DELETE FROM deleted_msgs WHERE id NOT IN (SELECT MIN(id) FROM deleted_msgs GROUP BY msgId)");
            } catch (Throwable th) {
                Log.w("DeletedMessagesHook", "Failed to deduplicate deleted messages", th);
            }

            createIndexes(db);
        }

        private static void addColumn(SQLiteDatabase db, String column) {
            if (hasColumn(db, column)) return;

            db.execSQL("ALTER TABLE deleted_msgs ADD COLUMN " + column + " INTEGER");
        }

        private static boolean hasColumn(SQLiteDatabase db, String column) {
            try (Cursor cursor = db.rawQuery("PRAGMA table_info(deleted_msgs)", null)) {
                int nameIndex = cursor.getColumnIndex("name");

                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && column.equals(cursor.getString(nameIndex))) {
                        return true;
                    }
                }
            } catch (Throwable th) {
                Log.w("DeletedMessagesHook", "Failed to inspect deleted message schema", th);
            }

            return false;
        }

        private static void createIndexes(SQLiteDatabase db) {
            db.execSQL("create unique index if not exists idx_deleted_msgs_local on deleted_msgs(localId) where localId is not null");
            db.execSQL("create index if not exists idx_deleted_msgs_msg on deleted_msgs(msgId)");
        }

        public void saveDeletedMessage(Integer localId, int peerId, int msgId) {
            SQLiteDatabase db = getWritableDatabase();

            ContentValues values = new ContentValues();
            values.put("msgId", msgId);
            values.put("peerId", peerId);
            if (localId != null) {
                values.put("localId", localId);
            }

            db.insertWithOnConflict("deleted_msgs", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        public void loadDeletedIds(Set<Integer> localIds, Set<Integer> vkIds, Set<Integer> legacyVkIds) {
            SQLiteDatabase db = getReadableDatabase();
            Set<Integer> localVkIds = ConcurrentHashMap.newKeySet();

            try (Cursor cursor = db.query("deleted_msgs", new String[]{"localId", "msgId"}, null, null, null, null, null)) {
                int localIndex = cursor.getColumnIndex("localId");
                int msgIndex = cursor.getColumnIndex("msgId");

                while (cursor.moveToNext()) {
                    boolean hasLocalId = localIndex >= 0 && !cursor.isNull(localIndex);
                    boolean hasVkId = msgIndex >= 0 && !cursor.isNull(msgIndex);

                    if (hasLocalId) {
                        localIds.add(cursor.getInt(localIndex));
                    }

                    if (hasVkId) {
                        int vkId = cursor.getInt(msgIndex);
                        vkIds.add(vkId);

                        if (hasLocalId) {
                            localVkIds.add(vkId);
                        } else {
                            legacyVkIds.add(vkId);
                        }
                    }
                }
            }

            legacyVkIds.removeAll(localVkIds);
        }
    }
}
