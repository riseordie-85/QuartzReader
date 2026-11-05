package com.tim.reader.reader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

private const val CARD_WIDTH = 1080
private const val BLUR_WIDTH = 270
private const val BLUR_RADIUS = 2
private const val BLUR_PASSES = 3
private const val MAX_TEXT_SIZE = 64
private const val MIN_TEXT_SIZE = 30

enum class ShareTemplate(val label: String) {
    Page("Page"),
    Focus("Focus"),
    Quote("Quote"),
}

enum class ShareRatio(val label: String, val height: Int) {
    Square("1:1", 1080),
    Story("9:16", 1920),
}

class SharePalette(val start: Int, val end: Int)

val SHARE_PALETTES = listOf(
    SharePalette(0xFFE800A8.toInt(), 0xFFF5003F.toInt()),
    SharePalette(0xFFFF8A65.toInt(), 0xFFFF5252.toInt()),
    SharePalette(0xFF7C4DFF.toInt(), 0xFF2979FF.toInt()),
    SharePalette(0xFF00BFA5.toInt(), 0xFF0091EA.toInt()),
    SharePalette(0xFF37474F.toInt(), 0xFF101820.toInt()),
)

/** Draws [page] to fill the area, cut to fit like a cover. */
private fun Canvas.drawCover(page: Bitmap, width: Int, height: Int, paint: Paint) {
    val scale = max(width.toFloat() / page.width, height.toFloat() / page.height)
    val sourceWidth = (width / scale).toInt()
    val sourceHeight = (height / scale).toInt()
    val left = (page.width - sourceWidth) / 2
    val top = (page.height - sourceHeight) / 2
    drawBitmap(page, Rect(left, top, left + sourceWidth, top + sourceHeight), Rect(0, 0, width, height), paint)
}

/**
 * One box blur along each row of [source], written turned on its side so that a second call blurs
 * the columns. Each channel keeps a running total, so the radius doesn't affect the speed.
 */
private fun blurRows(source: IntArray, target: IntArray, width: Int, height: Int) {
    val window = BLUR_RADIUS * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -BLUR_RADIUS..BLUR_RADIUS) {
            val pixel = source[row + offset.coerceIn(0, width - 1)]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
        }
        for (x in 0 until width) {
            target[x * height + y] = (0xFF shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val leaving = source[row + (x - BLUR_RADIUS).coerceAtLeast(0)]
            val entering = source[row + (x + BLUR_RADIUS + 1).coerceAtMost(width - 1)]
            red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/** Shrinks the page in halves so text doesn't break into blocks, then blurs it properly. */
private fun blurred(page: Bitmap): Bitmap {
    var small = page
    while (small.width >= BLUR_WIDTH * 2) {
        small = Bitmap.createScaledBitmap(small, small.width / 2, max(small.height / 2, 1), true)
    }
    val width = small.width
    val height = small.height
    val pixels = IntArray(width * height)
    val turned = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(BLUR_PASSES) {
        blurRows(pixels, turned, width, height)
        blurRows(turned, pixels, height, width)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun tint(palette: SharePalette, width: Int, height: Int) = Paint().apply {
    shader = LinearGradient(
        0f,
        0f,
        width.toFloat(),
        height.toFloat(),
        palette.start,
        palette.end,
        Shader.TileMode.CLAMP,
    )
}

/** Sizes the text down until it fits, and cuts it off only if it's still too long at the smallest size. */
private fun fitText(text: String, typeface: Typeface, color: Int, width: Int, maxHeight: Int): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.color = color
    }
    var size = MAX_TEXT_SIZE
    while (true) {
        paint.textSize = size.toFloat()
        val lineHeight = paint.fontMetricsInt.let { it.bottom - it.top }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
        if (size <= MIN_TEXT_SIZE) {
            builder.setMaxLines((maxHeight / (lineHeight * 1.15f)).toInt().coerceAtLeast(1))
            builder.setEllipsize(TextUtils.TruncateAt.END)
        }
        val layout = builder.build()
        if (layout.height <= maxHeight || size <= MIN_TEXT_SIZE) return layout
        size -= 4
    }
}

private fun Canvas.drawCaption(caption: String, centerX: Float, top: Float) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 2f, 0x66000000)
    }
    val shown = TextUtils.ellipsize(caption, paint, CARD_WIDTH - 160f, TextUtils.TruncateAt.END).toString()
    drawText(shown, centerX, top - paint.ascent(), paint)
}

