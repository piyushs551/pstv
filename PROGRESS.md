# PROGRESS

## Done
- Core app: M3U + Xtream, SQLite+FTS search, TMDB (trending/art/plots/rating/genres), OpenSubtitles, favorites/recents, background refresh.
- NexTv-inspired structure: tabs (Search/Home/Live/EPG/Movies/TV shows/Settings), hero with chips, rows, Live sidebar+tiles, EPG grid (XMLTV), side-panel player overlay.
- Player: ExoPlayer + MPV engines, auto fallback (error or 15 s stall), Live/VOD settings, FF/RW steps, low latency, match framerate, track lists incl. unsupported (greyed).
- Windows scripts + README. Robolectric layout previews.

## USER FEEDBACK BATCH (from on-device use + Prime Video reference shots) — work in this order
Stage A (bugs) — DONE (built, not yet verified on TV): 
 A1 Back button: deep row -> scroll to top, show tab bar, focus current tab; Back again (non-Home tab) -> Home tab; Back on Home -> exit. 
 A2 Down arrow "skipped a row": outer rows RecyclerView must clip (previous row peeked over hero); one row per press.
 A3 Tab bar fades away + hero collapses when focus moves below row 0 (more room); returns on row 0 / Back.
 A4 Live tab category list scroll broken (notifyDataSetChanged on focus): update only old/new selected.
 A5 Live player: Left opens channel list.
 A6 Tiles without art show full name + kind icon, not initials.
Stage B (density + Prime layout) — DONE (built + previewed; Settings > UI size 85% default, landscape cards for all, slim text tabs, left rail Search/Favorites/Settings, Favorites screen = SearchActivity extra "fav"): UI looks too zoomed (~800x600): add Settings > UI size (Compact default via density scale); landscape 16:9 cards for ALL kinds (TMDB backdrops; supersedes 2:3 posters) so ~2 rows show; slimmer text top menu; left icon rail (Search, Favorites); less chunky.
Stage C (content) — PARTLY DONE: category drawer (Left on first card in Movies/TV shows) done; still TODO: more TMDB rows (top rated/popular), Settings screen to reorder/hide categories per tab: Left key in Movies/TV tabs opens a category list (recently added, top, groups) to jump; more TMDB rows (top rated/popular/top-10 style); Settings: reorder/hide categories per tab (e.g. Indian first).
Stage D — DONE (built, untested on TV; needs Xtream series): Series detail like Prime: info on top, season selector (dropdown/pills), episodes as landscape cards with episode info.
Prime shots 6-8 (series detail), 9-12 (Live) described in notes; images pending from user.

## Earlier plan (design-language pass, in stages; commit after each)
1. DONE — Tokens + typography + icons (res/values/tokens.xml, Tokens.kt/Fonts.kt, Outfit in assets/fonts, 16 Material Symbols Rounded vectors ic_*.xml).
2. DONE — Cards: 2:3 posters vs 16:9, rounded, focus ring + shadow, dimmed neighbours, title on focused card only.
3. DONE — Pills, icon tabs, accent only on Play/primary + focus, TV-safe margins, type scale via tokens.
4. DONE — Frosted translucent panels (player overlay/side panel, dialogs), Live/EPG/Settings/Detail restyle, previews.

## Status after design pass
- All four design stages committed. Previews (Robolectric) for Home, Live, EPG, Movies, Search, Settings, Detail, Player overlay. APK ~16.3 MB (mpv + fonts + icons).
- Preview caveats: Robolectric does not apply outline clipping (cards look square) or focus; both work on device by design but are UNVERIFIED on the Fire TV.
- Fonts: Outfit installed by re-pointing the Android 7 system font map via reflection (Fonts.kt); falls back to system fonts if blocked.

