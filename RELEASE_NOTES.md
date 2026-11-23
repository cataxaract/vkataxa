## v1.0.7

### Added
- Added the **My Wave** tab to the music section: VK recommendations via `audio.getRecommendations`, cached queues, automatic refresh and play/pause control.
- Added a Last.fm fallback (`track.getSimilar` + VK search) for cases where VK returns no recommendations or the method is unavailable.
- Added music settings for the recommendation source: Auto, VK or Last.fm.
- Added a Spotify import screen:
  - OAuth Authorization Code Flow with PKCE for Spotify accounts where the app owner has Premium;
  - CSV import without Premium (for example, a playlist exported with Exportify);
  - VK track matching, playlist creation, repeated-import synchronization and playlist deletion.
- Added a compact, theme-aware My Wave layout with mood filters, native VK track rows and a round play/pause button that switches to pause bars while music is playing.

### Changed
- The music settings screen now contains the recommendation source and Spotify import entry.
- The My Wave UI follows the current light/dark theme and accent color instead of being tied to AMOLED + green.

### Fixed
- Spotify import now uses the correct `execute.savePlaylist` response field for the new playlist id.
- Failed playlist imports clean up the partially created VK playlist.
- CSV entries can be synchronized and removed together with their VK playlist.

### Known limitations
- Direct Spotify OAuth import requires an active Premium subscription for the owner of the Spotify application. Without it, use the CSV import path.
- Last.fm fallback depends on Last.fm API availability and may have worse coverage for rare tracks.
- CSV playlist names are taken from the file name; importing a file with the same name updates the existing VK playlist.

## v1.0.6

### Added
- Deleted messages now show a red dot next to the bubble, reusing the unread-message indicator style.
- The photo viewer now offers a "Download" action for deleted photos, including photos that were restored from the local deleted-message cache.
- Added per-chat cleanup: in the chat header or dialog list menu, "Clear deleted messages" removes saved markers, restored photos and local copies of deleted messages for that chat or group. Regular messages are not touched.

### Fixed
- Saving a deleted photo no longer depends on the standard save menu item being present; the custom save action appears when a local deleted photo exists.
- The deleted-photo save confirmation now uses the localized "Photo saved" string instead of a hardcoded message.
- The deleted-message dot now renders red instead of the theme accent: `RecoloredDrawable` now applies `setTint`/`setTintList`, so the status indicator can be recolored.

### Improved
- Nested messages are decrypted only for deleted messages, and the whole deleted-message parser exits early when anti-delete is disabled; loading old history no longer performs unnecessary decryption work.
- README updated with the new deleted-photo download and per-chat cleanup options.
