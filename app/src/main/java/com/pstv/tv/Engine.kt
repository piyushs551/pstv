package com.pstv.tv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.Locale

data class Track(val label: String, val selected: Boolean, val supported: Boolean = true, val ref: Any? = null)
data class StreamInfo(val height: Int = 0, val fps: Float = 0f, val codec: String? = null, val channels: Int = 0)

/** A playback backend. PlayerActivity only talks to this, so ExoPlayer and mpv are interchangeable. */
interface Engine {
    interface Listener {
        fun onBuffering(buffering: Boolean)
        fun onReady()
        fun onEnded()
        fun onError(msg: String)
        fun onPlayState(playing: Boolean)
    }

    val name: String
    var listener: Listener?
    var playWhenReady: Boolean
    val position: Long
    /** 0 when unknown (live). */
    val duration: Long
    val seekable: Boolean

    fun prepare(url: String, startMs: Long)
    fun seekTo(ms: Long)
    fun tracks(audio: Boolean): List<Track>
    fun select(audio: Boolean, t: Track)
    fun disableText()
    fun textDisabled(): Boolean
    fun info(): StreamInfo
    /** True when the stream has audio but this engine cannot decode any of it (e.g. MP2 on ExoPlayer): silent playback. */
    fun audioUnsupported(): Boolean = false
    fun applySubtitleStyle()
    fun setAspect(mode: Int)
    /** Attach a downloaded SRT. [delayMs] is applied by re-timing (Exo) or the player's own delay (mpv). */
    fun addExternalSub(raw: String, delayMs: Int)
    /** True if the delay also applies to embedded tracks. */
    val delayAppliesToEmbedded: Boolean
    fun setSubDelay(ms: Int)
    fun release()
}

// ====================================================================================================
// ExoPlayer
// ====================================================================================================

class ExoEngine(private val act: Activity, private val pv: PlayerView, private val root: FrameLayout, private val live: Boolean) : Engine {
    override val name = "ExoPlayer"
    override var listener: Engine.Listener? = null
    private val p: ExoPlayer
    private var url = ""
    private var extRaw: String? = null
    private var extFile: File? = null

