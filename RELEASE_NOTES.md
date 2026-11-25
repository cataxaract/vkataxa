## v1.1.0

### Added
- Local music import: scan tracks from the device, match them with VK and add only the missing ones to "My music" (dedup against `audio.get`).
- Spotify import improvements: batched search (up to 10 queries per `execute`), better title/artist/duration matching, progress with the current track and per-track statistics (added / already present / not found / errors).
- Captcha placeholder handling while VK asks for confirmation during import.

### Changed
- The own music playlist is now labelled "Понравившиеся" (ru) / "Liked" (default) instead of "My playlist".
- Developer builds can toggle feature flags from Settings → For developers (temporary dev-menu item).
- Release builds are always `zipalign`-ed and verified with `apksigner`; a missing `zipalign` now fails the build instead of being silently skipped.

### Fixed
- Translation via DeepL could hang forever on any text containing "i" (infinite `Matcher` loop) — fixed.
- The news feed could crash while filtering a post with unexpected JSON — a failed filter now keeps the post and logs the error.
- Deleting a message from the local DB could delete same-id messages in other chats; now scoped to `(dialog_id, vk_id)`.
- Deleted-message markers are deduplicated and scoped per account: no more growth with `localId = null` and no leakage between profiles.
- DNR/DNT ("do not read"/"do not type") settings are now stored per account; old rows are migrated.
- Saving a deleted photo or clearing deleted messages no longer depends on a non-null current activity.
- Music cache migration runs in a single transaction, so an interrupted upgrade no longer loses the cache.
- Settings edited in text dialogs are now written to the same preferences file the mod reads.
- Message actions no longer crash on stickers/photos without text.
- HTTP responses in users/verification lists are closed and lists are re-filled instead of growing.

### Security
- VK API requests no longer put `access_token` into URLs: the token is sent in the `Authorization: Bearer` header.
- Spotify OAuth tokens are encrypted with AES/GCM in the Android Keystore (with migration from the old plaintext prefs); the Spotify client id comes from `BuildConfig`.
- StrictMode no longer permits network on every thread: UI-thread disk/network detectors are enabled in log mode.

### Known limitations
- VK captcha (error 14) still cannot be solved in this client. Large CSV/Spotify imports may stop with a captcha request; the honest stop + resumable queue is not finished yet, so very large imports should be split manually.
- "Do not read"/"do not type" rows created before this version are assigned to the account that first opens the database after the update.
- Photos of deleted messages are still stored in one shared folder keyed by message id and are not separated per account yet.
- Spotify OAuth import still requires Premium for the owner of the Spotify application; use CSV import otherwise.

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
