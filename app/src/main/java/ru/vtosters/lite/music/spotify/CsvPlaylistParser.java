package ru.vtosters.lite.music.spotify;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CsvPlaylistParser {
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private CsvPlaylistParser() {
    }

    public static final class Result {
        public final String name;
        public final List<SpotifyTrack> tracks;

        Result(String name, List<SpotifyTrack> tracks) {
            this.name = name;
            this.tracks = tracks;
        }
    }

    public static Result parse(String fileName, InputStream input) throws Exception {
        String text = read(input);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        List<List<String>> rows = split(text);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV пустой");
        }

        int titleIndex = -1;
        int artistIndex = -1;
        int durationIndex = -1;
        int headerRow = -1;

        for (int r = 0; r < Math.min(rows.size(), 8); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String value = row.get(c).toLowerCase(Locale.ROOT).trim();
                boolean uri = value.contains("uri");

                if (!uri && titleIndex < 0 && (value.equals("track name") || value.equals("title") || value.equals("name"))) {
                    titleIndex = c;
                } else if (!uri && artistIndex < 0 && value.contains("artist name")) {
                    artistIndex = c;
                } else if (!uri && durationIndex < 0 && value.contains("duration")) {
                    durationIndex = c;
                }
            }
            if (titleIndex >= 0 && artistIndex >= 0) {
                headerRow = r;
                break;
            }
            titleIndex = -1;
            artistIndex = -1;
            durationIndex = -1;
        }

        if (titleIndex < 0) {
            titleIndex = 0;
            artistIndex = 1;
            headerRow = -1;
        } else if (artistIndex < 0) {
            if (headerRow >= 0) {
                List<String> header = rows.get(headerRow);
                for (int c = 0; c < header.size(); c++) {
                    String value = header.get(c).toLowerCase(Locale.ROOT).trim();
                    if (!value.contains("uri") && value.contains("artist")) {
                        artistIndex = c;
                        break;
                    }
                }
            }
            if (artistIndex < 0) {
                titleIndex = 0;
                artistIndex = 1;
                headerRow = -1;
            }
        }

        List<SpotifyTrack> tracks = new ArrayList<>();
        int start = headerRow < 0 ? 0 : headerRow + 1;
        int index = 0;

        for (int r = start; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() <= Math.max(titleIndex, artistIndex)) {
                continue;
            }

            String title = clean(row.get(titleIndex));
            String artist = clean(row.get(artistIndex));
            if (title.isEmpty() || artist.isEmpty()) {
                continue;
            }

            int durationMs = 0;
            if (durationIndex >= 0 && durationIndex < row.size()) {
                durationMs = parseDuration(row.get(durationIndex));
            }

            tracks.add(new SpotifyTrack(
                    "csv:" + index++ + ":" + title,
                    title,
                    artist,
                    durationMs,
                    false,
                    "track"
            ));
        }

        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("В CSV не найдено треков");
        }

        return new Result(playlistName(fileName), tracks);
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) {
                throw new IllegalArgumentException("CSV слишком большой");
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String playlistName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "Spotify CSV";
        }
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        name = name.replace('_', ' ').trim();
        return name.isEmpty() ? "Spotify CSV" : name;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parseDuration(String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":");
            try {
                long total = 0;
                for (String part : parts) {
                    total = total * 60 + Long.parseLong(part.trim());
                }
                return (int) Math.min(total * 1000, Integer.MAX_VALUE);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(digits.toString());
            if (parsed < 10_000) {
                parsed *= 1000;
            }
            return (int) Math.min(parsed, Integer.MAX_VALUE);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<List<String>> split(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (!row.isEmpty()) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }

        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
