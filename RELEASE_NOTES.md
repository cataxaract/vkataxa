## v1.0.4

### Fixed
- Deleted messages are now matched by the local message id (with the dialog id used to resolve server ids), so identical `vk_id` values in different chats no longer mark unrelated messages as deleted.
- Resolving a deleted message no longer uses an ambiguous `SELECT *` by `vk_id`; cursors are closed and only the needed column is read.
- The deleted-messages database no longer accumulates duplicate rows: schema v2 adds `peerId`/`localId`, deduplicates old records and uses a unique index for new ones.
- The crash reporter now passes immutable `PendingIntent` flags, cannot fail while reporting a crash, and always calls the original uncaught-exception handler.
- Crash log saving now uses MediaStore on Android 10+ (writing to `Download/VK/logs/com.vtosters.lite`) and no longer crashes when the intent extra is missing.
- Auto-update now checks `cataxaract/vkataxa` instead of the archived upstream repository, selects the APK asset explicitly, closes HTTP responses and unregisters the DownloadManager receiver.
- Photo copying no longer performs two network requests, closes its response and reports failures on the UI thread.
- Fixed the circular image clipping radius for non-square images.
- The SOCKS proxy port is now reset correctly.
- Translation no longer crashes when no translator is selected; Google Translate responses are closed.
- Analytics calls are skipped until AppCenter is actually started.
- Playlist thumbnail failures no longer throw from a background callback.

### Improved
- Added a cache for plain-text messages so repeated encryption checks are skipped during message list rendering.
- Deleted-message transparency is updated only when the alpha value actually changes.
- Photo restore avoids duplicate image-list lookups and repeated attachment id reads.
- Deleted-message transparency checks the local message id first; `vk_id` matching is kept as a fallback for old saved records and nested forwarded messages.
- Deleted-message ids are stored in concurrent sets and the deleted-messages database is opened once instead of being closed after every write.
- Encryption rendering strips the lock prefix without regex and no longer performs duplicate translation calls.

### Build
- GitHub Actions updated: `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 17) and `actions/upload-artifact@v4`.
- Windows `generateRjava` now runs the `.bat` script.
- Windows builds zipalign the APK before signing when `zipalign` is available.
- `version.properties` is regenerated on every build and is now present in the APK, keeping version and OTA metadata accurate.
- Removed a hardcoded release AIDL source directory that caused duplicate `ILibVkxService` classes in beta/rc builds.
- Added `APPCENTER_KEY` to the default config so the debug variant also compiles.

### Known limitations
- Old `deleted_msgs` rows that contain only a server message id are still matched by that id until the message is deleted again, because the old schema did not store the dialog/local id.
- Forwarding a deleted message still copies text only.
- Translations are still requested synchronously by the legacy translator cache; moving them off the UI thread is a separate change.