## Fixes after on-device feedback (round 2)
- TMDB rows: trending + popular + top rated (movies/series), matched to playlist (also original title, quality tags stripped from titles; playlist re-imports once, Repo.NORM_V). Home rebuilds when the TMDB key changes. Settings > "Test TMDB key".
- Continue Watching: rows empty at load are added later (refreshPersonalRows); Movies/TV tabs get their own Continue watching row. NOTE watch history saved before the Db v3 update has no series link and cannot show.
- Series page: Season is a dropdown popup under the button; episode stills/titles/plots from TMDB (Tmdb.seasonEpisodes).
- Blank logo cards: light backing is chosen from the logo's luminance (Images.isDark).
- DONE: Settings > Categories (Categories.kt, CategoriesActivity): reorder/hide per tab; applied in Home groupRows and Live sidebar.

## Round 3 features (built + previewed, not yet on TV)
- Held D-pad keys no longer lose focus (explicit row moves + repeat throttle + focus safety net). Banner scrim lightened; Settings > banner mode for poster-only items.
- Settings > Categories: reorder/hide per tab. Search page redesigned (chips, recent searches, grouped results); Favorites is its own screen.
- Long-press OK on a movie/show = InfoDialog (banner, plot, seasons/episodes, TMDB "More like this" from the playlist, other versions of the same title). Live channels: long-press toggles favorite.

- Launcher icon: vector drawable replaced by PNG mipmaps (Fire TV launcher showed a broken icon); banner regenerated at 320x180 (drawable-xhdpi/banner.png).

- Round 4: banner stays fixed on every row (only the tab bar fades); remote Options/Menu key focuses the side tray Search; Settings is a short top menu with sub-screens (Playlist, Guide, Appearance, Categories, Artwork & TMDB, Player, Subtitles, About).

- Round 5: UI size default 75%; Settings > Appearance > Banner height (S/M/L, % of screen); category reorder = pick up with OK, Up/Down, drop (hold OK = options); smart default category order (priority words hindi, india, bollywood, other Indian languages first; groups with a year newest first; editable in Settings > Categories; CategoriesTest); items inside Movies/TV categories sorted newest-added first (seen.ts then playlist reverse), Settings sort choice + "Sort" entry in the Movies/TV left drawer.

- Clock top right on Home/Live/EPG/Movies/TV shows (Settings > Appearance: Eastern EST/EDT default, device zone, off; 12/24 h).

- Round 6: zoom no longer cuts text/image (caption not clipped, more headroom around rows and in the info dialog); "Other versions" cards (and search results) carry a quality/category tag (qualityTag); launcher banner moved to drawable-nodpi with explicit activity icon/banner/logo. ICON STILL BROKEN ON DEVICE per user: needs diagnosis (uninstall/reinstall test, dumpsys package).

- Round 7: series autoplay next episode (Settings > Player, default on; Player fetches Xtream episode list); finishing an episode queues "Next: S1 E2" in Continue Watching (recents.label marker); Home has separate "Continue watching movies" / "Continue watching series" rows (legacy "recent" key migrates). Tests: HomeRowsTest, CategoriesTest.
- DONE (round 8): smart search = Smart.kt (local query parser: genre/language/region/year/keywords) + Tmdb.discoverTitles/keywordId/matchTitles (live, up to 8 pages per kind, 650 ms typing pause, 429 handled, session cache) + category-name matches; Settings > Artwork & TMDB > Smart search. SmartTest. UNVERIFIED against real TMDB. Earlier idea (user): smart search ("bollywood comedy", "christmas movies"): TMDB discover/keywords + group names + local title match; optional background TMDB index of the playlist.

- Round 8b: TMDB-to-playlist matching is now fuzzy (Db.matchTitle -> fuzzyMatch via FTS; Model.fuzzyTokens/fuzzyAccept: ignores years, language/quality tags, leading "the", spacing/hyphens; sequels and different years rejected). FuzzyTest. Used by suggestion rows, themed search, recommendations.

- Round 9: Search > recent searches are now reachable with the remote (filter chips Down -> recent chips, OK runs the search, "Clear" chip).

