# PROGRESS

## Done
- Core app: M3U + Xtream, SQLite+FTS search, TMDB (trending/art/plots/rating/genres), OpenSubtitles, favorites/recents, background refresh.
- NexTv-inspired structure: tabs (Search/Home/Live/EPG/Movies/TV shows/Settings), hero with chips, rows, Live sidebar+tiles, EPG grid (XMLTV), side-panel player overlay.
- Player: ExoPlayer + MPV engines, auto fallback (error or 15 s stall), Live/VOD settings, FF/RW steps, low latency, match framerate, track lists incl. unsupported (greyed).
- Windows scripts + README. Robolectric layout previews.

## Next (design-language pass, in stages; commit after each)
1. DONE — Tokens + typography + icons (res/values/tokens.xml, Tokens.kt/Fonts.kt, Outfit in assets/fonts, 16 Material Symbols Rounded vectors ic_*.xml).
2. DONE — Cards: 2:3 posters vs 16:9, rounded, focus ring + shadow, dimmed neighbours, title on focused card only.
3. DONE — Pills, icon tabs, accent only on Play/primary + focus, TV-safe margins, type scale via tokens.
4. DONE — Frosted translucent panels (player overlay/side panel, dialogs), Live/EPG/Settings/Detail restyle, previews.

## Status after design pass
- All four design stages committed. Previews (Robolectric) for Home, Live, EPG, Movies, Search, Settings, Detail, Player overlay. APK ~16.3 MB (mpv + fonts + icons).
- Preview caveats: Robolectric does not apply outline clipping (cards look square) or focus; both work on device by design but are UNVERIFIED on the Fire TV.
- Fonts: Outfit installed by re-pointing the Android 7 system font map via reflection (Fonts.kt); falls back to system fonts if blocked.

## Decisions
- API keys (TMDB, OpenSubtitles key+login) are baked in at build time from git-ignored keys.properties via scripts\set-keys.ps1; Settings shows "Built in" and can override. Replaces the earlier "user types keys in Settings".
- Font: Outfit (rounded sans). Icons: Material Symbols Rounded from npm `@material-symbols/svg-400`, converted to vector drawables.
- MPV kept despite APK 15 MB; verify on device.
- Home rows: curated (recent, trending, favorites, recent live, recently added); group rows live under Movies / TV shows / Live tab.

## Open risks / unverified on device
- Everything: nothing has run on a Fire TV yet. MPV on API 25. Match framerate on Fire OS. EPG matching depends on provider tvg-id/epg_channel_id. RAM <150 MB unmeasured.
