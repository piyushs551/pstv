package com.pstv.tv

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/** Kotlin-side access to the design tokens in res/values/tokens.xml. Change the look there, not here. */
object Tokens {
    fun px(c: Context, res: Int) = c.resources.getDimension(res)
    fun pxi(c: Context, res: Int) = c.resources.getDimensionPixelSize(res)
    fun color(c: Context, res: Int) = c.getColor(res)
    fun float(c: Context, res: Int): Float = TypedValue().also { c.resources.getValue(res, it, true) }.float
    fun int(c: Context, res: Int) = c.resources.getInteger(res)
}

/** Set a text size from the type scale, e.g. `tv.type(R.dimen.type_body)`. */
fun TextView.type(res: Int) = setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(res))

/**
 * Outfit, bundled in assets/fonts. On Android 7 the system font map is what `android:fontFamily="sans-serif*"`
 * resolves through, so we re-point it (reflection). If that is not possible the system fonts are used instead.
 * Roles: sans-serif = Regular, sans-serif-medium = Medium, sans-serif-black = SemiBold.
 */
object Fonts {
    fun install(c: Context) {
        try {
            val reg = Typeface.createFromAsset(c.assets, "fonts/outfit_regular.ttf")
            val med = Typeface.createFromAsset(c.assets, "fonts/outfit_medium.ttf")
            val semi = Typeface.createFromAsset(c.assets, "fonts/outfit_semibold.ttf")
            val f = Typeface::class.java.getDeclaredField("sSystemFontMap").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val map = f.get(null) as MutableMap<String, Typeface>
            map["sans-serif"] = reg; map["sans-serif-light"] = reg; map["sans-serif-condensed"] = reg
            map["sans-serif-medium"] = med; map["sans-serif-black"] = semi
        } catch (_: Throwable) { /* keep system fonts */ }
    }

    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun strong(): Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
}
