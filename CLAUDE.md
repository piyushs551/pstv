# PSTV — standing rules and device facts

Read this first in every session. Then read PROGRESS.md (what is done / next) and reference/FEATURES.md.

## What this is
Original-brand IPTV player for Android TV / Amazon Fire TV. Plays ONLY the user's own M3U URL or Xtream login. No content, playlists or third-party branding ships in the app. Never use Netflix's or NexTv's name, logo, colours-as-brand or assets; copy structure and flow only.

## Standing rules (from the user)
- Keep token use LOW: short replies, quiet builds (`./gradlew -q ... | grep -E "^e: |FAILED" | cut -c1-200 | head`), never dump logs, low-res previews, read only the lines you need.
- Ask before installs on the user's machine and before preference-dependent choices. Tell them before each install.
- Everything configurable lives in Settings (not hard-coded). New behaviour = new Settings row.
- Commit to git after each stage and update PROGRESS.md in the same commit; push to `main`.
- API keys (TMDB, OpenSubtitles) are baked in at build time from git-ignored `keys.properties` / `PSTV_*` env vars (`scripts\set-keys.ps1`, which also takes the user's playlist: source/m3u_url/x_host/x_user/x_pass); Settings only overrides. Never commit or ask the user to paste keys in chat.
- Do not put the user's email, IP addresses, playlist URLs or keys in the repo, commits or external requests.
- Never claim something works on the Fire TV unless it was run there; say "built / previewed only".
- Screenshots of each screen before calling a stage done (Robolectric previews are acceptable, label them as previews).

## Target device
Fire TV Stick 4K (1st gen), Fire OS 6.7.1.1 = Android 7.1 (API 25), ~1.5 GB RAM, ~2.4 GB free, 32-bit (`armeabi-v7a`; verify with `adb shell getprop ro.product.cpu.abilist`). D-pad remote only. Budgets: app RAM < ~150 MB, hardware decoding, 4K HEVC playable. APK target ~12 MB originally; now ~5 MB without mpv.
User machine: Windows laptop, project in `C:\claude\projects\pstv`. Scripts: `scripts\setup.ps1`, `build.ps1`, `install.ps1`, `scripts\capture-reference.ps1` (untested on Windows).

## Stack
Kotlin 2.2.10 (needed by libmpv), AGP 8.5.2, Gradle 8.9 wrapper, classic Views + RecyclerView (no Compose), Media3 ExoPlayer 1.7.1 (core, hls, ui, okhttp) + NextLib FFmpeg decoders (`io.github.anilbeesetti:nextlib-media3ext`, minSdk 21; plays MP2/DTS audio in software), Coil 2 (`io.coil-kt:coil`), OkHttp, built-in `JsonReader`/`org.json`, SQLite (FTS4). minSdk 25, targetSdk 34, R8 + resource shrink on in debug too.

## Cloud build environment (this is where Claude runs)
- Android SDK at `/opt/android-sdk` (`export ANDROID_HOME=/opt/android-sdk`; `local.properties` is git-ignored). If missing: cmdline-tools from dl.google.com, then `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`.
- Network allowlist (environment `pstv`): dl.google.com, gcp.mirrors.maven.org (Robolectric native jar). Other hosts may need `update_environment`.
- No KVM -> no emulator. UI previews: `./gradlew -q testDebugUnitTest -Dshots.dir=/tmp/shots` (Robolectric native graphics, `ShotsTest.kt`, placeholder titles only).
- `sleep` chains are blocked; use run_in_background/Monitor for waiting.

## Architecture map (app/src/main/java/com/pstv/tv)
App.kt (Application, BaseActivity, Nav) · Prefs.kt (all settings) · Db.kt (SQLite v2: items, favs, recents, livehist, seen, meta, epg) · Repo.kt (playlist refresh) · M3uParser.kt / Xtream.kt · Tmdb.kt (+Enricher) · OpenSubs.kt (+Srt) · Epg.kt (XMLTV stream parser) · Images.kt (Coil, NameTile) · Rows.kt (cards, rows, Anim) · HomeActivity.kt (tabs shell, Hero) · LivePage.kt · EpgPage.kt · SearchActivity / DetailActivity / SettingsActivity · PlayerActivity.kt + Engine.kt (ExoEngine) + SeekController.kt.

## Gotchas learned
- Db schema bump drops and recreates all tables (pre-release).
- MPV was REMOVED: libmpv (minSdk 26) crashed on the Fire OS 6 stick, and it clashed with the FFmpeg extension (both ship libavcodec.so). ExoPlayer + NextLib FFmpeg is the only engine now (Engine.kt keeps the abstraction). `checkAarMetadata` tasks are disabled (media3 1.7 metadata wants a newer compileSdk).
- Focus: rows use deterministic Up/Down handling in `HomeActivity.onCardKey`; remembered position per row (`RowDesc.lastPos`).
- `HomeActivity` hero shows local data instantly; images debounce 150 ms; never block on network.
- Robolectric previews cannot show focus; call the card's `onFocusChangeListener` manually.

## Design language (user spec, supersedes earlier card decisions)
Netflix structure (hero + horizontal rows) with Apple tvOS polish. Rounded corners (12–16 dp cards, pill buttons/tabs), Material Symbols Rounded icons, bundled rounded sans (Outfit), near-black background, ONE accent colour used only for focus and primary actions. Focus: ~8–10% scale, soft bright outline + shadow, dimmed neighbours, ~150 ms. 16:9 cards for live/episodes, 2:3 posters for movies/series, title shown under the focused card only. TV-safe margins. No blur/parallax; fake frosted panels with translucent dark fills. Design is IMPLEMENTED (stages 1-4 done). All look values live in the design-tokens file (res/values/tokens.xml + Tokens.kt) so the look changes in one place.
