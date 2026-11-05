package com.tim.reader.theme

import android.graphics.Matrix
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

fun fontFamily(name: String): FontFamily = when (name) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    "sans" -> FontFamily.SansSerif
    else -> if (ThemeFiles.isImported(name)) ThemeFiles.fontFamily(name) else FontFamily.SansSerif
}

private fun Fill.imageName() = (this as? ImageFill)?.name

/** Repeats an image at the chosen size. */
private fun tileBrush(image: ImageBitmap, scale: Float) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        ImageShader(image, TileMode.Repeated, TileMode.Repeated).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        }
}

/** Fills a shape with an image, cut to fit and centered. */
private fun coverBrush(image: ImageBitmap) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val scale = maxOf(size.width / image.width, size.height / image.height)
        return ImageShader(image, TileMode.Clamp, TileMode.Clamp).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((size.width - image.width * scale) / 2, (size.height - image.height * scale) / 2)
                },
            )
        }
    }
}

private fun fillBrush(fill: Fill, image: ImageBitmap?): Brush? = when (fill) {
    is SolidFill -> SolidColor(Color(fill.color))
    is GradientFill -> {
        val colors = fill.colors.map { Color(it) }
        when {
            colors.size < 2 -> SolidColor(colors.firstOrNull() ?: Color.Transparent)
            fill.direction == GradientDirection.Horizontal -> Brush.horizontalGradient(colors)
            fill.direction == GradientDirection.Vertical -> Brush.verticalGradient(colors)
            else -> Brush.linearGradient(colors)
        }
    }
    is ImageFill -> image?.let { if (fill.mode == ImageMode.Tile) tileBrush(it, fill.tileScale) else coverBrush(it) }
}

/** Paints a fill behind content, in the given shape. */
@Composable
fun Modifier.themedFill(fill: Fill, shape: Shape): Modifier {
    val image by rememberThemeImage(fill.imageName())
    val brush = remember(fill, image) { fillBrush(fill, image) }
    val tint = (fill as? ImageFill)?.tint
    var result = this
    if (brush != null) result = result.background(brush, shape)
    if (tint != null) result = result.background(Color(tint), shape)
    return result
}

fun Modifier.themedStroke(stroke: StrokeLook?, shape: Shape): Modifier =
    if (stroke == null) this else border(BorderStroke(stroke.widthDp.dp, Color(stroke.color)), shape)

fun Modifier.themedShadow(look: ShadowLook?, shape: Shape): Modifier =
    if (look == null) {
        this
    } else {
        val color = Color(look.color).copy(alpha = look.opacity)
        this.shadow(look.distanceDp.dp, shape, clip = false, ambientColor = color, spotColor = color)
    }

fun cornerShape(cornerDp: Float) = RoundedCornerShape(cornerDp.dp)

/** A full page background: a color, gradient, cropped wallpaper, or tile. */
@Composable
fun ThemedBackground(fill: Fill, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val crop = (fill as? ImageFill)?.takeIf { it.mode == ImageMode.Crop }
    val image by rememberThemeImage(crop?.name)
    val base = if (crop == null) Modifier.themedFill(fill, RoundedCornerShape(0.dp)) else Modifier
    Box(modifier.then(base)) {
        val picture = image
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            crop?.tint?.let { Box(Modifier.fillMaxSize().background(Color(it))) }
        }
        content()
    }
}

/** Text with an optional outline and shadow, as the mockups use for shelf titles. */
@Composable
fun ThemedText(text: String, look: TextLook, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val shadow = look.shadow
    val style = TextStyle(
        fontFamily = fontFamily(look.font),
        fontSize = look.sizeSp.sp,
        fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (look.italic) FontStyle.Italic else FontStyle.Normal,
        shadow = shadow?.let {
            val radians = Math.toRadians(it.angle.toDouble())
            val distance = with(density) { it.distanceDp.dp.toPx() }
            Shadow(
                color = Color(it.color).copy(alpha = it.opacity),
                offset = Offset((cos(radians) * distance).toFloat(), (sin(radians) * distance).toFloat()),
                blurRadius = with(density) { it.blurDp.dp.toPx() },
            )
        },
    )
    Box(modifier) {
        val stroke = look.stroke
        if (stroke != null) {
            Text(
                text = text,
                color = Color(stroke.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = style.copy(
                    shadow = null,
                    drawStyle = Stroke(width = with(density) { stroke.widthDp.dp.toPx() * 2 }),
                ),
            )
        }
        Text(text = text, color = Color(look.color), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
    }
}
