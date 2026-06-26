# StalkerTV for iPhone & iPad

A native iOS (SwiftUI) client for **Stalker / Ministra** IPTV portals — the iOS
counterpart to the Android app in the parent repository. Playback uses
**MobileVLCKit** (the iOS build of VLC) so it handles the same arbitrary
codecs and streaming protocols (HLS, MPEG-TS, AC-3, …) the Android app relies on
libVLC for.

> Status: **working foundation.** Provider login, portal handshake, live-channel
> browsing by folder, stream resolution, and full-screen VLC playback are
> implemented. VOD / Series / Catch-up / Favourites are ported in the portal
> client (`StalkerPortal.swift`) and ready to surface in the UI.

## What's here

```
ios/
├── project.yml                     # XcodeGen project spec (generates the .xcodeproj)
├── Podfile                         # CocoaPods alternative for MobileVLCKit
├── StalkerTV/
│   ├── App/StalkerTVApp.swift      # @main entry
│   ├── Models/Models.swift         # Channel / Genre / VodItem / EpgItem …
│   ├── Networking/
│   │   ├── StalkerPortal.swift     # async port of Android Portal.kt (handshake, channels, VOD, create_link, EPG, archive)
│   │   └── JSON.swift              # forgiving JSON accessor (mirrors org.json optString/optInt)
│   ├── Storage/Configs.swift       # provider accounts, favourites, sort mode (UserDefaults)
│   ├── Player/VLCPlayerView.swift  # MobileVLCKit UIViewRepresentable
│   └── Views/                      # SwiftUI: Root, Accounts, LiveChannels, Player
└── README.md
```

The portal logic is a faithful 1:1 port of the Android `Portal.kt` — same MAG250
user-agent spoofing, same handshake candidate URLs, same cookie pinning to one
backend node, same `create_link` retry on `nothing_to_play`.

## Build it (requires a Mac with Xcode)

You cannot build an iOS app on Linux/Windows — Xcode (macOS only) is required.

1. **Install tools**
   ```bash
   brew install xcodegen
   ```
2. **Generate the Xcode project**
   ```bash
   cd ios
   xcodegen generate
   open StalkerTV.xcodeproj
   ```
   This resolves **MobileVLCKit** via Swift Package Manager automatically.
3. **Set your signing team** — select the `StalkerTV` target → *Signing &
   Capabilities* → pick your Apple ID team (free or paid). Xcode will assign a
   bundle id; the default is `com.stalkertv.app`.
4. **Run** on a simulator or a connected device (`⌘R`).

### CocoaPods alternative
If SPM resolution misbehaves, use the included `Podfile` instead:
```bash
cd ios
xcodegen generate
# remove the MobileVLCKit package + dependency lines from project.yml first
pod install
open StalkerTV.xcworkspace
```

## Sideload onto your iPhone / iPad

You don't need the App Store for personal use.

| Method | Cost | Re-sign every | Notes |
|---|---|---|---|
| **Xcode direct install** | Free Apple ID | 7 days | Plug device in, press Run |
| **AltStore / Sideloadly** | Free Apple ID | 7 days (AltStore auto-refreshes over Wi-Fi) | Easiest day-to-day with free account |
| **Paid Apple Developer** | $99/yr | 1 year | Re-sign once a year, then forget it |

Steps with Xcode (simplest):
1. Connect your iPhone/iPad, trust the computer.
2. Select your device as the run destination, press **Run**.
3. On the device: *Settings → General → VPN & Device Management* → trust your
   developer profile.
4. With a **free** Apple ID the app stops launching after 7 days — re-run from
   Xcode (or let AltStore auto-refresh) to renew. A **$99** account lasts a year.

## First run
Add a provider (Portal URL like `http://host:port`, your MAC, optional Serial
Number), then open **Live TV** → pick a folder → tap a channel to play.

## Roadmap (already scaffolded in the portal client)
- VOD movies + Series/seasons/episodes browsing UI
- Catch-up / archive playback (timeshift) UI
- Favourites & parental PIN (logic ported in `Configs.swift`)
- EPG display (`shortEpg` is wired; full per-date grid is a small addition)
- AirPlay (native) in place of the Android Chromecast/DLNA path
