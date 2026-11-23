package ru.vtosters.lite.music.spotify;

public final class SpotifyConfig {
    public static final String CLIENT_ID = "e8dc2fe6ae6c43078aec9aeb120e36f0";
    public static final String REDIRECT_URI = "vkataxa://spotify-callback";
    public static final String SCOPES = "playlist-read-private playlist-read-collaborative";
    public static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    public static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    public static final String API_URL = "https://api.spotify.com/v1";

    private SpotifyConfig() {
    }
}
