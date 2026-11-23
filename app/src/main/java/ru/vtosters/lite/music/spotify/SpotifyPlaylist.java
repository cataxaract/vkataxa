package ru.vtosters.lite.music.spotify;

public final class SpotifyPlaylist {
    public final String id;
    public final String name;
    public final String owner;
    public final String imageUrl;
    public final int trackCount;
    public final String snapshotId;

    public SpotifyPlaylist(String id, String name, String owner, String imageUrl, int trackCount, String snapshotId) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.imageUrl = imageUrl;
        this.trackCount = trackCount;
        this.snapshotId = snapshotId;
    }
}