- Round 10 (player): Previous/Next episode buttons (series; also media keys); arrow keys move explicitly between Previous/Pause/Next/Audio/Subtitles/Aspect and the progress line; audio language/track remembered per movie/series (Db v4 audiopref, pruned when the title leaves Continue Watching); controls auto-hide (Settings > Player: 3/5/8 s/never); progress is one thin line (SeekLine) that thickens + dot when focused; hold-to-seek speeds up (Settings: off/normal/fast; SeekController.stepFor, SeekStepTest); default seek step 5 s for new installs.

- Round 11: Movies/TV category list sits between the side tray and the rows: row Left (first card) -> list; list Left -> side tray (list stays open); tray Right -> list at the current category; list Right -> back to the row.

- Round 12: category order bug fixed: manual moves saved the whole list so priority words never applied again. Now editing Priority words / Smart order, or "Apply priority order now", clears manual orders (hidden kept); "Reset" shows the default (priority) order; Sort entry in the Movies/TV category list updates in place and keeps the list open.

- Round 13: new app icon = "PS" monogram + play triangle on the purple tile (mipmaps + 320x180 TV banner regenerated; generator kept out of the repo).

- Round 13b: Fire TV home banner redone full-bleed 16:9 (640x360, drawable-nodpi): PS + play triangle centred on the purple gradient, tagline beneath. Square mipmap icon unchanged.

## Engine decision (round 3)
- User reported MPV crashes on the stick and live MP2 audio is silent on ExoPlayer. MPV removed; Media3 upgraded 1.4.1 -> 1.7.1 and NextLib FFmpeg decoders added (software fallback for unsupported audio). UNVERIFIED on device. If a stream is still silent, report the track label from Audio panel.

## Decisions
- API keys (TMDB, OpenSubtitles key+login) are baked in at build time from git-ignored keys.properties via scripts\set-keys.ps1; Settings shows "Built in" and can override. Replaces the earlier "user types keys in Settings".
- Font: Outfit (rounded sans). Icons: Material Symbols Rounded from npm `@material-symbols/svg-400`, converted to vector drawables.
- MPV kept despite APK 15 MB; verify on device.
- Home rows: curated (recent, trending, favorites, recent live, recently added); group rows live under Movies / TV shows / Live tab.

## Open risks / unverified on device
- MPV: user reported manual switch to MPV on a live channel stayed loading then the app dropped to Home (native crash suspected on Fire OS 6 / API 25). Crash guard added (Prefs.mpvPending/mpvBroken). Need `adb logcat -b crash -d` to find the cause. MP2 live audio is silent on ExoPlayer; alternative fix = FFmpeg audio decoder extension for Media3 (NextLib) instead of MPV.
- Everything: nothing has run on a Fire TV yet. MPV on API 25. Match framerate on Fire OS. EPG matching depends on provider tvg-id/epg_channel_id. RAM <150 MB unmeasured.

## Playlist via set-keys.ps1
- `scripts\set-keys.ps1` now also saves the playlist (source, m3u_url / x_host, x_user, x_pass) into git-ignored keys.properties; baked into BuildConfig.
- Prefs: Settings value overrides the built-in one; Settings rows show "Built in". Repo reloads the playlist when the source signature changes (first launch after a rebuild). Built only, not run on the Fire TV.
- Fire OS home row kept showing a stale tile after reinstall: banner renamed to `tv_banner`, versionCode 2 so the launcher treats it as a new tile (no launcher data cleared). Built only; not verified on the Fire TV.
- Live player: Left on the channel panel opens a category panel (cursor on current category); Right/Back close both; picking a channel keeps the panel open; picking a category loads its channels. Guide tab: Left at the start of a row opens a category panel (All channels + categories) that filters the guide; Back/Right close it; Rewind/FF keys shift the time window. Built only, not run on the Fire TV.
- Search now also looks at guide program titles (row "Airing on the guide": channels showing a match now or within 24 h, with "Now/8:00 PM · Program" under the card). Settings > Smart/“Search the guide” toggles it. Built only.
- Home tile broke again after installing versionCode 2 over a device that had 3 (a downgrade). versionCode must only go up: now 4. APK checked: banner + all icon PNGs valid and present. Cause not proven on the Fire TV.
