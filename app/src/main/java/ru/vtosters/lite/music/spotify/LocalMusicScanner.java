package ru.vtosters.lite.music.spotify;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LocalMusicScanner {
    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "webm"
    ));
    private static final int MAX_SCAN_FILES = 5000;
    private static final int MAX_SCAN_DEPTH = 8;
    private static final long MAX_SCAN_MS = 60_000L;

    private LocalMusicScanner() {
    }

    public static File defaultFolder() {
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (music.isDirectory()) {
            return music;
        }
        File lower = new File(music, "spotify");
        if (lower.isDirectory()) {
            return lower;
        }
        File upper = new File(music, "Spotify");
        if (upper.isDirectory()) {
            return upper;
        }
        return lower;
    }

    public static List<SpotifyTrack> scan(Context context, File folder) {
        // MediaStore covers Music/ and Download/ and is much faster than reading every file's tags.
        List<SpotifyTrack> media = scanMediaStore(context);
        if (!media.isEmpty()) {
            return media;
        }
        return scanFiles(folder);
    }

    private static List<SpotifyTrack> scanMediaStore(Context context) {
        List<SpotifyTrack> result = new ArrayList<>();
        if (context == null) {
            return result;
        }

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection;
        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME
            };
            selection = "(" + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? OR "
                    + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?)";
            args = new String[]{"Music/%", "Download/%"};
        } else {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME
            };
            selection = "(" + MediaStore.Audio.Media.DATA + " LIKE ? OR "
                    + MediaStore.Audio.Media.DATA + " LIKE ?)";
            args = new String[]{"%/Music/%", "%/Download/%"};
        }

        try (Cursor cursor = context.getContentResolver().query(uri, projection, selection, args, null)) {
            if (cursor == null) {
                return result;
            }
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

            while (cursor.moveToNext()) {
                String title = clean(cursor.getString(titleIndex));
                String artist = clean(cursor.getString(artistIndex));
                long duration = cursor.getLong(durationIndex);
                String displayName = clean(cursor.getString(nameIndex));
                if (title.isEmpty()) {
                    title = baseName(displayName);
                }
                if (title.isEmpty()) {
                    continue;
                }
                result.add(new SpotifyTrack(
                        "local:ms:" + cursor.getLong(idIndex),
                        title,
                        artist,
                        (int) duration,
                        true,
                        "local"
                ));
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    public static List<SpotifyTrack> scanTree(Context context, Uri treeUri) {
        List<SpotifyTrack> result = new ArrayList<>();
        if (context == null || treeUri == null) {
            return result;
        }
        long deadline = System.currentTimeMillis() + MAX_SCAN_MS;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            collectTree(context, treeUri, rootId, result, 0, deadline);
        } catch (Throwable ignored) {
        }
        result.sort(Comparator.comparing(track -> track.title, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static void collectTree(Context context, Uri treeUri, String documentId,
                                    List<SpotifyTrack> result, int depth, long deadline) {
        if (depth > MAX_SCAN_DEPTH || result.size() >= MAX_SCAN_FILES
                || System.currentTimeMillis() > deadline) {
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = context.getContentResolver().query(
                children,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()
                    && result.size() < MAX_SCAN_FILES
                    && System.currentTimeMillis() <= deadline) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (id == null || name == null || name.startsWith(".")) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectTree(context, treeUri, id, result, depth + 1, deadline);
                    continue;
                }
                if (!isAudio(name) && (mime == null || !mime.startsWith("audio/"))) {
                    continue;
                }
                SpotifyTrack track = read(context, treeUri, id, name);
                if (track != null && track.usable()) {
                    result.add(track);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<SpotifyTrack> scanFiles(File folder) {
        List<SpotifyTrack> result = new ArrayList<>();
        if (folder == null || !folder.isDirectory()) {
            return result;
        }

        List<File> files = new ArrayList<>();
        collect(folder, files, 0, System.currentTimeMillis() + MAX_SCAN_MS);
        files.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));

        for (File file : files) {
            SpotifyTrack track = read(file);
            if (track != null && track.usable()) {
                result.add(track);
            }
        }
        return result;
    }

    private static void collect(File folder, List<File> files, int depth, long deadline) {
        if (depth > MAX_SCAN_DEPTH || files.size() >= MAX_SCAN_FILES
                || System.currentTimeMillis() > deadline) {
            return;
        }
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (files.size() >= MAX_SCAN_FILES || System.currentTimeMillis() > deadline) {
                return;
            }
            if (child.isHidden()) {
                continue;
            }
            if (child.isDirectory()) {
                collect(child, files, depth + 1, deadline);
            } else if (isAudio(child)) {
                files.add(child);
            }
        }
    }

    private static boolean isAudio(File file) {
        return isAudio(file.getName());
    }

    private static boolean isAudio(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) {
            return false;
        }
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static SpotifyTrack read(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return buildTrack(retriever, "local:" + file.getAbsolutePath(), baseName(file));
        } catch (Throwable ignored) {
            return null;
        } finally {
            release(retriever);
        }
    }

    private static SpotifyTrack read(Context context, Uri treeUri, String documentId, String name) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            retriever.setDataSource(context, uri);
            return buildTrack(retriever, "local:saf:" + uri, baseName(name));
        } catch (Throwable ignored) {
            return null;
        } finally {
            release(retriever);
        }
    }

    private static SpotifyTrack buildTrack(MediaMetadataRetriever retriever, String id, String baseName) {
        String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

        title = clean(title);
        artist = clean(artist);

        if (title.isEmpty()) {
            int dash = baseName.indexOf(" - ");
            if (dash > 0) {
                if (artist.isEmpty()) {
                    artist = clean(baseName.substring(0, dash));
                }
                title = clean(baseName.substring(dash + 3));
            } else {
                title = baseName;
            }
        }
        if (title.isEmpty()) {
            return null;
        }

        int durationMs = 0;
        if (duration != null) {
            try {
                durationMs = Integer.parseInt(duration.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        return new SpotifyTrack(id, title, artist, durationMs, true, "local");
    }

    private static void release(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Throwable ignored) {
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)[_\\- ]*spotdown\\.org", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String baseName(File file) {
        return baseName(file.getName());
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return clean(dot > 0 ? name.substring(0, dot) : name);
    }
}
