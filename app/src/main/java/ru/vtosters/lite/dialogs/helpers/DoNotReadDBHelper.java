package ru.vtosters.lite.dialogs.helpers;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import ru.vtosters.lite.dialogs.DNRPrefs;
import ru.vtosters.lite.utils.AccountManagerUtils;

import java.util.ArrayList;
import java.util.List;

import static ru.vtosters.lite.utils.AndroidUtils.getGlobalContext;

public class DoNotReadDBHelper extends SQLiteOpenHelper {
    public DoNotReadDBHelper() {
        super(getGlobalContext(), "dnr", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table dnr (account integer not null default 0, peerId integer not null, enabled integer, primary key(account, peerId))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= 2) return;

        db.beginTransaction();
        try {
            db.execSQL("create table dnr_new (account integer not null, peerId integer not null, enabled integer, primary key(account, peerId))");
            db.execSQL("insert into dnr_new (account, peerId, enabled) select ?, peerId, enabled from dnr", new Object[]{account()});
            db.execSQL("drop table dnr");
            db.execSQL("alter table dnr_new rename to dnr");
            db.setTransactionSuccessful();
        } catch (Throwable th) {
            Log.w("DoNotReadDBHelper", "Failed to migrate dnr, recreating", th);
            db.execSQL("drop table if exists dnr");
            db.execSQL("drop table if exists dnr_new");
            onCreate(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static int account() {
        return AccountManagerUtils.getUserId();
    }

    public boolean isEnabledForPeerId(int peerId) {
        try (Cursor c = getReadableDatabase().query("dnr", null, "account = ? AND peerId = ?", new String[]{String.valueOf(account()), String.valueOf(peerId)}, null, null, null)) {
            if (c.moveToFirst()) {
                int enabledIndex = c.getColumnIndex("enabled");

                return c.getInt(enabledIndex) == 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return DNRPrefs.getMarkAsReadWithoutExceptions(peerId);
    }

    public List<Integer> get() {
        ArrayList<Integer> rs = new ArrayList<>();

        try (Cursor c = getReadableDatabase().query("dnr", null, "enabled = 1 AND account = ?", new String[]{String.valueOf(account())}, null, null, null)) {
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

    public void setEnabledForPeerId(int peerId, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put("enabled", enabled ? 1 : 0);

        int i = getWritableDatabase().update("dnr", cv, "account = ? AND peerId = ?", new String[]{String.valueOf(account()), String.valueOf(peerId)});
        if (i == 0) {
            cv.put("account", account());
            cv.put("peerId", peerId);
            getWritableDatabase().insert("dnr", null, cv);
        }
    }
}
