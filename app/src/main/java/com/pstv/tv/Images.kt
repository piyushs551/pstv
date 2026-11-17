package com.pstv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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

    /** True if a (logo) bitmap is mostly dark/transparent, so it needs a light backing to be visible. */
    fun isDark(b: android.graphics.Bitmap): Boolean {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 8, 8, true)
        var sum = 0.0; var wsum = 0.0
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = s.getPixel(x, y); val a = android.graphics.Color.alpha(p) / 255.0
            val l = (0.299 * android.graphics.Color.red(p) + 0.587 * android.graphics.Color.green(p) + 0.114 * android.graphics.Color.blue(p)) / 255.0
            sum += l * a; wsum += a
        }
        return wsum < 1.0 || sum / wsum < 0.45
    }

    fun diskBytes(): Long = loader.diskCache?.size ?: 0L

    fun clear() {
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    /** Size-matched load into a card. Re-binding the same view cancels the previous request. */
    fun card(v: ImageView, url: String?, name: String, w: Int, h: Int, icon: Int = 0, onBitmap: ((android.graphics.Bitmap) -> Unit)? = null) {
        val tile = NameTile(name, v.context, icon)
        if (url.isNullOrBlank()) { v.setImageDrawable(tile); return }
        v.load(url, loader) {
            size(w, h)
            precision(Precision.INEXACT)
            placeholder(tile)
            error(tile)
            fallback(tile)
            if (onBitmap != null) listener(onSuccess = { _, r -> (r.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let(onBitmap) })
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

/** Generated tile for items without art: gradient from the title's hash, a kind icon and the full name (no initials). */
class NameTile(private val name: String, private val ctx: Context? = null, private val iconRes: Int = 0) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); typeface = Fonts.medium() }
    private val icon = if (ctx != null && iconRes != 0) ctx.getDrawable(iconRes)?.mutate()?.also { it.setTint(0xB3FFFFFF.toInt()) } else null
    private var layout: StaticLayout? = null
    private var layoutW = 0

    override fun draw(c: Canvas) {
        val b = bounds
        val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
        val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.36f))
        val c2 = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.55f, 0.18f))
        paint.shader = LinearGradient(0f, b.top.toFloat(), b.width().toFloat(), b.bottom.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        c.drawRect(b, paint)
        val small = b.width() < 160
        val iconSize = if (small) minOf(b.width(), b.height()) / 2 else (b.height() * 0.22f).toInt().coerceAtLeast(24)
        val wText = (b.width() * 0.82f).toInt()
        if (!small) {
            if (layout == null || layoutW != wText) {
                text.textSize = (b.height() * 0.11f).coerceAtLeast(14f)
                layout = StaticLayout.Builder.obtain(name, 0, name.length, text, wText).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
                layoutW = wText
            }
        }
        val lh = if (small) 0 else layout?.height ?: 0
        val gap = if (icon != null && !small) iconSize / 3 else 0
        val total = (if (icon != null) iconSize else 0) + gap + lh
        var y = b.top + (b.height() - total) / 2f
        icon?.let { d -> val x = b.centerX() - iconSize / 2; d.setBounds(x, y.toInt(), x + iconSize, y.toInt() + iconSize); d.draw(c); y += iconSize + gap }
        if (!small) layout?.let { l -> c.save(); c.translate(b.left + (b.width() - wText) / 2f, y); l.draw(c); c.restore() }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
