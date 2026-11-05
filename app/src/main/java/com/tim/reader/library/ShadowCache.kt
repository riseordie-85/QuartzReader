package com.tim.reader.library

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tim.reader.theme.ShadowLook

/**
 * Book shadows are drawn once and reused, which kept the shelf test free of slow frames on the Tab.
 * Changing a shadow in the theme editor only redraws this one image.
 */
object ShadowCache {
    /** How far the image reaches past the book on every side, in pixels. */
    fun padding(look: ShadowLook, density: Float) = ((look.blurDp + look.distanceDp) * density * 1.5f).toInt() + 2

    private val memory = LruCache<String, ImageBitmap>(24)

    fun get(look: ShadowLook, width: Int, height: Int, density: Float): ImageBitmap {
        val key = "$look:$width:$height"
        return memory.get(key) ?: draw(look, width, height, density).also { memory.put(key, it) }
    }

    private fun draw(look: ShadowLook, width: Int, height: Int, density: Float): ImageBitmap {
        val pad = padding(look, density)
        val bitmap = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
        val base = look.color.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((look.opacity * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base))
            if (look.blurDp > 0f) maskFilter = BlurMaskFilter(look.blurDp * density, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).drawRect(
            pad.toFloat(),
            pad.toFloat(),
            (pad + width).toFloat(),
            (pad + height).toFloat(),
            paint,
        )
        return bitmap.asImageBitmap()
    }
}