    init {
        val factory = DefaultRenderersFactory(act)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)             // hardware first, fall back only if a codec fails
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.UA)
        val lowLatency = live && Prefs.lowLatency
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (lowLatency) 3_000 else 15_000, if (lowLatency) 12_000 else 40_000, if (lowLatency) 800 else 2_000, if (lowLatency) 2_000 else 4_000)
            .setTargetBufferBytes(Prefs.bufferMb * 1024 * 1024)   // bounds RAM even for 4K HEVC
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        p = ExoPlayer.Builder(act, factory).setMediaSourceFactory(DefaultMediaSourceFactory(http)).setLoadControl(load).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                listener?.onBuffering(s == Player.STATE_BUFFERING)
                if (s == Player.STATE_READY) listener?.onReady()
                if (s == Player.STATE_ENDED) listener?.onEnded()
            }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { listener?.onPlayState(ready) }
            override fun onPlayerError(e: PlaybackException) { listener?.onError(e.errorCodeName) }
        })
        pv.visibility = View.VISIBLE
        pv.player = p
        applySubtitleStyle()
    }

    override var playWhenReady: Boolean get() = p.playWhenReady; set(v) { p.playWhenReady = v }
    override val position get() = p.currentPosition
    override val duration get() = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    override val seekable get() = p.isCurrentMediaItemSeekable

    private fun item(): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        extFile?.let {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(it)).setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(Prefs.subLang).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            ))
        }
        return b.build()
    }

    override fun prepare(url: String, startMs: Long) {
        this.url = url
        if (live) { extRaw = null; extFile = null }
        p.setMediaItem(item(), if (startMs > 5000) startMs else C.TIME_UNSET)
        p.prepare(); p.playWhenReady = true
    }

    override fun seekTo(ms: Long) = p.seekTo(ms)

    override fun tracks(audio: Boolean): List<Track> {
        val type = if (audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val out = ArrayList<Track>(); var n = 0
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (t in 0 until g.length) {
                val label = label(g.getTrackFormat(t), n++)
                // Unsupported tracks are listed (greyed) so you can see what the stream has but this device can't decode.
                out += Track(label, g.isTrackSelected(t) && !off, g.isTrackSupported(t), Pair(g, t))
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    override fun select(audio: Boolean, t: Track) {
        val (g, i) = t.ref as Pair<androidx.media3.common.Tracks.Group, Int>
        val type = if (audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
    }

    override fun audioUnsupported(): Boolean {
        val g = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        return g.isNotEmpty() && g.none { it.isSupported }
    }

    override fun disableText() { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
    override fun textDisabled() = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    override fun info(): StreamInfo {
        val v = p.videoFormat; val a = p.audioFormat
        return StreamInfo(
            v?.height ?: 0, v?.frameRate?.takeIf { it > 0 } ?: 0f,
            when (v?.sampleMimeType) { MimeTypes.VIDEO_H265 -> "HEVC"; MimeTypes.VIDEO_H264 -> "H.264"; MimeTypes.VIDEO_AV1 -> "AV1"; else -> null },
            a?.channelCount ?: 0,
        )
    }

    override fun applySubtitleStyle() {
        pv.subtitleView?.apply {
            setStyle(CaptionStyleCompat(Prefs.subColor, 0, 0, CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Prefs.subSize / 100f)
        }
    }

    override fun setAspect(mode: Int) {
        val lp = pv.layoutParams as FrameLayout.LayoutParams
        val cw = root.width; val ch = root.height
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

    override val delayAppliesToEmbedded = false

    override fun addExternalSub(raw: String, delayMs: Int) { extRaw = raw; applyExt(delayMs) }
    override fun setSubDelay(ms: Int) { if (extRaw != null) applyExt(ms) }

    private fun applyExt(delayMs: Int) {
        val raw = extRaw ?: return
        val f = File(act.cacheDir, "ext_${System.nanoTime()}.srt")
        f.writeText(Srt.shift(raw, delayMs))
        extFile?.delete(); extFile = f
        val pos = p.currentPosition; val play = p.playWhenReady
        p.setMediaItem(item(), pos); p.prepare(); p.playWhenReady = play
    }

    override fun release() { pv.player = null; p.release(); extFile?.delete() }

    private fun label(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
        val extra = buildList {
            f.label?.let { add(it) }
            if (f.channelCount > 0 && MimeTypes.isAudio(f.sampleMimeType)) add(if (f.channelCount == 6) "5.1" else if (f.channelCount == 8) "7.1" else "${f.channelCount}ch")
            f.sampleMimeType?.substringAfterLast('/')?.let { add(it.uppercase()) }
        }.distinct().joinToString(" · ")
        return listOfNotNull("Track ${n + 1}" + (lang?.let { " [$it]" } ?: ""), extra.ifEmpty { null }).joinToString("  —  ")
    }
}

// ====================================================================================================
// mpv (libmpv + FFmpeg). Loaded only when this engine is actually used, so it costs no RAM otherwise.
// ====================================================================================================

class MpvEngine(private val ctx: Context, private val root: FrameLayout, private val pv: PlayerView, private val live: Boolean) : Engine {
    override val name = "MPV"
    override var listener: Engine.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val mpv: MPVLib = MPVLib.create(ctx) ?: throw IllegalStateException("libmpv failed to create")
    private val surface = SurfaceView(ctx)
    private var surfaceReady = false
    private var pending: Pair<String, Long>? = null
    private var released = false
    private var aspectMode = 0

    private val observer = object : MPVLib.EventObserver {
        override fun event(eventId: Int) {
            if (released) return
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post { if (!released) { clearStart(); listener?.onReady() } }
            }
        }
        override fun eventEndFile(reason: Int, error: Int) {
            if (released) return
            when (reason) {
                MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF -> main.post { listener?.onEnded() }
                MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR -> main.post { listener?.onError("mpv error $error") }
            }
        }
        override fun eventProperty(property: String, value: Boolean) {
            if (released) return
            when (property) {
                "paused-for-cache" -> main.post { listener?.onBuffering(value) }
                "pause" -> main.post { listener?.onPlayState(!value) }
            }
        }
    }

    init {
        fun o(k: String, v: String) { mpv.setOptionString(k, v) }
        o("vo", "gpu"); o("gpu-context", "android"); o("opengl-es", "yes")
        o("hwdec", "mediacodec,mediacodec-copy"); o("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        o("ao", "audiotrack,opensles")
        o("user-agent", Http.UA); o("tls-verify", "no")
        o("cache", "yes")
        o("demuxer-max-bytes", "${Prefs.bufferMb}MiB"); o("demuxer-max-back-bytes", "8MiB")
        o("demuxer-readahead-secs", if (live && Prefs.lowLatency) "3" else "20")
        o("keep-open", "no"); o("force-window", "no"); o("save-position-on-quit", "no")
        mpv.init()
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.addObserver(observer)

        pv.visibility = View.GONE
        root.addView(surface, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (released) return
                mpv.attachSurface(h.surface)
                mpv.setOptionString("force-window", "yes")
                surfaceReady = true
                pending?.let { (u, s) -> pending = null; load(u, s) }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (released) return
                surfaceReady = false
                mpv.setPropertyString("vo", "null"); mpv.setOptionString("force-window", "no"); mpv.detachSurface()
            }
        })
        applySubtitleStyle()
    }

    private fun clearStart() { runCatching { mpv.setPropertyString("start", "none") } }

    private fun load(url: String, startMs: Long) {
        mpv.setPropertyString("start", if (startMs > 5000) "%.3f".format(Locale.US, startMs / 1000.0) else "none")
        mpv.command(arrayOf("loadfile", url))
        mpv.setPropertyBoolean("pause", false)
        setAspect(aspectMode)
    }

    override fun prepare(url: String, startMs: Long) { if (surfaceReady) load(url, startMs) else pending = url to startMs }

    override var playWhenReady: Boolean
        get() = mpv.getPropertyBoolean("pause") != true
        set(v) { mpv.setPropertyBoolean("pause", !v) }
    override val position get() = ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()
    override val duration get() = ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
    override val seekable get() = mpv.getPropertyBoolean("seekable") == true

    override fun seekTo(ms: Long) { mpv.command(arrayOf("seek", "%.3f".format(Locale.US, ms / 1000.0), "absolute")) }

    override fun tracks(audio: Boolean): List<Track> {
        val want = if (audio) "audio" else "sub"
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        val out = ArrayList<Track>()
        for (i in 0 until n) {
            if (mpv.getPropertyString("track-list/$i/type") != want) continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val lang = mpv.getPropertyString("track-list/$i/lang")?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
            val title = mpv.getPropertyString("track-list/$i/title")
            val codec = mpv.getPropertyString("track-list/$i/codec")?.uppercase()
            val ch = mpv.getPropertyInt("track-list/$i/demux-channel-count")
            val extra = listOfNotNull(title, if (audio) ch?.let { c -> if (c == 6) "5.1" else if (c == 8) "7.1" else "${c}ch" } else null, codec).distinct().joinToString(" · ")
            out += Track(listOfNotNull("Track $id" + (lang?.let { " [$it]" } ?: ""), extra.ifEmpty { null }).joinToString("  —  "),
                mpv.getPropertyBoolean("track-list/$i/selected") == true, true, id)
        }
        return out
    }

    override fun select(audio: Boolean, t: Track) { mpv.setPropertyString(if (audio) "aid" else "sid", (t.ref as Int).toString()) }
    override fun disableText() { mpv.setPropertyString("sid", "no") }
    override fun textDisabled() = mpv.getPropertyString("sid") == "no"

    override fun info(): StreamInfo = StreamInfo(
        mpv.getPropertyInt("video-params/h") ?: 0,
        (mpv.getPropertyDouble("container-fps") ?: 0.0).toFloat(),
        mpv.getPropertyString("video-codec")?.let { c -> when { c.contains("hevc", true) || c.contains("h265", true) -> "HEVC"; c.contains("h264", true) -> "H.264"; c.contains("av1", true) -> "AV1"; else -> null } },
        mpv.getPropertyInt("audio-params/channel-count") ?: 0,
    )

    override fun applySubtitleStyle() {
        runCatching {
            mpv.setPropertyDouble("sub-scale", Prefs.subSize / 100.0)
            mpv.setPropertyString("sub-color", "#%06X".format(Prefs.subColor and 0xFFFFFF))
            mpv.setPropertyDouble("sub-delay", Prefs.subDelayMs / 1000.0)
        }
    }

    override fun setAspect(mode: Int) {
        aspectMode = mode
        runCatching {
            mpv.setPropertyBoolean("keepaspect", mode != 1)
            mpv.setPropertyDouble("panscan", if (mode == 2) 1.0 else 0.0)
            mpv.setPropertyString("video-aspect-override", when (mode) { 3 -> "1.777778"; 4 -> "1.333333"; else -> "-1" })
        }
    }

    override val delayAppliesToEmbedded = true

    override fun addExternalSub(raw: String, delayMs: Int) {
        val f = File(ctx.cacheDir, "ext_${System.nanoTime()}.srt")
        f.writeText(raw)
        mpv.command(arrayOf("sub-add", f.absolutePath, "select"))
        setSubDelay(delayMs)
    }

    override fun setSubDelay(ms: Int) { mpv.setPropertyDouble("sub-delay", ms / 1000.0) }

    override fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        runCatching { mpv.removeObserver(observer) }
        runCatching { mpv.setPropertyString("vo", "null") }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
        root.removeView(surface)
        pv.visibility = View.VISIBLE
    }
}
