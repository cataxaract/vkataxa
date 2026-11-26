// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.spotify;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.vk.dto.music.MusicTrack;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MU-10: persistent import journal. Matching results, target playlist and added VK ids are stored
 * per import key so captcha/restart does not lose found tracks.
 */
final class ImportProgressDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "music_import.db";
    private static final int DB_VERSION = 4;
    private static final long ACTIVE_STALE_MS = 5L * 60 * 1000;
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

    static final class PausedJob {
        final String key;
        final String status;
        final int ownerId;
        final int playlistId;
        final int total;
        final int added;
        final int matched;
        final int miss;
        final String label;

        PausedJob(String key, String status, int ownerId, int playlistId,
                  int total, int added, int matched, int miss, String label) {
            this.key = key;
            this.status = status;
            this.ownerId = ownerId;
            this.playlistId = playlistId;
            this.total = total;
            this.added = added;
            this.matched = matched;
            this.miss = miss;
            this.label = label == null ? "" : label;
        }
    }

    ImportProgressDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
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
                + "label TEXT NOT NULL DEFAULT '',"
                + "PRIMARY KEY(account, key))");
        createItems(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            String oldTable = TABLE_ITEMS + "_v1";
            db.execSQL("ALTER TABLE " + TABLE_ITEMS + " RENAME TO " + oldTable);
            createItems(db);
            db.execSQL("INSERT OR REPLACE INTO " + TABLE_ITEMS
                    + " (account, key, item_key, track_uid, state, track_json, updated_at)"
                    + " SELECT account, key, track_uid, track_uid, 'added', '', updated_at FROM "
                    + oldTable);
            db.execSQL("DROP TABLE " + oldTable);
        } else if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_ITEMS + " ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_ITEMS + " ADD COLUMN last_error TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE_ITEMS + " ADD COLUMN last_attempt INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_JOBS + " ADD COLUMN label TEXT NOT NULL DEFAULT ''");
        }
    }

    private static void createItems(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_ITEMS + " ("
                + "account INTEGER NOT NULL,"
                + "key TEXT NOT NULL,"
                + "item_key TEXT NOT NULL,"
                + "track_uid TEXT NOT NULL,"
                + "state TEXT NOT NULL,"
                + "track_json TEXT NOT NULL,"
                + "attempts INTEGER NOT NULL DEFAULT 0,"
                + "last_error TEXT NOT NULL DEFAULT '',"
                + "last_attempt INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(account, key, item_key))");
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

    void touch(int account, String key) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TABLE_JOBS, values, "account = ? AND key = ?",
                new String[]{String.valueOf(account), key});
    }

    void setLabel(int account, String key, String label) {
        ContentValues values = new ContentValues();
        values.put("label", label == null ? "" : label);
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
                "account = ? AND key = ? AND state = ?",
                new String[]{String.valueOf(account), key, "added"},
                null, null, null)) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        }
        return result;
    }

    Set<String> completed(int account, String key, long missCutoff) {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_ITEMS,
                new String[]{"item_key"},
                "account = ? AND key = ? AND (state IN (?, ?, ?) OR (state = ? AND last_attempt >= ?))",
                new String[]{
                        String.valueOf(account), key,
                        "matched", "add_intent", "added", "miss", String.valueOf(missCutoff)
                },
                null, null, null)) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        }
        return result;
    }

    Map<String, MusicTrack> matched(int account, String key) {
        Map<String, MusicTrack> result = new HashMap<>();
        java.util.List<String> corrupt = new java.util.ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_ITEMS,
                new String[]{"item_key", "track_json", "state"},
                "account = ? AND key = ? AND state IN (?, ?, ?)",
                new String[]{String.valueOf(account), key, "matched", "add_intent", "added"},
                null, null, null)) {
            while (cursor.moveToNext()) {
                String itemKey = cursor.getString(0);
                String state = cursor.getString(2);
                MusicTrack track = parse(cursor.getString(1));
                if (track != null) {
                    result.put(itemKey, track);
                } else if (!"added".equals(state)) {
                    // BUG-21: markAdded may create an "added" row without track_json; resetting it
                    // to pending would make the next resume search and add the track again.
                    corrupt.add(itemKey);
                }
            }
        }
        for (String itemKey : corrupt) {
            resetPending(account, key, itemKey);
        }
        return result;
    }

    private static String toJson(MusicTrack track) {
        try {
            JSONObject json = track.J();
            return json == null ? "" : json.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    void saveMatched(int account, String key, String itemKey, MusicTrack track) {
        if (track == null) {
            return;
        }
        String json = toJson(track);
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("key", key);
        values.put("item_key", itemKey);
        values.put("track_uid", track.y1());
        values.put("state", json.isEmpty() ? "pending" : "matched");
        values.put("track_json", json);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void saveIntent(int account, String key, String itemKey, MusicTrack track) {
        if (track == null) {
            return;
        }
        String json = toJson(track);
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("key", key);
        values.put("item_key", itemKey);
        values.put("track_uid", track.y1());
        values.put("state", json.isEmpty() ? "pending" : "add_intent");
        values.put("track_json", json);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void saveMiss(int account, String key, String itemKey) {
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("key", key);
        values.put("item_key", itemKey);
        values.put("track_uid", "");
        values.put("state", "miss");
        values.put("track_json", "");
        values.put("attempts", attempts(account, key, itemKey) + 1);
        values.put("last_error", "");
        values.put("last_attempt", System.currentTimeMillis());
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private int attempts(int account, String key, String itemKey) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_ITEMS,
                new String[]{"attempts"},
                "account = ? AND key = ? AND item_key = ?",
                new String[]{String.valueOf(account), key, itemKey},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }

    void resetPending(int account, String key, String itemKey) {
        ContentValues values = new ContentValues();
        values.put("state", "pending");
        values.put("attempts", 0);
        values.put("last_error", "");
        values.put("last_attempt", 0L);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TABLE_ITEMS, values, "account = ? AND key = ? AND item_key = ?",
                new String[]{String.valueOf(account), key, itemKey});
    }

    void markAdded(int account, String key, String itemKey, String trackUid) {
        ContentValues values = new ContentValues();
        values.put("state", "added");
        values.put("track_uid", trackUid == null ? "" : trackUid);
        values.put("updated_at", System.currentTimeMillis());
        int updated = getWritableDatabase().update(
                TABLE_ITEMS, values, "account = ? AND key = ? AND item_key = ?",
                new String[]{String.valueOf(account), key, itemKey});
        if (updated == 0) {
            values.put("account", account);
            values.put("key", key);
            values.put("item_key", itemKey);
            values.put("track_json", "");
            getWritableDatabase().insertWithOnConflict(
                    TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    List<PausedJob> paused(int account) {
        List<PausedJob> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        long cutoff = now - 30L * 24 * 60 * 60 * 1000;
        long staleCutoff = now - ACTIVE_STALE_MS;
        // BUG-20: a job left in matching/adding after force-stop has no updates; expose it as paused.
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_JOBS,
                new String[]{"key", "status", "target_owner", "target_playlist", "label"},
                "account = ? AND updated_at >= ? AND (status LIKE 'paused%'"
                        + " OR (status IN (?, ?) AND updated_at < ?))",
                new String[]{String.valueOf(account), String.valueOf(cutoff),
                        "matching", "adding", String.valueOf(staleCutoff)},
                null, null, "updated_at DESC", "20")) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                result.add(new PausedJob(
                        key,
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getInt(3),
                        count(account, key, null),
                        count(account, key, "added"),
                        count(account, key, "matched") + count(account, key, "add_intent"),
                        count(account, key, "miss"),
                        cursor.getString(4)
                ));
            }
        }
        return result;
    }

    private int count(int account, String key, String state) {
        String selection = "account = ? AND key = ?";
        String[] args;
        if (state == null) {
            args = new String[]{String.valueOf(account), key};
        } else {
            selection += " AND state = ?";
            args = new String[]{String.valueOf(account), key, state};
        }
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_ITEMS, new String[]{"COUNT(*)"}, selection, args, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    void retryMisses(int account, String key) {
        ContentValues values = new ContentValues();
        values.put("state", "pending");
        values.put("attempts", 0);
        values.put("last_error", "");
        values.put("last_attempt", 0L);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TABLE_ITEMS, values, "account = ? AND key = ? AND state = ?",
                new String[]{String.valueOf(account), key, "miss"});
    }

    void clear(int account, String key) {
        getWritableDatabase().delete(
                TABLE_JOBS, "account = ? AND key = ?", new String[]{String.valueOf(account), key});
        getWritableDatabase().delete(
                TABLE_ITEMS, "account = ? AND key = ?", new String[]{String.valueOf(account), key});
    }

    private static MusicTrack parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return new MusicTrack(new JSONObject(json));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
