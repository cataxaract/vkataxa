// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import ru.vtosters.lite.utils.AndroidUtils;

/**
 * MU-01: account-scoped local index of liked track ids. Stores only ids and timestamps; the full
 * track list is refreshed from audio.get with a TTL.
 */
final class MusicLikesDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "music_likes.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_LIKES = "likes";
    private static final String TABLE_META = "meta";
    private static final String KEY_SYNCED_AT = "synced_at";

    private static volatile MusicLikesDb instance;

    static MusicLikesDb get() {
        MusicLikesDb local = instance;
        if (local == null) {
            synchronized (MusicLikesDb.class) {
                local = instance;
                if (local == null) {
                    local = new MusicLikesDb(AndroidUtils.getGlobalContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private MusicLikesDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_LIKES + " ("
                + "account INTEGER NOT NULL,"
                + "track_id TEXT NOT NULL,"
                + "added_at INTEGER NOT NULL,"
                + "PRIMARY KEY(account, track_id))");
        db.execSQL("CREATE TABLE " + TABLE_META + " ("
                + "account INTEGER NOT NULL,"
                + "key TEXT NOT NULL,"
                + "value TEXT NOT NULL,"
                + "PRIMARY KEY(account, key))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only; future migrations must preserve account-scoped rows.
    }

    Set<String> ids(int account) {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_LIKES,
                new String[]{"track_id"},
                "account = ?",
                new String[]{String.valueOf(account)},
                null,
                null,
                null)) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        }
        return result;
    }

    long syncedAt(int account) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_META,
                new String[]{"value"},
                "account = ? AND key = ?",
                new String[]{String.valueOf(account), KEY_SYNCED_AT},
                null,
                null,
                null)) {
            if (cursor.moveToFirst()) {
                try {
                    return Long.parseLong(cursor.getString(0));
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    void replaceAll(int account, Collection<String> ids, long syncedAt) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_LIKES, "account = ?", new String[]{String.valueOf(account)});
            long now = System.currentTimeMillis();
            for (String id : ids) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("account", account);
                values.put("track_id", id);
                values.put("added_at", now);
                db.insertWithOnConflict(TABLE_LIKES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            putMeta(db, account, KEY_SYNCED_AT, String.valueOf(syncedAt));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void add(int account, String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("track_id", trackId);
        values.put("added_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                TABLE_LIKES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void remove(int account, String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return;
        }
        getWritableDatabase().delete(
                TABLE_LIKES,
                "account = ? AND track_id = ?",
                new String[]{String.valueOf(account), trackId});
    }

    private static void putMeta(SQLiteDatabase db, int account, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("key", key);
        values.put("value", value);
        db.insertWithOnConflict(TABLE_META, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
