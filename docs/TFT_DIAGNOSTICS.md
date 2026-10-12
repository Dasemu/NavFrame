# Persistent TFT map diagnostics

Starting with NavFrame 0.13.2, map-render failures and the first successful render after a failure are saved automatically on the phone. The history does not depend on Logcat or on keeping the service alive. Completed writes are retained across app/service restarts and a phone reboot, including a shutdown caused by an empty battery.

## Export after an incident

When stationary, open **Menu → Information and licenses → TFT map diagnosis → Share**. In the Spanish UI this is **☰ → Información y licencias → Diagnóstico del mapa TFT → Compartir**.

The shared text includes the current service diagnostic, when available, and the saved history. History remains available when no service is bound. Save it before reinstalling the app or clearing app data. **Clear history** removes saved events after confirmation; it does not stop navigation or erase the current in-memory service diagnostic.

## Recorded information

Each event contains its UTC timestamp, app version, Android API level, MapLibre version, failure/recovery type, failure category and render stage, classified resource/reason and HTTP error status if available, render duration, retry delay/backoff attempt, active source type (`LOCAL` or `ONLINE`), observed connectivity, navigation mode/status, and TFT connection state.

The backoff attempt is capped at four, like the renderer's existing retry policy. Repeated failures continue to create separate timestamped records after that limit. Only the first successful render following failures creates a recovery record; normal successful frames are not logged.

Raw SDK messages, stack traces, URLs, tokens, coordinates, route geometry, destinations, search text, and Bluetooth identifiers are excluded. The history contains fixed labels and numbers, not a copy of Logcat. Network state is diagnostic context: a disconnected network does not itself explain a failure of a selected local map.

## Storage and limits

The history is a private app file named `tft-map-diagnostics.json` in Android's `noBackupFilesDir`. No storage permission is required; the app does not upload it automatically, and it is excluded from backup/device transfer. The file retains the latest 100 failure/recovery events and is bounded at 128 KiB. Older events are discarded when the limit is reached.

File operations run on an IO dispatcher. Writes are synced and replaced atomically, so an interrupted replacement preserves the previous completed history. A failure that occurs while a shutdown interrupts its write may not be retained. Clearing app data or uninstalling NavFrame deletes the history.

If reading fails, the diagnostic dialog reports that the history is unavailable and never exports the unreadable file as text. The next record replaces an unreadable history with a valid event. If storage cannot be written, navigation/error-frame handling continues; the current service diagnostic and Logcat report `TFT_MAP_HISTORY_WRITE_FAILED`.

## What this cannot recover

Events from before installing 0.13.2 cannot be reconstructed. This is a record of failures handled by NavFrame's TFT rendering path, not a system-wide crash recorder: abrupt native process crashes and power loss before a failure is recorded may leave no new event. A recovery record means image generation succeeded, not that delivery to the physical TFT was verified. The history helps investigate incidents; it does not establish their root cause by itself.

Tests cover reopening persisted events, retention, interrupted replacement, malformed/oversized input, unavailable storage, service failure followed by recovery/recreation, and sharing/clearing saved history without a bound service. Physical power-loss behavior and MapLibre OpenGL/TFT rendering still need device validation.
