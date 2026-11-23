package ru.vtosters.lite.music.spotify;

public final class SpotifyTrack {
    public final String id;
    public final String title;
    public final String artist;
    public final int durationMs;
    public final boolean local;
    public final String type;

    public SpotifyTrack(String id, String title, String artist, int durationMs, boolean local, String type) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.local = local;
        this.type = type;
    }

    public int durationSeconds() {
        return durationMs / 1000;
    }

    public boolean usable() {
        return !local && "track".equals(type) && id != null && !id.isEmpty()
                && title != null && !title.isEmpty()
                && artist != null && !artist.isEmpty();
    }
}
