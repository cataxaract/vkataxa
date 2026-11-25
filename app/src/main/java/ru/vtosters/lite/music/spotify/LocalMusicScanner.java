package ru.vtosters.lite.music.spotify;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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

    private LocalMusicScanner() {
    }

    public static File defaultFolder() {
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
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
        List<SpotifyTrack> result = scanMediaStore(context);
        if (!result.isEmpty()) {
            return result;
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
            selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"Music/spotify/%"};
        } else {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME
            };
            selection = MediaStore.Audio.Media.DATA + " LIKE ?";
            args = new String[]{"%/Music/spotify/%"};
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

    private static List<SpotifyTrack> scanFiles(File folder) {
        List<SpotifyTrack> result = new ArrayList<>();
        if (folder == null || !folder.isDirectory()) {
            return result;
        }

        List<File> files = new ArrayList<>();
        collect(folder, files);
        files.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));

        for (File file : files) {
            SpotifyTrack track = read(file);
            if (track != null && track.usable()) {
                result.add(track);
            }
        }
        return result;
    }

    private static void collect(File folder, List<File> files) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isHidden()) {
                continue;
            }
            if (child.isDirectory()) {
                collect(child, files);
            } else if (isAudio(child)) {
                files.add(child);
            }
        }
    }

    private static boolean isAudio(File file) {
        String name = file.getName();
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

            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            title = clean(title);
            artist = clean(artist);

            String baseName = baseName(file);
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

            return new SpotifyTrack(
                    "local:" + file.getAbsolutePath(),
                    title,
                    artist,
                    durationMs,
                    true,
                    "local"
            );
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
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
