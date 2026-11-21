## vX.Y.Z

### Added
- Deleted messages are now shown with 55% opacity instead of a trash icon.
- Photos from deleted messages keep their original display size.
- Deleted photos can be saved to the gallery using the standard **Save** action in the photo viewer. Files are stored in `Pictures/vkataxa`.
- Deleted messages can be forwarded as regular text: the message text is copied into a new message instead of being sent as a server-side forward.

### Improved
- Deleted photos are downloaded locally and restored from the app's private storage.
- Existing saved photos are migrated to the new filename format, so they can also be exported to the gallery.
- Added null-safety for nested messages, missing senders and empty message bodies.
- Updated the README for this fork: removed direct links to VTosters projects, kept only the note that the project is based on VTosters Lite.

### Fixed
- Fixed potential crashes when opening old chats containing deleted or forwarded messages from blocked or unavailable users.
- Fixed photo viewer menu handling that could prevent some standard actions from working.
- Prevented crashes when parsing messages with missing encryption data or empty text.

### Build
- APK output is now placed in a separate directory: `build-output/VTLite.apk`.
- The project is pinned to Gradle 7.5.1 and JDK 17; NDK 25.2.9519653 is required.
- CI now uses `./gradlew` instead of the system Gradle.
- Build performance improved: Gradle caching, parallel execution and VFS watching enabled, unused Jetifier disabled, and the apktool heap increased.

### Known limitations
- Forwarding a deleted message copies text only. Photos and other attachments are not forwarded automatically.
- Gallery export is available for photos that have already been restored locally after message deletion.
