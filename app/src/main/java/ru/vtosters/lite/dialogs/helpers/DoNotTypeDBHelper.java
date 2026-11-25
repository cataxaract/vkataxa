package ru.vtosters.lite.dialogs.helpers;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import ru.vtosters.lite.dialogs.DNRPrefs;
import ru.vtosters.lite.utils.AccountManagerUtils;
import ru.vtosters.lite.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.List;

public class DoNotTypeDBHelper extends SQLiteOpenHelper {
    public DoNotTypeDBHelper() {
        super(AndroidUtils.getGlobalContext(), "dnt", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table dnt (account integer not null default 0, peerId integer not null, enabled integer, primary key(account, peerId))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= 2) return;

        db.beginTransaction();
        try {
            db.execSQL("create table dnt_new (account integer not null, peerId integer not null, enabled integer, primary key(account, peerId))");
            db.execSQL("insert into dnt_new (account, peerId, enabled) select ?, peerId, enabled from dnt", new Object[]{account()});
            db.execSQL("drop table dnt");
            db.execSQL("alter table dnt_new rename to dnt");
            db.setTransactionSuccessful();
        } catch (Throwable th) {
            Log.w("DoNotTypeDBHelper", "Failed to migrate dnt, recreating", th);
            db.execSQL("drop table if exists dnt");
            db.execSQL("drop table if exists dnt_new");
            onCreate(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static int account() {
        return AccountManagerUtils.getUserId();
    }

    public List<Integer> get() {
        ArrayList<Integer> rs = new ArrayList<>();

        try (Cursor c = getReadableDatabase().query("dnt", null, "enabled = 1 AND account = ?", new String[]{String.valueOf(account())}, null, null, null)) {
            if (c.moveToFirst()) {
                int peerIdIndex = c.getColumnIndex("peerId");

                do {
                    rs.add(c.getInt(peerIdIndex));
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return rs;
    }

    public boolean isEnabledForPeerId(int peerId) {
        try (Cursor c = getReadableDatabase().query("dnt", null, "account = ? AND peerId = ?", new String[]{String.valueOf(account()), String.valueOf(peerId)}, null, null, null)) {
            if (c.moveToFirst()) {
                int enabledIndex = c.getColumnIndex("enabled");

                return c.getInt(enabledIndex) == 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return DNRPrefs.getActivityWithoutExceptions(peerId);
    }

    public void setEnabledForPeerId(int peerId, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put("enabled", enabled ? 1 : 0);

        int i = getWritableDatabase().update("dnt", cv, "account = ? AND peerId = ?", new String[]{String.valueOf(account()), String.valueOf(peerId)});
        if (i == 0) {
            cv.put("account", account());
            cv.put("peerId", peerId);
            getWritableDatabase().insert("dnt", null, cv);
        }
    }
}
