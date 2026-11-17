package com.pstv.tv

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class PlayerActivity : BaseActivity() {
    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var pv: PlayerView
    private lateinit var spin: View
    private lateinit var overlay: View
    private lateinit var pTitle: TextView
    private lateinit var pBar: ProgressBar
    private lateinit var pTime: TextView
    private lateinit var pHint: TextView
    private lateinit var banner: TextView
    private lateinit var chanList: RecyclerView

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

    private var extSrt: String? = null
    private var extSubFile: File? = null
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
        overlay = findViewById(R.id.overlay); pTitle = findViewById(R.id.p_title); pBar = findViewById(R.id.p_bar)
        pTime = findViewById(R.id.p_time); pHint = findViewById(R.id.p_hint); banner = findViewById(R.id.p_banner)
        chanList = findViewById(R.id.chan_list)

        val i = intent
        currentUrl = i.getStringExtra("url") ?: return finish()
        title = i.getStringExtra("title") ?: ""
        key = i.getStringExtra("key") ?: currentUrl
        kind = Kind.values()[i.getIntExtra("kind", 1)]
        group = i.getStringExtra("group") ?: ""
        season = i.getIntExtra("season", 0); episode = i.getIntExtra("episode", 0)
        baseName = i.getStringExtra("baseName") ?: title
        year = i.getIntExtra("year", -1).takeIf { it > 0 }
        pTitle.text = cleanTitle(title)

        seeker = SeekController({ player }) { t, d -> showOverlay(preview = t, previewDur = d) }
        applySubtitleStyle()
        root.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) applyAspect() }

        if (isLive) {
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

    override fun onStart() { super.onStart(); initPlayer(); ui.post(ticker) }

    override fun onStop() {
        ui.removeCallbacks(ticker); ui.removeCallbacksAndMessages(null)
        seeker.cancel()
        saveProgress()
        releasePlayer()
        super.onStop()
    }

    // ---- player ---------------------------------------------------------------------------------

    private fun initPlayer() {
        if (player != null) return
        val factory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)             // hardware first, fall back only if a codec fails
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.UA)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 2_000, 4_000)
            .setTargetBufferBytes(32 * 1024 * 1024)      // keeps RAM bounded even for 4K HEVC
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(this, factory).setMediaSourceFactory(DefaultMediaSourceFactory(http)).setLoadControl(load).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                spin.visibility = if (s == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (s == Player.STATE_READY) retries = 0
                if (s == Player.STATE_ENDED && !isLive) { Db.saveProgress(key, 0, 1); finish() }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (isLive && retries++ < 3) { ui.postDelayed({ start(currentUrl, 0) }, 1500); return }
                toast("Playback error: ${e.errorCodeName}")
            }
        })
        pv.player = p
        player = p
        start(currentUrl, resumeMs)
        applyAspect()
        applySubtitleStyle()
    }

    private fun releasePlayer() {
        player?.let { resumeMs = if (isLive) 0 else it.currentPosition; it.release() }
        pv.player = null
        player = null
    }

    private fun buildItem(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        extSubFile?.let {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(it))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(Prefs.subLang)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            ))
        }
        return b.build()
    }

    private fun start(url: String, pos: Long) {
        val p = player ?: return
        currentUrl = url
        p.setMediaItem(buildItem(url), if (pos > 5000) pos else C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
    }

    private fun saveProgress() {
        val p = player ?: return
        if (isLive) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: return
        if (p.currentPosition > 5000) Db.saveProgress(key, p.currentPosition, dur)
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

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun showOverlay(preview: Long? = null, previewDur: Long = 0) {
        overlay.visibility = View.VISIBLE
        updateOverlay(preview, previewDur)
        ui.removeCallbacks(hideOverlay)
        if (player?.playWhenReady != false) ui.postDelayed(hideOverlay, 4000)
    }

    private fun updateOverlay(preview: Long?, previewDur: Long) {
        val p = player ?: return
        if (isLive) {
            pBar.visibility = View.GONE
            pTime.text = "LIVE"
            pHint.text = "▲ ▼ change channel     OK channel list     ≡ options"
            return
        }
        val dur = if (previewDur > 0) previewDur else p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val pos = preview ?: p.currentPosition
        pBar.visibility = View.VISIBLE
        pBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        pTime.text = "${fmt(pos)} / ${fmt(dur)}" + if (preview != null) "      ${if (preview >= p.currentPosition) "▶▶" else "◀◀"} ${Prefs.seekStep}s steps" else ""
        pHint.text = "◀ ▶ seek ${Prefs.seekStep}s (hold to speed up)     OK play/pause     ▲ options"
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun showBanner(text: String) {
        banner.text = text; banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, 2500)
    }

    // ---- keys -----------------------------------------------------------------------------------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (chanList.visibility == View.VISIBLE) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val p = player ?: return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (p.isCurrentMediaItemSeekable && !isLive) seeker.press(-1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (p.isCurrentMediaItemSeekable && !isLive) seeker.press(1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (isLive) zap(-1) else options(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (isLive) zap(1) else showOverlay(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (isLive) zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (isLive) zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (e.repeatCount > 0) return true
                if (isLive && e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) showChannels()
                else { p.playWhenReady = !p.playWhenReady; showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; showOverlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; showOverlay(); return true }
            KeyEvent.KEYCODE_MENU -> { options(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            chanList.visibility == View.VISIBLE -> chanList.visibility = View.GONE
            overlay.visibility == View.VISIBLE && !isLive -> { ui.removeCallbacks(hideOverlay); overlay.visibility = View.GONE }
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
        key = c.url; title = c.name; pTitle.text = cleanTitle(c.name)
        retries = 0
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
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val tv = TextView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 3) }
                setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
                setBackgroundResource(R.drawable.row_bg)
                isFocusable = true; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; gravity = Gravity.CENTER_VERTICAL
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
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

    // ---- options: subtitles, audio, aspect ------------------------------------------------------

    private fun dlg() = AlertDialog.Builder(this, R.style.Theme_Pstv_Dialog)

    private fun options() {
        val labels = arrayOf(
            "Subtitles", "Find subtitles online (OpenSubtitles)", "Audio track",
            "Aspect ratio:  ${aspectNames[Prefs.aspect]}",
            "Subtitle size:  ${Prefs.subSize}%", "Subtitle colour:  ${SettingsActivity.colorName(Prefs.subColor)}",
            "Subtitle delay:  ${SettingsActivity.delayLabel(Prefs.subDelayMs)}",
        )
        dlg().setTitle("Options").setItems(labels) { _, w ->
            when (w) {
                0 -> trackDialog(C.TRACK_TYPE_TEXT, "Subtitles", true)
                1 -> searchSubs()
                2 -> trackDialog(C.TRACK_TYPE_AUDIO, "Audio track", false)
                3 -> dlg().setTitle("Aspect ratio").setSingleChoiceItems(aspectNames, Prefs.aspect) { d, i -> Prefs.aspect = i; applyAspect(); d.dismiss() }.show()
                4 -> dlg().setTitle("Subtitle size").setSingleChoiceItems(sizes.map { "$it%" }.toTypedArray(), sizes.indexOf(Prefs.subSize)) { d, i -> Prefs.subSize = sizes[i]; applySubtitleStyle(); d.dismiss() }.show()
                5 -> dlg().setTitle("Subtitle colour").setSingleChoiceItems(SettingsActivity.colors.map { it.first }.toTypedArray(), SettingsActivity.colors.indexOfFirst { it.second == Prefs.subColor }) { d, i -> Prefs.subColor = SettingsActivity.colors[i].second; applySubtitleStyle(); d.dismiss() }.show()
                6 -> dlg().setTitle("Subtitle delay (downloaded subtitles)")
                    .setSingleChoiceItems(SettingsActivity.delays.map { SettingsActivity.delayLabel(it) }.toTypedArray(), SettingsActivity.delays.indexOf(Prefs.subDelayMs)) { d, i ->
                        Prefs.subDelayMs = SettingsActivity.delays[i]; d.dismiss()
                        if (extSrt != null) applyExternalSub() else toast("Delay applies to subtitles downloaded from OpenSubtitles")
                    }.show()
            }
        }.show()
    }

    private val sizes = listOf(75, 100, 125, 150, 200)
    private val aspectNames = arrayOf("Fit", "Fill", "Zoom", "16:9", "4:3")

    private fun applySubtitleStyle() {
        pv.subtitleView?.apply {
            setStyle(CaptionStyleCompat(Prefs.subColor, 0, 0, CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Prefs.subSize / 100f)
        }
    }

    private fun applyAspect() {
        val lp = pv.layoutParams as FrameLayout.LayoutParams
        val cw = root.width; val ch = root.height
        val mode = Prefs.aspect
        when (mode) {
            0, 1, 2 -> {
                pv.resizeMode = when (mode) { 0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT; 1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            else -> {
                if (cw == 0 || ch == 0) return
                val r = if (mode == 3) 16f / 9f else 4f / 3f
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL   // stretch the video into a box of the chosen ratio
                if (cw.toFloat() / ch > r) { lp.height = ch; lp.width = (ch * r).toInt() } else { lp.width = cw; lp.height = (cw / r).toInt() }
            }
        }
        lp.gravity = Gravity.CENTER
        pv.layoutParams = lp
    }

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
        val extra = buildList {
            f.label?.let { add(it) }
            if (f.channelCount > 0 && MimeTypes.isAudio(f.sampleMimeType)) add(if (f.channelCount == 6) "5.1" else if (f.channelCount == 8) "7.1" else "${f.channelCount}ch")
            f.sampleMimeType?.substringAfterLast('/')?.let { add(it.uppercase()) }
        }.distinct().joinToString(" · ")
        return listOfNotNull(lang ?: "Track ${n + 1}", extra.ifEmpty { null }).joinToString("  —  ")
    }

    private fun trackDialog(type: Int, titleText: String, includeOff: Boolean) {
        val p = player ?: return
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (includeOff) {
            labels += "Off"
            acts += { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build() }
        }
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (t in 0 until g.length) {
                if (!g.isTrackSupported(t)) continue
                labels += trackLabel(g.getTrackFormat(t), n++)
                acts += { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build() }
            }
        }
        if (labels.isEmpty() || (includeOff && labels.size == 1)) { toast(if (type == C.TRACK_TYPE_TEXT) "No embedded subtitles. Try 'Find subtitles online'." else "No selectable tracks"); return }
        dlg().setTitle(titleText).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    // ---- OpenSubtitles --------------------------------------------------------------------------

    private fun searchSubs() {
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
            extSrt = res.getOrThrow()
            applyExternalSub()
        }
    }

    /** Writes the (delay-shifted) SRT and re-attaches it at the current position. */
    private fun applyExternalSub() {
        val raw = extSrt ?: return
        val p = player ?: return
        val f = File(cacheDir, "ext_${System.nanoTime()}.srt")
        f.writeText(Srt.shift(raw, Prefs.subDelayMs))
        extSubFile?.delete()
        extSubFile = f
        val pos = p.currentPosition; val play = p.playWhenReady
        p.setMediaItem(buildItem(currentUrl), pos)
        p.prepare(); p.playWhenReady = play
        toast("Subtitles applied")
    }
}
