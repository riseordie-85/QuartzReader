package com.tim.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Five stars with halves. Tapping the left half of a star gives a half star. Without [onChange],
 * the stars only show a rating.
 */
@Composable
fun StarRating(stars: Float, color: Color, starSize: Dp = 34.dp, onChange: ((Float) -> Unit)? = null) {
    val described = Modifier.semantics { contentDescription = "${formatStars(stars)} of 5 stars" }
    val tappable = if (onChange == null) {
        described
    } else {
        described.pointerInput(onChange) {
            detectTapGestures { offset ->
                val value = ceil(offset.x / size.width * 10f) / 2f
                onChange(value.coerceIn(0.5f, 5f))
            }
        }
    }
    Row(tappable) {
        for (index in 0 until 5) {
            val fill = (stars - index).coerceIn(0f, 1f)
            Canvas(Modifier.size(starSize)) {
                val path = starPath(size.width, size.height)
                drawPath(path, color, style = Stroke(width = size.width * 0.06f))
                if (fill > 0f) {
                    clipRect(right = size.width * fill) { drawPath(path, color) }
                }
            }
        }
    }
}

fun formatStars(stars: Float) = if (stars % 1f == 0f) stars.toInt().toString() else stars.toString()

private fun starPath(width: Float, height: Float): Path {
    val cx = width / 2
    val cy = height / 2 + height * 0.04f
    val outer = minOf(width, height) * 0.46f
    val inner = outer * 0.45f
    return Path().apply {
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + point * 36.0)
            val at = Offset(cx + (radius * cos(angle)).toFloat(), cy + (radius * sin(angle)).toFloat())
            if (point == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
        }
        close()
    }
}
