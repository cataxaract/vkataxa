// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.spotify;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashSet;
import java.util.Set;

/**
 * MU-10 phase 2: persistent target + added-track state per import key. Matching progress is not
 * stored yet; re-running the same import reuses the playlist and skips already added VK ids.
 */
final class ImportProgressDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "music_import.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_JOBS = "jobs";
    private static final String TABLE_ITEMS = "items";

    static final class Job {
        final int ownerId;
        final int playlistId;
        final String status;

        Job(int ownerId, int playlistId, String status) {
            this.ownerId = ownerId;
            this.playlistId = playlistId;
            this.status = status;
        }
    }

    ImportProgressDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_JOBS + " ("
                + "account INTEGER NOT NULL,"
                + "key TEXT NOT NULL,"
                + "target_owner INTEGER NOT NULL,"
                + "target_playlist INTEGER NOT NULL,"
                + "status TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(account, key))");
        db.execSQL("CREATE TABLE " + TABLE_ITEMS + " ("
                + "account INTEGER NOT NULL,"
                + "key TEXT NOT NULL,"
                + "track_uid TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(account, key, track_uid))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only.
    }

    Job get(int account, String key) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_JOBS,
                new String[]{"target_owner", "target_playlist", "status"},
                "account = ? AND key = ?",
                new String[]{String.valueOf(account), key},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                return new Job(cursor.getInt(0), cursor.getInt(1), cursor.getString(2));
            }
        }
        return null;
    }

    void saveTarget(int account, String key, int ownerId, int playlistId, String status) {
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("key", key);
        values.put("target_owner", ownerId);
        values.put("target_playlist", playlistId);
        values.put("status", status);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                TABLE_JOBS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void setStatus(int account, String key, String status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TABLE_JOBS, values, "account = ? AND key = ?",
                new String[]{String.valueOf(account), key});
    }

    Set<String> added(int account, String key) {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_ITEMS,
                new String[]{"track_uid"},
                "account = ? AND key = ?",
                new String[]{String.valueOf(account), key},
                null, null, null)) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        }
        return result;
    }

    void markAdded(int account, String key, String trackUid) {
        if (trackUid == null || trackUid.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("key", key);
        values.put("track_uid", trackUid);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void clear(int account, String key) {
        getWritableDatabase().delete(
                TABLE_JOBS, "account = ? AND key = ?", new String[]{String.valueOf(account), key});
        getWritableDatabase().delete(
                TABLE_ITEMS, "account = ? AND key = ?", new String[]{String.valueOf(account), key});
    }
}
