# Vimu timecode sync (experimental)

Source: Android TorrServe MatriX.145.Client. TorrServer stays MatriX.146.

## Design

- NUM continues to open the TorrServe Android client.
- TorrServe reads `POST /viewed` before playback, keyed by `hash + file_index`.
- Vimu is launched with `startfrom` in **milliseconds** and `forceresume=false`.
- A transparent activity owns the Vimu activity result while the original PlayActivity
  still finishes and reports to NUM as before.
- For playlists, the result URL's `link` and `index` query parameters identify the
  exact episode. Without a reliable URL, we do not overwrite a potentially different
  episode; single-file torrents can fall back to their selected index.
- A broadcast receiver sends a valid position to TorrServer as seconds via
  `Api.setViewed(hash, fileId, positionMs / 1000.0)`.
- Result code 1 and >=96%-complete playback write 0 as a completed item. Error and
  invalid / zero-length interruptions do **not** erase previously saved progress.

## Build

GitHub Actions: `Vimu Sync Debug APK`. The **debug** app uses package
`ru.yourok.torrserve.sync`, installs beside the original client, and appears as
"TorrServe Sync (TEST)". It uses a debug-only Firebase config solely to satisfy
Gradle plugin requirements; telemetry collection is disabled in the debug manifest.

## SK-1 test

1. In TEST client settings, configure the existing TorrServer URL. Do not uninstall
   the original client. Select Vimu.
2. Choose the TEST client once when NUM asks which app should handle the torrent.
3. Start one episode, stop by Back at 35:20, then verify the matching `file_index`
   using `POST /viewed` with `{"action":"list","hash":"..."}`.
4. Restart the same episode on TV box 2 with TEST client and Vimu. Confirm resume.
5. Switch episodes inside Vimu, exit by Back, verify **only the last episode**
   received the updated timecode.
6. Check `adb logcat -s VimuSync:I PlaybackPosition:V` for the result, and test
   early exit, completion, playback error and missing network independently.

## Known limitations

- No periodic player progress API. Force-close, sudden power loss and pressing Home
  without actually exiting Vimu may not trigger an activity result.
- Vimu may still use local filename-based resume on some configurations; validate
  the startfrom/forceresume interaction on installed Vimu 12.50.
- MatriX /viewed has no atomic compare-and-swap or timestamps; concurrent play on
  two boxes is last-write-wins. No reliable conflict arbitration without server work.
- Builds and physical devices must be tested; source changes alone do not prove success.

API: https://vimu.tv/player-api/