/** The page blurred under a color, with the passage sharp on a white card. */
private fun renderFocus(canvas: Canvas, height: Int, page: Bitmap, palette: SharePalette, text: String, caption: String) {
    canvas.drawCover(blurred(page), CARD_WIDTH, height, Paint(Paint.FILTER_BITMAP_FLAG))
    canvas.drawRect(
        0f,
        0f,
        CARD_WIDTH.toFloat(),
        height.toFloat(),
        tint(palette, CARD_WIDTH, height).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) },
    )

    val margin = 48
    val padding = 44
    val layout = fitText(text, Typeface.SERIF, Color.BLACK, CARD_WIDTH - (margin + padding) * 2, height - 420)
    val cardHeight = layout.height + padding * 2
    val cardTop = (height - cardHeight) / 2f
    val card = RectF(margin.toFloat(), cardTop, (CARD_WIDTH - margin).toFloat(), cardTop + cardHeight)
    canvas.drawRect(card, Paint().apply {
        color = Color.WHITE
        setShadowLayer(24f, 10f, 12f, 0x66000000)
    })
    canvas.save()
    canvas.translate((margin + padding).toFloat(), cardTop + padding)
    layout.draw(canvas)
    canvas.restore()
    canvas.drawCaption(caption, CARD_WIDTH / 2f, card.bottom + 32f)
}

/** A color wash with the page faintly behind it, and the quote on a soft rounded card. */
private fun renderQuote(canvas: Canvas, height: Int, page: Bitmap, palette: SharePalette, text: String, caption: String) {
    canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), height.toFloat(), tint(palette, CARD_WIDTH, height))
    canvas.drawCover(page, CARD_WIDTH, height, Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 70
        // Unlike the PorterDuff version, this leaves the card opaque where the page is see-through.
        blendMode = BlendMode.MULTIPLY
    })

    val margin = 96
    val padding = 80
    val layout = fitText(text, Typeface.SANS_SERIF, Color.BLACK, CARD_WIDTH - (margin + padding) * 2, height - 480)
    val cardHeight = layout.height + padding * 2
    val cardTop = (height - cardHeight) / 2f - 24f
    val card = RectF(margin.toFloat(), cardTop, (CARD_WIDTH - margin).toFloat(), cardTop + cardHeight)
    canvas.drawRoundRect(card, 72f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0FFFFFF.toInt() })
    canvas.drawRoundRect(card, 72f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    })
    canvas.save()
    canvas.translate((margin + padding).toFloat(), cardTop + padding)
    layout.draw(canvas)
    canvas.restore()
    canvas.drawCaption(caption, CARD_WIDTH / 2f, card.bottom + 36f)
}

fun renderShareCard(
    template: ShareTemplate,
    ratio: ShareRatio,
    palette: SharePalette,
    page: Bitmap,
    text: String,
    caption: String,
): Bitmap {
    if (template == ShareTemplate.Page) return page
    val bitmap = Bitmap.createBitmap(CARD_WIDTH, ratio.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (template == ShareTemplate.Focus) {
        renderFocus(canvas, ratio.height, page, palette, text, caption)
    } else {
        renderQuote(canvas, ratio.height, page, palette, text, caption)
    }
    return bitmap
}

/** Opens Android's share menu with the image. */
fun shareImage(context: Context, image: Bitmap) {
    val folder = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(folder, "quartz-reader.png")
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val address: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, address)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, address)
    context.startActivity(Intent.createChooser(send, "Share"))
}

/** Saves into Pictures, in a Quartz Reader album. Returns false if it couldn't. */
fun saveToGallery(context: Context, image: Bitmap): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "quartz-reader-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Quartz Reader")
    }
    val resolver = context.contentResolver
    val address = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(address)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
    }.getOrDefault(false)
}
