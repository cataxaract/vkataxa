// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.nav;

/**
 * Navigation facade (F-03). The new UI will replace the implementation, callers stay unchanged.
 * Implementation TODO(F-03): build on existing VK intents/fragments (see UI-BE-01, MU-04).
 */
public interface Navigator {

    void openMusicHome();

    void openMusicWave();

    void openPlaylist(int id);

    void openDialog(int peerId);

    void openArtist(String artistId);
}
