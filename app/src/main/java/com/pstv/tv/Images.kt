package com.pstv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.load
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import coil.size.Precision
import java.io.File

/** Coil setup: small memory cache, disk cache capped by the user's setting, name tiles when there is no art. */
object Images {
    lateinit var loader: ImageLoader
        private set
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext; rebuild() }

    /** Call again after changing the disk cache size in Settings. */
    fun rebuild() {
        val old = if (this::loader.isInitialized) loader else null
        loader = ImageLoader.Builder(ctx)
            .okHttpClient(Http.client)
            .memoryCache { MemoryCache.Builder(ctx).maxSizeBytes(24 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(ctx.cacheDir, "img")).maxSizeBytes(Prefs.cacheMb.coerceIn(10, 2000) * 1024L * 1024L).build() }
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
        old?.shutdown()
    }

    fun diskBytes(): Long = loader.diskCache?.size ?: 0L

    fun clear() {
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    /** Size-matched load into a card. Re-binding the same view cancels the previous request. */
    fun card(v: ImageView, url: String?, name: String, w: Int, h: Int) {
        val tile = NameTile(name)
        if (url.isNullOrBlank()) { v.setImageDrawable(tile); return }
        v.load(url, loader) {
            size(w, h)
            precision(Precision.INEXACT)
            placeholder(tile)
            error(tile)
            fallback(tile)
        }
    }

    /** Warm the memory cache for something about to be shown (hero banner next candidates). */
    fun preload(url: String?, w: Int, h: Int) {
        if (url.isNullOrBlank()) return
        loader.enqueue(
            ImageRequest.Builder(ctx).data(url).size(Size(w, h)).precision(Precision.INEXACT)
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).build()
        )
    }
}

/** Generated tile: gradient from the title's hash plus its initials. */
class NameTile(private val name: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    override fun draw(c: Canvas) {
        val b = bounds
        val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
        val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.42f))
        val c2 = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.6f, 0.22f))
        paint.shader = LinearGradient(0f, b.top.toFloat(), b.width().toFloat(), b.bottom.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        c.drawRect(b, paint)
        text.textSize = minOf(b.width(), b.height()) * 0.38f
        c.drawText(initials, b.exactCenterX(), b.exactCenterY() - (text.ascent() + text.descent()) / 2, text)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
