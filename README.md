# Bunny Player

An Android IPTV player for **Xtream Codes** providers such as [mybunny.tv](https://mybunny.tv).
It works on phones (portrait and landscape), tablets and Android TV (remote / D-pad friendly).

## Install

Download the latest APK on the device and open it:

**https://github.com/mwalczak/player/releases/latest/download/bunny-player.apk**

After that the app **updates itself**. Every push to `main` is built by GitHub Actions and published as a release `build-N`. The app checks for a newer build on start (and when you come back to it), shows an *Update* banner, and installs it with one tap. The first time, Android asks you to allow installs from Bunny Player.

## Features

- **Sign in once** with server URL, username and password, or paste an M3U link (`…/get.php?username=…&password=…`) and the fields fill in for you. The login is remembered, so the app opens straight into your channels.
- **Adaptive layout**:
  - Portrait phone: bottom navigation and one column, so typing a search leaves room for results.
  - Landscape, tablet and TV: navigation rail on the left and channels in as many columns as fit.
  - The player always goes landscape. On a TV remote, the focused item is clearly highlighted.
- **Favorites first**: the app opens on your favorites when you have some. To add a channel, **long-press it** anywhere (or tap ☆). While watching, use the ☆ in the overlay or the red/bookmark button on a TV remote.
- **Live TV** shows the provider's own categories (countries, sports…) in the server's order, plus "All channels" and "Recently watched". **Pin** 📌 categories (e.g. Poland) to keep them at the top.
- **TV guide (EPG)**:
  - Every channel row shows what's on now, with a progress bar.
  - The player shows Now/Next, and 🕒 (or the remote's Guide button) lists the rest of today.
  - The **TV guide** tab is a grid with channels down the side, time across the top and a line at "now". Pick Favorites or a category, and tap a programme to watch.
  - 🔍 in the guide searches show titles across all channels for the next ~24 h (any word order, accents ignored). Results show when and where each show is on; what's on now comes first. Tap one to watch that channel.
  - Guide data comes from the daily [epgshare01](https://epgshare01.online) XMLTV guides. The app picks the countries found in your channel list automatically (e.g. Poland, US, Canada, UK, Portugal, Spain, Plex…). On a 46k-channel list that covers ~8.4k channels; PPV, event and 24/7 streams have no guide anywhere. US local stations are left out on purpose (57 MB for very few matches).
  - Channels are matched by the provider's own EPG id first, then by name. mybunny's own guide fills gaps, favorites first.
  - The guide is stored in an on-device database, so even very large channel lists stay fast. For now it downloads **only when you tap Settings → TV guide → Download guide now** (Wi-Fi only by default).
  - Settings → TV guide lets you pick guides by hand, allow mobile data, or add an extra XMLTV URL.
- **Home** is the first page: tiles for Favorites, each of your lists and your pinned categories. Tap a tile to start watching it from its first channel, or ≡ to open it. Back from any other tab, list or category returns to Home (Back from the player still returns to the list you were watching); Back on Home asks before leaving the app.
- **Autoplay**: Favorites, every list and every category have an **Autoplay** button. It starts from the channel being watched (on the TV when casting, otherwise the last one watched in that list) and plays the channels one after another, changing every minute (Settings → Autoplay: 30 s – 10 min) and starting over at the end. In the player the top controls have ⏸/▶ (on a TV remote, ▶ starts or resumes it and ⏸ pauses it, wherever the remote is in the player) and ✕ to turn it off; that same button starts autoplay in any list. A badge shows "Next in 0:42". Changing channel by hand restarts the countdown, a channel that fails is left after 3 s, and autoplay is passed on when casting to the TV.
- **Reminders**: long-press (long OK on a remote) a show that hasn't started, in the TV guide, its search results or the player's schedule (🕒), and pick *Remind me 2 min before* or *Switch to it when it starts*. 🔔 marks them. With the app open you get a prompt with Watch (or it switches by itself); otherwise a notification whose Watch opens the app on that channel. Alarms survive reboots. Settings → Reminders lists them.
- **TV home screen**: on Android TV the favorites appear as a "Bunny Player – Favorites" row, and the channel watched last in "Continue watching"; each opens the app on that channel (`bunnyplayer://play/<stream id>`). Google TV home screens may only show Continue watching.
- **Hidden categories**: long-press a category on Live TV → *Hide*, or Settings → Hidden categories → *Choose categories to hide…* (a searchable checklist). Hidden ones (and channels only in them) are left out of Live TV, All channels, Search, the TV guide and Home's pinned tiles; favorites and lists keep their channels. Settings lists them with *Unhide*. Stored by name and synced.
- **What's new**: after an update a pop-up lists the changes since the version you had (not on a fresh install); Settings → App → *What's new* shows them all. The entries live in `app/src/main/assets/changelog.json`: add one (next `id`) with each user-facing release.
- **Live TV** lists all categories, pinned ones on top. The field at the top filters them (any word order, accents ignored, e.g. "pol sport").
- **Search** across the whole catalog. Matching ignores case and accents and accepts words in any order ("one bbc" finds "UK: BBC One HD").
- **Favorites** can be reordered: drag a channel by its ≡ handle (on a TV remote: OK on ≡, then ▲/▼, then OK), or tap **A–Z** (with Undo). The order is the zapping and failover order.
- **My lists**: create, rename, delete and reorder (by hand or A–Z) your own lists ("Sports", "News"…) and add channels from search.
- **Player** (ExoPlayer / Media3):
  - Switch channels with swipe up/down, the ⏮ ⏭ buttons, or the remote (◀ ▶ or CH+/CH−). Zapping with swipes or the remote doesn't bring up the controls; a small label shows the new channel's number and name for 3 s.
  - ⟳ next to ⏮ ⏭ reloads the channel when it froze. ⇄-shaped *Other feeds* in the top controls lists other streams of the same channel from the whole catalog (then similar names); pick one to switch to it.
  - On a TV remote, **▲** jumps to the controls at the top (☆ favorite, add to list, aspect, cast, schedule, channel list) and **▼** to the ⏮ ⏭ ⇄ buttons. Move with ◀ ▶, press OK, and Back returns to the picture. A white ring shows the selected button.
  - ⇄ **Last channel** flips between the current channel and the one you watched before. The button shows its name, and the remote's Last key works too.
  - Open the side channel list with the list button or OK on a remote.
  - Shows the current programme ("Now") and the next one from the provider's EPG.
  - Aspect modes: Fit, Zoom, Stretch. You can also favorite the channel you're watching.
- **Cast to TV over Wi-Fi** (phone as remote): open Bunny Player on the TV, then on the phone tap 📺 (top of the side rail, or in the player) and pick the TV.
  - Any channel you tap on the phone now plays on the TV, and a bar shows what's on with ⏮ ⏭ ⟳ (reload) ⇄ (last), *Other feeds* (pick another stream of the TV's channel) and ■.
  - The channel playing on the TV is underlined in orange in channel lists and in the guide.
  - Casting from the player hands over the channel you're watching.
  - If the TV app isn't signed in, the phone signs it in with the same login, and the TV gets favorites, lists and settings through sync.
  - Devices find each other with mDNS (`_bunnyplayer._tcp`) and talk over plain HTTP on the local network. The TV uses its own player, so `.ts` streams and failover work exactly as they do on the TV. This avoids Chromecast, whose receiver can't play raw `.ts` and needs CORS headers that IPTV servers don't send.
- **Sync & sharing** (Settings → *Sync & sharing*): favorites, lists, pinned categories and playback/guide settings are saved online on the sync server ([server/](server/), deployed to `bunny.walczaki.com`).
  - Every device signed in with the same mybunny login shares one setup, both ways. After a reinstall, signing in brings everything back. The server never sees the login: the app derives a key from it (PBKDF2) and the server stores only a hash of that key.
  - Each setup has a share code (e.g. `ABCD-EFGH`). A friend enters it under *Use someone's setup* and either **follows** it (their device shows your setup and gets your changes) or **copies it once** and makes it their own. Logins are never shared this way.
  - The app syncs when it opens, every 5 minutes while open, and a moment after each change. A fresh install never uploads its empty setup over the saved one.
  - **History**: the server keeps the last 100 versions, each with the time, the device name and the IP address it was saved from. Saves from the same device and address within 2 minutes count as one version. Settings → Sync & sharing → *History* lists them; picking one restores it on every device signed in with that login. The version it replaces stays in the history.
- **Shared accounts**:
  - Before playback starts, the app checks how many streams the account is using. If all are taken (e.g. 1 of 1), it warns that starting will probably cut off whoever is watching, with **Watch anyway** / **Cancel**.
  - If your stream is refused while all connections are in use, the player says someone else started watching instead of hopping through channels. **Retry** takes the stream back.
- **Automatic failover when a channel is off**:
  1. The same stream is retried once.
  2. The app tries other feeds of the same channel from the catalog. For example, if `US: ESPN HD` is down it plays `ESPN FHD` or `ESPN ᴴᴰ (Backup)`.
  3. If none of those work, it moves to the next channel in the list you're watching. This is why the order of your favorites and lists matters.

  A channel also counts as off if it keeps buffering (8 / 15 / 30 s, set in Settings), or if it plays but has no sound for 5 s: providers' "temporarily unavailable" screens are silent. The sound level is measured on the decoded audio: stereo AAC/MP2 channels (most IPTV) are measured even with a soundbar; Dolby/DTS passed straight to a soundbar can't be, so those are never skipped for silence. Settings → When a channel is off → *Skip channels without sound* turns it off. Each step can be turned off in **Settings → When a channel is off**.
- **Live preview while browsing** (TV, tablet, landscape phone): a preview pane next to channel lists and the TV guide plays the selected channel. On a remote, moving through the list previews the focused channel after a short pause and **OK** opens full screen. On touch, the first tap previews and a second tap (or a tap on the preview) opens full screen. The preview hands its stream over before full screen starts, so it works with a 1-connection account. It can be turned off in Settings → Display.
- **Display**: Settings → *Columns in channel lists* (automatic or 1–4) per device; channel names wrap to two lines.
- **Crash reports**: if the app crashes (or a background job fails), the next start shows a banner with **Share report**, so the error can be sent and fixed.
- Settings also cover the stream format (MPEG-TS or HLS) and account info (status, expiry date, connections).

The channel list is cached on the device and refreshes every 12 hours, or when you pull to refresh. Favorites and lists are stored locally in `library.json`.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or open the project in Android Studio
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified, signed with app/debug.keystore
```

Install on a device or Fire TV with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## CI

`.github/workflows/build.yml` runs the unit tests and builds the APK on every push. On `main` it publishes a GitHub Release `build-<run number>` with `bunny-player.apk`. The APK's `versionCode` is the run number, which is how the in-app updater spots newer builds. Local builds are version 1 and never auto-update. All builds are signed with the committed `app/debug.keystore`, so any build installs over any other.

`.github/workflows/sync-server.yml` runs whenever `server/` changes on `main`. Like ip.walczaki.com, it tests the sync server, pushes an image built from `server/Dockerfile` to GHCR (`ghcr.io/mwalczak/player-sync`), and applies `server/k8s.yaml` on the VPS's k3s over ssh (secrets `VPS_HOST`, `VPS_SSH_KEY`). The data lives in the `sync-data` volume in namespace `bunny`.

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
│   ├── epg/                # guide sources, XMLTV streaming parse, matching, SQLite storage
│   ├── LibraryStore.kt     # favorites, lists, recently watched
│   └── SettingsStore.kt    # credentials and playback settings (DataStore)
├── cast/                   # phone -> TV remote (mDNS discovery + tiny HTTP receiver)
├── update/                 # self-update from GitHub Releases
└── ui/
    ├── login/  home/  lists/  common/  guide/
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
