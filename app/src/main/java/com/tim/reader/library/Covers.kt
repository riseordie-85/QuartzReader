package com.tim.reader.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val COVER_WIDTH = 480

/** Cover thumbnails live in app storage, because they can always be rebuilt. */
object Covers {
    private val memory = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    private fun file(context: Context, bookId: String): File {
        val folder = File(context.filesDir, "covers").apply { mkdirs() }
        return File(folder, "$bookId.jpg")
    }

    fun save(context: Context, bookId: String, cover: Bitmap): String {
        val target = file(context, bookId)
        // Webtoon pages are very tall, so their cover is the top of the first page.
        val framed = if (cover.height > cover.width * 3 / 2) {
            Bitmap.createBitmap(cover, 0, 0, cover.width, cover.width * 3 / 2)
        } else {
            cover
        }
        val width = minOf(framed.width, COVER_WIDTH).coerceAtLeast(1)
        val height = (framed.height.toLong() * width / framed.width).toInt().coerceAtLeast(1)
        val scaled = if (width == framed.width) framed else Bitmap.createScaledBitmap(framed, width, height, true)
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return target.path
    }

    /** A cover the user picked. Each gets a new name, so the old one can't linger in memory. */
    fun saveCustom(context: Context, bookId: String, cover: Bitmap): String {
        val folder = File(context.filesDir, "custom-covers").apply { mkdirs() }
        val target = File(folder, "$bookId-${System.currentTimeMillis()}.jpg")
        val width = minOf(cover.width, COVER_WIDTH * 2).coerceAtLeast(1)
        val height = (cover.height.toLong() * width / cover.width).toInt().coerceAtLeast(1)
        val scaled = if (width == cover.width) cover else Bitmap.createScaledBitmap(cover, width, height, true)
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return target.path
    }

    fun deleteCustom(path: String?) {
        if (path == null) return
        memory.remove(path)
        File(path).delete()
    }

    fun delete(context: Context, bookId: String) {
        val target = file(context, bookId)
        memory.remove(target.path)
        target.delete()
    }

    fun cached(path: String): ImageBitmap? = memory.get(path)

    suspend fun load(path: String): ImageBitmap? = memory.get(path) ?: withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { memory.put(path, it) }
    }

    /** A plain cover for books without artwork, such as MOBI files. */
    fun placeholder(title: String, seed: Int): Bitmap {
        val width = COVER_WIDTH
        val height = width * 3 / 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = ((seed % 360) + 360) % 360
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.55f)),
                Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.7f, 0.22f)),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, text, width - 72)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(7)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.translate(36f, 72f)
        layout.draw(canvas)
        return bitmap
    }
}

@Composable
fun rememberCover(path: String?): State<ImageBitmap?> =
    produceState(path?.let(Covers::cached), path) {
        value = path?.let { Covers.load(it) }
    }
