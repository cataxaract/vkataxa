package ru.vtosters.hooks;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import com.vk.core.network.Network;
import com.vk.im.engine.events.OnMsgUpdateEvent;
import com.vk.im.engine.internal.longpoll.tasks.MsgDeleteLpTask;
import com.vk.im.engine.internal.storage.StorageEnvironment;
import com.vk.im.engine.models.Image;
import com.vk.im.engine.models.ImageList;
import com.vk.im.engine.models.attaches.Attach;
import com.vk.im.engine.models.attaches.AttachImage;
import com.vk.im.engine.models.messages.Msg;
import com.vk.im.engine.models.messages.MsgFromUser;
import com.vk.im.engine.models.messages.NestedMsg;
import com.vk.libsqliteext.CustomSqliteExtensionsKt;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.lite.concurrent.VTExecutors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static ru.vtosters.hooks.other.Preferences.getBoolValue;
import static ru.vtosters.lite.utils.AndroidUtils.getGlobalContext;

public class DeletedMessagesHook {
    private static List<Integer> sDeletedMessagesList = new ArrayList<>();
    private static SQLiteDatabase sVKSQLiteDatabase;
    private static int sBodyIndex = -1;
    private static DeletedMessagesDBHelper sVTDatabase;

    public static void reloadMessagesList() {
        if (sVTDatabase == null) {
            sVTDatabase = new DeletedMessagesDBHelper();
        }
        sDeletedMessagesList = new ArrayList<>(sVTDatabase.loadAllMessages());
    }

    public static void grabVKDatabase(StorageEnvironment storageEnvironment) {
        sVKSQLiteDatabase = storageEnvironment.a();
    }

    public static boolean hook() {
        return getBoolValue("undeletemsg", false);
    }

    public static void setBodyDBParser(Msg msg) {
        if (!(msg instanceof MsgFromUser)) return;

        for (Integer integer : sDeletedMessagesList) {
            MsgFromUser msgFromUser = (MsgFromUser) msg;
            checkForNestedMsg(msgFromUser.w0());
            if (integer == msg.C1()) {
                restoreDeletedPhotos(integer, msgFromUser.J0(), msgFromUser.w0());
                editTextOfMsg((MsgFromUser) msg);
                break;
            }
        }
    }

    private static void checkForNestedMsg(List<NestedMsg> nestedMsgs) {
        for (NestedMsg nestedMsg : nestedMsgs) {
            if (!nestedMsg.w0().isEmpty()) checkForNestedMsg(nestedMsg.w0());

            nestedMsg.d(EncryptionMessagesHook.decryptMessage(nestedMsg.f(), nestedMsg.getFrom().getId()));
        }
    }

    private static void editTextOfMsg(MsgFromUser msgFromUser) {
        if (!msgFromUser.f().startsWith(getPrefixUndelete())) {
            msgFromUser.d(getPrefixUndelete() + EncryptionMessagesHook.decryptMessage(msgFromUser));
        }
    }

    private static String getPrefixUndelete() {
        var prefs = Preferences.getPreferences().getString("undeletemsg_prefix_value", "🗑");

        if (prefs.isEmpty()) {
            return "\uD83D\uDDD1 ";
        }

        return prefs + " ";
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
        File file = getDeletedPhotoFile(messageId, attachImage.getLocalId());
        if (file.exists() && file.length() > 0) return;

        ImageList imageList = attachImage.g();
        Image image = imageList != null ? imageList.t1() : null;
        if (image == null) return;

        String url = image.u1();
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return;

        VTExecutors.getIoExecutor().execute(() -> downloadPhoto(url, file));
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

    private static void restoreDeletedPhotos(int messageId, List<Attach> attaches, List<NestedMsg> nestedMsgs) {
        if (attaches != null) {
            for (Attach attach : attaches) {
                if (!(attach instanceof AttachImage)) continue;

                AttachImage attachImage = (AttachImage) attach;
                File localFile = attachImage.i();
                if (localFile != null && localFile.exists() && localFile.length() > 0) continue;

                File file = getDeletedPhotoFile(messageId, attachImage.getLocalId());
                if (!file.exists() || file.length() == 0) continue;

                ImageList imageList = attachImage.g();
                Image image = imageList != null ? imageList.t1() : null;
                int width = image != null ? image.getWidth() : -1;
                int height = image != null ? image.getHeight() : -1;

                attachImage.a(new ImageList(new Image(width, height, Uri.fromFile(file).toString())));
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

    private static File getDeletedPhotoFile(int messageId, int attachLocalId) {
        File dir = new File(getGlobalContext().getFilesDir(), "deleted_msgs_photos");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, messageId + "_" + attachLocalId + ".jpg");
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