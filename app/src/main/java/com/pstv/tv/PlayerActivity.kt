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
    private lateinit var pBar: ProgressBar
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
    private var ready = false
    private var fallbackUsed = false
    private var matched = false
    private var hasExtSub = false

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
        barWrap = findViewById(R.id.p_bar_wrap); pBar = findViewById(R.id.p_bar); pTime = findViewById(R.id.p_time); pPlay = findViewById(R.id.p_play)
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
        setTitles()

        seeker = SeekController({ engine }) { t, d -> showOverlay(preview = t, previewDur = d) }
        root.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) engine?.setAspect(Prefs.aspect) }

        pPlay.setOnClickListener { engine?.let { it.playWhenReady = !it.playWhenReady }; showOverlay() }
        findViewById<View>(R.id.p_audio).setOnClickListener { openAudio() }
        findViewById<View>(R.id.p_subs).setOnClickListener { openSubs() }
        findViewById<View>(R.id.p_aspect).setOnClickListener { openAspect() }
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

    override fun onStart() { super.onStart(); startEngine(if (isLive) Prefs.engineLive else Prefs.engineVod, resumeMs); ui.post(ticker) }

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
            updateChips()
            engine?.let { e -> matchFrameRate(e.info().fps); e.setAspect(Prefs.aspect); e.applySubtitleStyle() }
        }
        override fun onEnded() { if (!isLive) { Db.saveProgress(key, 0, 1); finish() } }
        override fun onError(msg: String) { handleProblem(msg) }
        override fun onPlayState(playing: Boolean) { pPlay.text = if (playing) "Pause" else "Play" }
    }

    private fun createEngine(kindName: String): Engine {
        if (kindName == "mpv") {
            try { return MpvEngine(this, root, pv, isLive) }
            catch (t: Throwable) { toast("MPV could not start on this device (${t.javaClass.simpleName}). Using ExoPlayer."); fallbackUsed = true }
        }
        return ExoEngine(this, pv, root, isLive)
    }

    private fun startEngine(kindName: String, pos: Long) {
        if (engine != null) return
        engine = createEngine(kindName).also { it.listener = listener }
        start(currentUrl, pos)
    }

    private fun releaseEngine() {
        engine?.let { resumeMs = if (isLive) 0 else it.position; runCatching { it.release() } }
        engine = null
    }

    private fun start(url: String, pos: Long) {
        val e = engine ?: return
        currentUrl = url
        ready = false; matched = false
        spin.visibility = View.VISIBLE
        e.prepare(url, pos)
        e.playWhenReady = true
        e.setAspect(Prefs.aspect); e.applySubtitleStyle()
        ui.removeCallbacks(stallRun); ui.postDelayed(stallRun, 15_000)
    }

    /** Nothing playing 15 s after start: try the other engine (once), if allowed in Settings. */
    private val stallRun = Runnable { if (!ready) handleProblem("no picture after 15 s") }

    private fun handleProblem(reason: String) {
        if (isLive && retries++ < 2 && engine is ExoEngine) { ui.postDelayed({ start(currentUrl, 0) }, 1500); return }
        if (Prefs.autoFallback && !fallbackUsed) { switchEngine(reason) }
        else toast("Playback problem: $reason" + if (!fallbackUsed && !Prefs.autoFallback) "  (enable auto fallback in Settings)" else "")
    }

    /** Move this stream to the other engine at the current position. */
    private fun switchEngine(reason: String) {
        fallbackUsed = true
        val toMpv = engine !is MpvEngine
        val pos = if (isLive) 0 else (engine?.position ?: resumeMs)
        toast("Switching to ${if (toMpv) "MPV" else "ExoPlayer"}: $reason")
        engine?.let { runCatching { it.release() } }
        engine = null
        engine = createEngine(if (toMpv) "mpv" else "exo").also { it.listener = listener }
        start(currentUrl, pos)
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

    private fun saveProgress() {
        val e = engine ?: return
        if (isLive) return
        val dur = e.duration
        if (dur > 0 && e.position > 5000) Db.saveProgress(key, e.position, dur)
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

    private fun showOverlay(preview: Long? = null, previewDur: Long = 0, focus: View? = null) {
        overlay.visibility = View.VISIBLE
        updateOverlay(preview, previewDur)
        updateChips()
        focus?.requestFocus()
        ui.removeCallbacks(hideOverlay)
        if (engine?.playWhenReady != false) ui.postDelayed(hideOverlay, 5000)
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
            text = t; textSize = 11f; setTextColor(getColor(R.color.text)); setBackgroundResource(R.drawable.chip_bg)
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
            // Focus is on the overlay: the progress bar seeks with Left/Right, buttons move normally.
            ui.removeCallbacks(hideOverlay); if (p.playWhenReady) ui.postDelayed(hideOverlay, 6000)
            if (f === barWrap && canSeek) {
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { seeker.press(-1, e.repeatCount); return true }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { seeker.press(1, e.repeatCount); return true }
            }
            if (e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { p.playWhenReady = !p.playWhenReady; return true }
            return super.dispatchKeyEvent(e)
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (canSeek) seeker.press(-1, e.repeatCount); return true }
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
        retries = 0; fallbackUsed = false
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
        isFocusable = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; gravity = Gravity.CENTER_VERTICAL
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
        ui.removeCallbacks(hideOverlay)
        render()
    }

    private fun otherEngineName() = if (engine is MpvEngine) "ExoPlayer" else "MPV"

    private fun trackEntries(audio: Boolean): List<Entry> {
        val e = engine ?: return emptyList()
        val out = ArrayList<Entry>()
        if (!audio) out += Entry("Off", e.textDisabled()) { e.disableText() }
        for (t in e.tracks(audio)) {
            out += if (t.supported) Entry(t.label, t.selected) { e.select(audio, t) }
            else Entry(t.label + "  (not supported here)", false, dim = true) { toast("${e.name} can't decode this track on this device. Use 'Try ${otherEngineName()}' below.") }
        }
        out += Entry("Try ${otherEngineName()} engine for this stream") { fallbackUsed = false; switchEngine("manual") }
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
