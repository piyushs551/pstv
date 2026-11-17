package com.pstv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView

/**
 * Card image: rounded corners (clipped in code, so it works on any device) and a top-biased crop, so faces in a
 * portrait poster shown in a 16:9 frame are not cut off. Logos use FIT_CENTER (set topCrop = false).
 */
class CardImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : ImageView(c, a) {
    private val path = Path()
    private val rect = RectF()
    private val m = Matrix()
    private val radius = c.resources.getDimension(R.dimen.radius_card)
    var topBias = 0.14f
    var topCrop = true
        set(v) { field = v; refit() }

    init { scaleType = ScaleType.FIT_XY }

    override fun setImageDrawable(d: Drawable?) { super.setImageDrawable(d); refit() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); refit() }

    private fun refit() {
        val d = drawable
        val dw = d?.intrinsicWidth ?: -1; val dh = d?.intrinsicHeight ?: -1
        if (!topCrop) { scaleType = ScaleType.FIT_CENTER; return }
        if (d == null || dw <= 0 || dh <= 0 || width == 0 || height == 0) { scaleType = ScaleType.FIT_XY; return }
        scaleType = ScaleType.MATRIX
        val s = maxOf(width.toFloat() / dw, height.toFloat() / dh)
        m.reset(); m.setScale(s, s)
        val extraY = dh * s - height
        m.postTranslate(-(dw * s - width) / 2f, -extraY * topBias)
        imageMatrix = m
    }

    override fun onDraw(canvas: Canvas) {
        if (width > 0) { rect.set(0f, 0f, width.toFloat(), height.toFloat()); path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW); canvas.clipPath(path) }
        super.onDraw(canvas)
    }
}
