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
