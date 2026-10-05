# MediaUrl

Lightweight Android browser app (~1-2MB) for extracting M3U8, MPD, MP4, and other media URLs from any website.

## Features

- 🌐 **WebView Browser** — Browse any website within the app
- 🔗 **Stream Detection** — Automatically detects and logs media URLs (M3U8, MPD, MP4, TS)
- 📋 **Extract & Copy** — One-click extraction and clipboard copy
- 🔧 **Custom Scripts** — Inject custom JavaScript for automation (querySelector, clicks, etc.)
- 🎬 **Background Service** — Runs in background via Foreground Service
- 📦 **Minimal Size** — ~1-2MB APK (proguard + shrinkResources enabled)
- 🚀 **GitHub Actions CI** — Auto-builds APK on push

## Architecture

```
MainActivity (WebView + UI)
    ↓
StreamExtractor (Network Interceptor)
    ↓
OkHttp (Monitors all requests)
    ↓
DetectedStream (m3u8, mpd, mp4, ts)
```

## How to Use

1. **Browse** — Use the in-app browser to navigate to any website
2. **Extract** — Tap "Extract Streams" button to collect detected URLs
3. **Copy** — Tap any URL to copy to clipboard
4. **Inject Scripts** — Add custom JavaScript for automation (coming soon)

## Build & Deploy

### Local Build
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions
```bash
git push origin main
# Auto-builds on push, artifacts available in Actions tab
```

## Permissions

- `INTERNET` — Browse websites and detect streams
- `FOREGROUND_SERVICE` — Run in background
- `POST_NOTIFICATIONS` — Show foreground service notification

## Min SDK: 24 | Target SDK: 34

## License

MIT

---

**v1.0.0** — Initial release with M3U8/MPD/MP4 detection
