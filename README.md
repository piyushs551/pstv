# PSTV: a Netflix-style IPTV player for Fire TV

An original-brand, D-pad-first player for **your own** M3U URL or Xtream login. The app ships with no channels, playlists or content.

Target: Fire TV Stick 4K (1st gen), Fire OS 6.7.1.1 (Android 7.1 / API 25). minSdk 25, targetSdk 34, Kotlin, classic Views + RecyclerView, Media3 ExoPlayer (core + HLS + OkHttp data source), Coil, OkHttp. JSON uses the built-in `android.util.JsonReader` (streaming, for huge lists) and `org.json`, so no extra library is shipped. Debug APK is about 2 MB.

## 1. Set up your Windows machine

Project folder: `C:\claude\projects\pstv` (no spaces, not in OneDrive).

```powershell
git clone https://github.com/piyushs551/pstv C:\claude\projects\pstv
cd C:\claude\projects\pstv
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
```

`setup.ps1` checks for JDK 17, the Android command-line tools and SDK packages, and **asks before each install** (JDK via `winget`; SDK from dl.google.com into `C:\Android\sdk`; it also sets `JAVA_HOME`, `ANDROID_HOME` and PATH). Open a new PowerShell window afterwards.

## 2. Build and install

If PowerShell says "running scripts is disabled", allow scripts for this window only (nothing permanent changes):
```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

```powershell
.\build.ps1                      # debug APK -> app\build\outputs\apk\debug\app-debug.apk
.\install.ps1 -Ip 192.168.1.50   # your Fire TV's IP; add -Build to rebuild first
```

On the Fire TV: Settings > My Fire TV > Developer Options > **ADB debugging ON** (and "Apps from Unknown Sources" if offered). Accept the "Allow USB debugging?" prompt on the TV when asked.

`install.ps1` prints the device ABI list. The APK is filtered to `pstv.abi` in `gradle.properties` (default `armeabi-v7a`, which is what a Stick 4K gen 1 normally reports). If `adb shell getprop ro.product.cpu.abilist` shows something else, change that value and rebuild.

### Update later
`git pull`, then `.\install.ps1 -Ip <ip> -Build`. `adb install -r` keeps your settings, favorites and cache.

## 3. First run

Settings (top bar) > pick **Source type**, enter the **M3U URL** or **Xtream server / username / password** > **Refresh playlist now**. After the first download it refreshes in the background every 6 hours and shows its status in the top bar.

### API keys (both are yours, entered in Settings; nothing is baked in)
- **TMDB**: free key at themoviedb.org (Settings > API, use the v3 "API Key"). Gives trending, posters, backdrops and plots. Only titles that exist in your playlist are shown.
- **OpenSubtitles**: API key from opensubtitles.com (Consumers), plus your account username/password (the API requires a login to download).

## 4. Controls

| Where | Key | Action |
|---|---|---|
| Home | Up/Down | One row up/down (the row remembers its last card) |
| Home | OK / long-press OK | Play or open / add or remove Favorite |
| Player (VOD) | Left/Right | Seek one fixed step; hold to speed up (capped) |
| Player (VOD) | OK, Up, Down | Pause and overlay, options, overlay |
| Player (live) | Up/Down | Previous/next channel (switches 350 ms after the last press) |
| Player (live) | OK | Mini channel list for the current group |
| Player | Menu | Options: subtitles, online subtitles, audio, aspect ratio, subtitle size/colour/delay |

Seek step (5/10/30/60 s), subtitle size/colour/delay and aspect ratio (Fit, Fill, Zoom, 16:9, 4:3) are remembered.

## 5. Image cache
Settings > **Image cache size** (50/100/200/500 MB, default 100) and **Clear image cache**. Images load lazily per visible row, sized to the card; cards without art get a generated name tile.

## 6. Capture your current app as a layout reference
```powershell
.\scripts\capture-reference.ps1 -Ip 192.168.1.50            # D-pad only, safe
.\scripts\capture-reference.ps1 -Ip 192.168.1.50 -Interactive # then capture screens you open yourself
```

## Player engines
Settings > PLAYER has separate **Live** and **VOD** sections: pick **ExoPlayer** (lightweight, default) or **MPV** (plays more formats and audio tracks), set fast-forward/rewind steps, low latency (live) and match framerate. **Auto fallback** retries a stream with the other engine if it errors or shows nothing for 15 s. In the player, the Audio and Subtitles panels have "Try MPV/ExoPlayer engine for this stream". Tracks the current engine cannot decode are listed greyed out.

MPV is `libmpv` (declares minSdk 26) running on Fire OS 6 (API 25), which has **not been verified on a real stick**: if it cannot load, the app says so and keeps using ExoPlayer. The APK is about 15 MB because it bundles mpv and FFmpeg for armeabi-v7a.

## Known limits
- Subtitle **delay** applies to subtitles downloaded from OpenSubtitles (they are re-timed). Embedded tracks use the stream's own timing.
- Xtream episodes you open from the series page resume correctly, but do not appear under Continue Watching (only whole movies/channels do).
- XMLTV guide is not implemented yet.
- Not yet tested on real hardware; see the project status notes when you first run it.
