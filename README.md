# Bunny Player

An Android IPTV player for **Xtream Codes** providers such as [mybunny.tv](https://mybunny.tv).
It runs on phones, tablets and Android TV / Fire TV boxes.

## Features

- **Sign in** with server URL, username and password, or paste an M3U link (`…/get.php?username=…&password=…`) and the fields fill in for you.
- **Favorites first**: the app opens on your favorites when you have some. To add a channel, **long-press it** anywhere (or tap ☆). While watching, use the ☆ in the overlay or the red/bookmark button on a TV remote.
- **Live TV** shows the provider's own categories (countries, sports…) in the server's order, plus "All channels" and "Recently watched". **Pin** 📌 categories (e.g. Poland) to keep them at the top.
- **Search** across the whole catalog. Matching ignores case and accents and accepts words in any order ("one bbc" finds "UK: BBC One HD").
- **Favorites** can be reordered. The order is the zapping and failover order.
- **My lists**: create, rename, delete and reorder your own lists ("Sports", "News"…) and add channels from search.
- **Player** (ExoPlayer / Media3):
  - Switch channels with swipe up/down, the ⏮ ⏭ buttons, or the remote (CH+/CH−, D-pad up/down).
  - Open the side channel list with the list button or OK on a remote.
  - Shows the current programme ("Now") and the next one from the provider's EPG.
  - Aspect modes: Fit, Zoom, Stretch. You can also favorite the channel you're watching.
- **Automatic failover when a channel is off**:
  1. The same stream is retried once.
  2. The app tries other feeds of the same channel from the catalog. For example, if `US: ESPN HD` is down it plays `ESPN FHD` or `ESPN ᴴᴰ (Backup)`.
  3. If none of those work, it moves to the next channel in the list you're watching. This is why the order of your favorites and lists matters.

  A channel also counts as off if it keeps buffering (8 / 15 / 30 s, set in Settings). Each step can be turned off in **Settings → When a channel is off**.
- Settings also cover the stream format (MPEG-TS or HLS) and account info (status, expiry date, connections).

The channel list is cached on the device and refreshes every 12 hours, or when you pull to refresh. Favorites and lists are stored locally in `library.json`.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or open the project in Android Studio
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified; signed with the debug key for sideloading
```

Install on a device or Fire TV with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## CI: build and publish to PicoShare

`.github/workflows/build.yml` runs the unit tests and builds the release APK on every push to `main`, then publishes it to PicoShare on the VPS:

1. The APK is copied over SSH (`VPS_HOST`, `VPS_SSH_KEY`; optional `VPS_USER` (default `debian`) and `VPS_PORT`).
2. [`scripts/picoshare-upload.sh`](scripts/picoshare-upload.sh) runs on the VPS. It reads PicoShare's shared secret from the pod (namespace `files`) and uploads the APK through a `kubectl port-forward`.
3. The script then swaps the upload into the existing **`android.all.apk`** entry. It moves the new file chunks under the old entry ID in PicoShare's SQLite DB, so **the download link never changes** and always serves the latest build.

Optional secrets for a stable signing key, so updates install over the previous version without uninstalling:

```bash
keytool -genkeypair -keystore release.keystore -alias bunny -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # → secret SIGNING_KEYSTORE_BASE64; the password → secret SIGNING_PASSWORD
```

## Project layout

```
app/src/main/java/com/mwalczak/player/
├── PlayerApp.kt            # app container (OkHttp, stores, repositories)
├── data/
│   ├── XtreamClient.kt     # player_api.php calls
│   ├── XtreamParser.kt     # tolerant JSON parsing (ids as strings or numbers, nulls…)
│   ├── ServerUrls.kt       # URL normalisation, M3U link parsing, stream URLs
│   ├── CatalogRepository.kt# in-memory + on-disk channel catalog, search
│   ├── ChannelMatcher.kt   # finds alternate feeds of the same channel
│   ├── LibraryStore.kt     # favorites, lists, recently watched
│   └── SettingsStore.kt    # credentials and playback settings (DataStore)
└── ui/
    ├── login/  home/  lists/  common/
    └── player/
        ├── PlayerController.kt  # ExoPlayer + zapping + failover logic
        └── PlayerScreen.kt      # fullscreen player UI
```

## Notes

- Most Xtream servers use plain HTTP, so cleartext traffic is allowed (`res/xml/network_security_config.xml`).
- Credentials are stored in the app's private DataStore. They are not encrypted.
- If your provider rejects unknown players, change `USER_AGENT` in `PlayerApp.kt`.

## Ideas for later

Movies and series (VOD), a full EPG grid, catch-up, multiple accounts, picture-in-picture, and per-channel manual backup lists.
