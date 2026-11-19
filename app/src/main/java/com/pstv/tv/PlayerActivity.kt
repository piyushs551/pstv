package com.pstv.tv

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class PlayerActivity : BaseActivity() {
    private var engine: Engine? = null
    private lateinit var root: FrameLayout
    private lateinit var pv: PlayerView
    private lateinit var spin: View
    private lateinit var overlay: View
    private lateinit var chips: LinearLayout
    private lateinit var pSub: TextView
    private lateinit var pTitle: TextView
    private lateinit var barWrap: View
    private lateinit var pBar: SeekLine
    private lateinit var pPrev: TextView
    private lateinit var pNext: TextView
    private lateinit var pTime: TextView
    private lateinit var pPlay: TextView
    private lateinit var banner: TextView
    private lateinit var chanList: RecyclerView
    private lateinit var side: View
    private lateinit var sideTitle: TextView
    private lateinit var sideList: RecyclerView

    private lateinit var key: String
    private lateinit var title: String
    private lateinit var currentUrl: String
    private var baseName = ""
    private var year: Int? = null
    private var kind = Kind.MOVIE
    private var group = ""
    private var season = 0
    private var episode = 0
    private var resumeMs = 0L
    private var retries = 0
    private var audioApplied = false
    private var ready = false
    private var matched = false
    private var hasExtSub = false
    private var parentUrl: String? = null
    private var episodeList: List<Xtream.Episode> = emptyList()
    private var epLabel: String? = null

    private var channels: List<Item> = emptyList()
    private var chIndex = -1

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var seeker: SeekController
    private val isLive get() = kind == Kind.LIVE

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.root); pv = findViewById(R.id.player); spin = findViewById(R.id.spin)
        overlay = findViewById(R.id.overlay); chips = findViewById(R.id.p_chips); pSub = findViewById(R.id.p_sub); pTitle = findViewById(R.id.p_title)
        barWrap = findViewById(R.id.p_bar_wrap); pBar = barWrap as SeekLine; pTime = findViewById(R.id.p_time); pPlay = findViewById(R.id.p_play); pPrev = findViewById(R.id.p_prev); pNext = findViewById(R.id.p_next)
        banner = findViewById(R.id.p_banner); chanList = findViewById(R.id.chan_list)
        side = findViewById(R.id.side); sideTitle = findViewById(R.id.side_title); sideList = findViewById(R.id.side_list)
        sideList.layoutManager = LinearLayoutManager(this)

        val i = intent
        currentUrl = i.getStringExtra("url") ?: return finish()
        title = i.getStringExtra("title") ?: ""
        key = i.getStringExtra("key") ?: currentUrl
        kind = Kind.values()[i.getIntExtra("kind", 1)]
        group = i.getStringExtra("group") ?: ""
        season = i.getIntExtra("season", 0); episode = i.getIntExtra("episode", 0)
        baseName = i.getStringExtra("baseName") ?: title
        year = i.getIntExtra("year", -1).takeIf { it > 0 }
        parentUrl = i.getStringExtra("parent")?.ifEmpty { null }; epLabel = i.getStringExtra("label")?.ifEmpty { null }
        setTitles()
        // Series: fetch the episode list so we know what comes next
        parentUrl?.let { p -> if (kind == Kind.SERIES) scope.launch { episodeList = withContext(Dispatchers.IO) { runCatching { Xtream.episodes(p) }.getOrDefault(emptyList()) }; updateEpisodeButtons() } }

        seeker = SeekController({ engine }) { t, d -> showOverlay(preview = t, previewDur = d) }
        root.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) engine?.setAspect(Prefs.aspect) }

        pPlay.setOnClickListener { engine?.let { it.playWhenReady = !it.playWhenReady }; showOverlay() }
        findViewById<View>(R.id.p_audio).setOnClickListener { openAudio() }
        findViewById<View>(R.id.p_subs).setOnClickListener { openSubs() }
        findViewById<View>(R.id.p_aspect).setOnClickListener { openAspect() }
        pPrev.setOnClickListener { goEpisode(-1) }; pNext.setOnClickListener { goEpisode(1) }
        if (isLive) { barWrap.visibility = View.GONE; pPlay.visibility = View.GONE }

        if (isLive) {
            Db.touchLive(key)
            chanList.layoutManager = LinearLayoutManager(this)
            chanList.adapter = ChanAdapter()
            scope.launch {
                channels = withContext(Dispatchers.IO) { Db.page(kind, group, 0, 5000) }
                chIndex = channels.indexOfFirst { it.url == key }
                chanList.adapter?.notifyDataSetChanged()
            }
        } else {
            resumeMs = Db.progressOf(key)
        }
    }

    private fun setTitles() {
        pTitle.text = cleanTitle(if (season > 0 && baseName.isNotBlank()) baseName else title)
        pSub.text = when {
            season > 0 -> "Season $season Episode $episode"
            isLive -> group
            else -> year?.toString() ?: ""
        }
        pSub.visibility = if (pSub.text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onStart() { super.onStart(); startEngine(resumeMs); ui.post(ticker) }

    override fun onStop() {
        ui.removeCallbacksAndMessages(null)
        seeker.cancel()
        saveProgress()
        releaseEngine()
        super.onStop()
    }

    // ---- engine ---------------------------------------------------------------------------------

    private val listener = object : Engine.Listener {
        override fun onBuffering(buffering: Boolean) { spin.visibility = if (buffering) View.VISIBLE else View.GONE }
        override fun onReady() {
            ready = true; retries = 0; spin.visibility = View.GONE
            ui.removeCallbacks(stallRun)
            if (!audioApplied) { audioApplied = true; applySavedAudio() }
            checkAudio()
            updateChips()
            engine?.let { e -> matchFrameRate(e.info().fps); e.setAspect(Prefs.aspect); e.applySubtitleStyle() }
        }
        override fun onEnded() {
            if (isLive) return
            Db.saveProgress(key, 10, 10)                       // finished: drop it from Continue Watching
            val n = nextEpisode()
            if (n != null && Prefs.autoNext) playNext(n) else { queueNext(); finish() }
        }
        override fun onError(msg: String) { handleProblem(msg) }
        override fun onPlayState(playing: Boolean) { pPlay.text = if (playing) "Pause" else "Play"; pPlay.setCompoundDrawablesWithIntrinsicBounds(if (playing) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0) }
    }

    private fun startEngine(pos: Long) {
        if (engine != null) return
        engine = ExoEngine(this, pv, root, isLive).also { it.listener = listener }
        start(currentUrl, pos)
    }

    private fun releaseEngine() {
        engine?.let { resumeMs = if (isLive) 0 else it.position; runCatching { it.release() } }
        engine = null
    }

    private fun start(url: String, pos: Long) {
        val e = engine ?: return
        currentUrl = url
        ready = false; matched = false; audioApplied = false
        spin.visibility = View.VISIBLE
        e.prepare(url, pos)
        e.playWhenReady = true
        e.setAspect(Prefs.aspect); e.applySubtitleStyle()
        ui.removeCallbacks(stallRun); ui.postDelayed(stallRun, 15_000)
        ui.removeCallbacks(audioRun); ui.postDelayed(audioRun, 3_000)
    }

    /** Audio the device cannot decode even with the FFmpeg decoders: say so once instead of playing silently. */
    private val audioRun = Runnable { checkAudio() }
    private var audioWarned = false
    private fun checkAudio() {
        val e = engine ?: return
        if (ready && !audioWarned && e.audioUnsupported()) { audioWarned = true; toast("This stream's audio format can't be decoded on this device") }
    }

    /** Nothing playing 15 s after start. */
    private val stallRun = Runnable { if (!ready) handleProblem("no picture after 15 s") }

    private fun handleProblem(reason: String) {
        if (isLive && retries++ < 2) { ui.postDelayed({ start(currentUrl, 0) }, 1500); return }
        toast("Playback problem: $reason")
    }

    private fun matchFrameRate(fps: Float) {
        if (matched || !Prefs.matchFps || fps < 20f) return
        matched = true
        runCatching {
            val d = windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { m -> listOf(1f, 2f, 2.5f).minOf { k -> abs(m.refreshRate - fps * k) } }
            if (best != null && best.modeId != cur.modeId) window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }

    private fun nextEpisode(): Xtream.Episode? {
        val i = episodeList.indexOfFirst { it.url == key }
        return if (i >= 0) episodeList.getOrNull(i + 1) else null
    }

    /** Finished (or nearly) an episode: Continue Watching should offer the next one. */
    private fun queueNext() {
        val n = nextEpisode() ?: return
        val p = parentUrl ?: return
        Db.saveProgress(n.url, 0, 0, p, "Next: S${n.season} E${n.number}")
    }

    private fun playNext(n: Xtream.Episode) = playEpisode(n, 0, "Next episode")

    private fun playEpisode(n: Xtream.Episode, startMs: Long, banner: String) {
        key = n.url; season = n.season; episode = n.number
        title = "${cleanTitle(baseName)}  S${n.season}E${n.number}"
        epLabel = "S${n.season} E${n.number}"
        resumeMs = startMs; retries = 0; audioWarned = false
        setTitles(); updateEpisodeButtons()
        showBanner("$banner: S${n.season} E${n.number}")
        start(n.url, startMs)
    }

    /** Previous (-1) / next (+1) episode from the player controls. Keeps the watched position of the one we leave. */
    private fun goEpisode(d: Int) {
        val i = episodeList.indexOfFirst { it.url == key }
        val t = episodeList.getOrNull(i + d) ?: return
        saveProgress()
        playEpisode(t, Db.progressOf(t.url), if (d > 0) "Next episode" else "Previous episode")
    }

    private fun updateEpisodeButtons() {
        val i = episodeList.indexOfFirst { it.url == key }
        val on = kind == Kind.SERIES && parentUrl != null && i >= 0
        pPrev.visibility = if (on && i > 0) View.VISIBLE else View.GONE
        pNext.visibility = if (on && i < episodeList.size - 1) View.VISIBLE else View.GONE
    }


    private fun saveProgress() {
        val e = engine ?: return
        if (isLive) return
        val dur = e.duration
        if (dur > 0 && e.position > 5000) { Db.saveProgress(key, e.position, dur, parentUrl, epLabel); if (e.position > dur * 0.95) queueNext() }
    }

    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (overlay.visibility == View.VISIBLE && seeker.pendingTarget == null) updateOverlay(null, 0)
            if (++tick % 10 == 0) saveProgress()
            ui.postDelayed(this, 1000)
        }
    }

    // ---- overlay --------------------------------------------------------------------------------

    private val hideOverlay = Runnable { if (side.visibility != View.VISIBLE) overlay.visibility = View.GONE }
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun resetHide() { ui.removeCallbacks(hideOverlay); if (Prefs.hideSecs > 0) ui.postDelayed(hideOverlay, Prefs.hideSecs * 1000L) }

    private fun showOverlay(preview: Long? = null, previewDur: Long = 0, focus: View? = null) {
        overlay.visibility = View.VISIBLE
        updateOverlay(preview, previewDur)
        updateChips()
        focus?.requestFocus()
        resetHide()
    }

    private fun updateOverlay(preview: Long?, previewDur: Long) {
        val e = engine ?: return
        if (isLive) { pTime.text = "LIVE  ·  ${e.name}"; return }
        val dur = if (previewDur > 0) previewDur else e.duration
        val pos = preview ?: e.position
        pBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        pTime.text = "${fmt(pos)} / ${fmt(dur)}" + if (preview != null) "     ${if (preview >= e.position) "▶▶ ${Prefs.ffStep}s" else "◀◀ ${Prefs.rwStep}s"}" else "     ${e.name}"
    }

    /** Resolution / codec / frame rate / audio layout chips from the playing streams. */
    private fun updateChips() {
        val e = engine ?: return
        val i = e.info()
        val list = ArrayList<String>()
        if (i.height > 0) list += when { i.height >= 2000 -> "4K"; i.height >= 1000 -> "FULL HD"; i.height >= 700 -> "HD"; else -> "SD" }
        i.codec?.let { list += it }
        if (i.fps > 0) list += "%.2f Hz".format(i.fps)
        if (i.channels > 0) list += when (i.channels) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> "${i.channels}ch" }
        chips.removeAllViews()
        for (t in list) chips.addView(TextView(this).apply {
            text = t; type(R.dimen.type_micro); setTextColor(getColor(R.color.text)); setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(context, 7), dp(context, 2), dp(context, 7), dp(context, 2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(context, 6) }
        })
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun showBanner(text: String) {
        banner.text = text; banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, 2500)
    }

    private fun View.isIn(parent: View): Boolean { var v: View? = this; while (v != null) { if (v === parent) return true; v = v.parent as? View }; return false }

    // ---- keys -----------------------------------------------------------------------------------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (chanList.visibility == View.VISIBLE || side.visibility == View.VISIBLE) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val p = engine ?: return super.dispatchKeyEvent(e)
        val canSeek = p.seekable && !isLive
        val f = currentFocus
        val inOverlay = overlay.visibility == View.VISIBLE && f != null && f.isIn(overlay)
        if (inOverlay) {
            resetHide()
            // Arrow keys move between the controls (Previous, Pause, Next, Audio, Subtitles, Aspect) and the progress line.
            val ctl = listOf<View>(pPrev, pPlay, pNext, findViewById(R.id.p_audio), findViewById(R.id.p_subs), findViewById(R.id.p_aspect)).filter { it.visibility == View.VISIBLE }
            if (f === barWrap) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (canSeek) seeker.press(-1, e.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (canSeek) seeker.press(1, e.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { (ctl.firstOrNull { it === pPlay } ?: ctl.firstOrNull())?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
            } else {
                val i = ctl.indexOf(f)
                if (i >= 0) when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { ctl.getOrNull(i - 1)?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { ctl.getOrNull(i + 1)?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (barWrap.visibility == View.VISIBLE) barWrap.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> return true
                }
            }
            if (e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { p.playWhenReady = !p.playWhenReady; return true }
            return super.dispatchKeyEvent(e)
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (canSeek) seeker.press(-1, e.repeatCount) else if (isLive && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && e.repeatCount == 0) showChannels(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (canSeek) seeker.press(1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (isLive) zap(-1) else showOverlay(focus = pPlay); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (isLive) zap(1) else showOverlay(focus = pPlay); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (isLive) zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (isLive) zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (e.repeatCount > 0) return true
                if (isLive) showChannels() else showOverlay(focus = pPlay)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (e.repeatCount == 0) { p.playWhenReady = !p.playWhenReady; showOverlay() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; showOverlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; showOverlay(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (pNext.visibility == View.VISIBLE) goEpisode(1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (pPrev.visibility == View.VISIBLE) goEpisode(-1); return true }
            KeyEvent.KEYCODE_MENU -> { showOverlay(focus = findViewById(R.id.p_subs)); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            side.visibility == View.VISIBLE -> side.visibility = View.GONE
            chanList.visibility == View.VISIBLE -> chanList.visibility = View.GONE
            overlay.visibility == View.VISIBLE -> { ui.removeCallbacks(hideOverlay); overlay.visibility = View.GONE }
            else -> super.onBackPressed()
        }
    }

    // ---- live: quick switching + mini guide -----------------------------------------------------

    private val zapRun = Runnable { channels.getOrNull(chIndex)?.let { startChannel(it) } }

    /** Up/Down: moves the selection immediately; the stream only switches ~350 ms after the last press. */
    private fun zap(d: Int) {
        if (channels.isEmpty()) return
        chIndex = ((if (chIndex < 0) 0 else chIndex) + d + channels.size) % channels.size
        showBanner("${chIndex + 1}   ${cleanTitle(channels[chIndex].name)}")
        ui.removeCallbacks(zapRun); ui.postDelayed(zapRun, 350)
    }

    private fun startChannel(c: Item) {
        key = c.url; title = c.name; group = c.group; baseName = c.name
        setTitles(); Db.touchLive(c.url)
        retries = 0; audioWarned = false
        start(c.url, 0)
    }

    private fun showChannels() {
        if (channels.isEmpty()) return
        chanList.visibility = View.VISIBLE
        (chanList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(maxOf(chIndex, 0), dp(this, 120))
        focusChannel(0)
    }

    private fun focusChannel(tries: Int) {
        val vh = chanList.findViewHolderForAdapterPosition(maxOf(chIndex, 0))
        if (vh != null) vh.itemView.requestFocus() else if (tries < 8) chanList.post { focusChannel(tries + 1) }
    }

    private inner class ChanAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(listRow(p.context)) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val tv = h.itemView as TextView
            tv.text = "${i + 1}   ${cleanTitle(channels[i].name)}"
            tv.setTextColor(getColor(if (i == chIndex) R.color.accent else R.color.text))
            tv.setOnClickListener {
                chIndex = i
                chanList.visibility = View.GONE
                startChannel(channels[i])
                notifyDataSetChanged()
            }
        }
    }

    private fun listRow(c: android.content.Context) = TextView(c).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 3) }
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        setBackgroundResource(R.drawable.row_bg)
        isFocusable = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; type(R.dimen.type_body); gravity = Gravity.CENTER_VERTICAL
        setTextColor(c.getColor(R.color.text))
    }

    // ---- side panel: subtitles, audio, aspect --------------------------------------------------

    private class Entry(val label: String, val checked: Boolean = false, val keepOpen: Boolean = false, val dim: Boolean = false, val action: () -> Unit)

    private fun showSide(titleText: String, entries: () -> List<Entry>) {
        sideTitle.text = titleText
        fun render(focusAt: Int = 0) {
            val list = entries()
            sideList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = list.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(listRow(p.context)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    val tv = h.itemView as TextView
                    tv.text = (if (list[i].checked) "✓   " else "     ") + list[i].label
                    tv.alpha = if (list[i].dim) 0.5f else 1f
                    tv.setOnClickListener {
                        list[i].action()
                        if (list[i].keepOpen) render(i) else side.visibility = View.GONE
                    }
                }
            }
            sideList.post { (sideList.layoutManager as LinearLayoutManager).findViewByPosition(focusAt)?.requestFocus() }
        }
        side.visibility = View.VISIBLE
        overlay.visibility = View.GONE          // the panel replaces the controls while it is open
        ui.removeCallbacks(hideOverlay)
        render()
    }

    /** Audio language/track is remembered per movie or series while it stays in Continue Watching. */
    private val titleKey get() = parentUrl ?: key

    private fun rememberAudio(t: Track) {
        if (isLive) return
        Db.setAudioPref(titleKey, t.lang, t.index)
        toast("Audio remembered for this ${if (parentUrl != null) "series" else "movie"}")
    }

    private fun applySavedAudio() {
        if (isLive) return
        val e = engine ?: return
        val pref = Db.audioPref(titleKey) ?: return
        val tracks = e.tracks(true).filter { it.supported }
        if (tracks.size < 2) return
        val match = tracks.firstOrNull { pref.first != null && it.lang == pref.first } ?: tracks.getOrNull(pref.second)
        if (match != null && !match.selected) e.select(true, match)
    }

    private fun trackEntries(audio: Boolean): List<Entry> {
        val e = engine ?: return emptyList()
        val out = ArrayList<Entry>()
        if (!audio) out += Entry("Off", e.textDisabled()) { e.disableText() }
        for (t in e.tracks(audio)) {
            out += if (t.supported) Entry(t.label, t.selected) { e.select(audio, t); if (audio) rememberAudio(t) }
            else Entry(t.label + "  (not supported here)", false, dim = true) { toast("This device can't decode that track") }
        }
        return out
    }

    private fun openAudio() = showSide("Audio") { trackEntries(true) }

    private fun openSubs() = showSide("Subtitles") {
        val e = engine
        trackEntries(false) + listOf(
            Entry("Find subtitles online…") { searchSubs() },
            Entry("Size:  ${Prefs.subSize}%", keepOpen = true) { Prefs.subSize = next(sizes, Prefs.subSize); e?.applySubtitleStyle() },
            Entry("Colour:  ${SettingsActivity.colorName(Prefs.subColor)}", keepOpen = true) {
                val cols = SettingsActivity.colors.map { it.second }; Prefs.subColor = next(cols, Prefs.subColor); e?.applySubtitleStyle()
            },
            Entry("Delay:  ${SettingsActivity.delayLabel(Prefs.subDelayMs)}" + if (e?.delayAppliesToEmbedded == true) "" else "  (downloaded subs)", keepOpen = true) {
                Prefs.subDelayMs = next(SettingsActivity.delays, Prefs.subDelayMs, 4)   // 1 s steps
                e?.setSubDelay(Prefs.subDelayMs)
            },
        )
    }

    private fun openAspect() = showSide("Aspect ratio") { aspectNames.mapIndexed { i, n -> Entry(n, Prefs.aspect == i, keepOpen = true) { Prefs.aspect = i; engine?.setAspect(i) } } }

    private fun <T> next(list: List<T>, cur: T, step: Int = 1): T = list[(list.indexOf(cur).coerceAtLeast(0) + step) % list.size]

    private val sizes = listOf(75, 100, 125, 150, 200)
    private val aspectNames = listOf("Fit", "Fill", "Zoom", "16:9", "4:3")

    // ---- OpenSubtitles --------------------------------------------------------------------------

    private fun dlg() = AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog)

    private fun searchSubs() {
        side.visibility = View.GONE
        toast("Searching OpenSubtitles…")
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { OpenSubs.search(baseName, year, season.takeIf { it > 0 }, episode.takeIf { it > 0 }) } }
            res.onFailure { toast(it.message ?: "Search failed"); return@launch }
            val hits = res.getOrThrow()
            if (hits.isEmpty()) { toast("No subtitles found"); return@launch }
            dlg().setTitle("Subtitles for ${cleanTitle(baseName)}")
                .setItems(hits.map { "${it.release}   [${it.lang}]  ↓${it.downloads}" }.toTypedArray()) { _, i -> download(hits[i]) }.show()
        }
    }

    private fun download(h: OpenSubs.Hit) {
        toast("Downloading…")
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { OpenSubs.download(h.fileId) } }
            res.onFailure { toast(it.message ?: "Download failed"); return@launch }
            hasExtSub = true
            engine?.addExternalSub(res.getOrThrow(), Prefs.subDelayMs)
            toast("Subtitles applied")
        }
    }
}
