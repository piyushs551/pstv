# PROGRESS

## Done
- Core app: M3U + Xtream, SQLite+FTS search, TMDB (trending/art/plots/rating/genres), OpenSubtitles, favorites/recents, background refresh.
- NexTv-inspired structure: tabs (Search/Home/Live/EPG/Movies/TV shows/Settings), hero with chips, rows, Live sidebar+tiles, EPG grid (XMLTV), side-panel player overlay.
- Player: ExoPlayer + MPV engines, auto fallback (error or 15 s stall), Live/VOD settings, FF/RW steps, low latency, match framerate, track lists incl. unsupported (greyed).
- Windows scripts + README. Robolectric layout previews.

## Next (design-language pass, in stages; commit after each)
1. DONE — Tokens + typography + icons (res/values/tokens.xml, Tokens.kt/Fonts.kt, Outfit in assets/fonts, 16 Material Symbols Rounded vectors ic_*.xml).
2. Cards: 2:3 posters (movies/series) vs 16:9 (live/episodes), radius, focus ring+shadow, dim neighbours, title on focused card only.
3. Hero / tabs / buttons: pills, TV-safe margins, accent only for focus + primary actions.
4. Frosted translucent panels (player overlay/side panel, dialogs), Live/EPG/Settings/Detail restyle, previews.

## Decisions
- Font: Outfit (rounded sans). Icons: Material Symbols Rounded from npm `@material-symbols/svg-400`, converted to vector drawables.
- MPV kept despite APK 15 MB; verify on device.
- Home rows: curated (recent, trending, favorites, recent live, recently added); group rows live under Movies / TV shows / Live tab.

## Open risks / unverified on device
- Everything: nothing has run on a Fire TV yet. MPV on API 25. Match framerate on Fire OS. EPG matching depends on provider tvg-id/epg_channel_id. RAM <150 MB unmeasured.
