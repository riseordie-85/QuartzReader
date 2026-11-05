package com.tim.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

enum class Glyph { Plus, Book, Gear, Grid }

private fun DrawScope.drawPlus(color: Color) {
    val width = size.minDimension * 0.14f
    val middle = size.minDimension / 2
    val inset = size.minDimension * 0.12f
    drawLine(color, Offset(middle, inset), Offset(middle, size.height - inset), width, StrokeCap.Round)
    drawLine(color, Offset(inset, middle), Offset(size.width - inset, middle), width, StrokeCap.Round)
}

private fun DrawScope.drawBook(color: Color) {
    val unit = size.minDimension
    drawRoundRect(
        color,
        Offset(unit * 0.16f, unit * 0.06f),
        Size(unit * 0.68f, unit * 0.88f),
        CornerRadius(unit * 0.1f),
    )
    drawLine(
        Color.Black.copy(alpha = 0.25f),
        Offset(unit * 0.34f, unit * 0.1f),
        Offset(unit * 0.34f, unit * 0.9f),
        unit * 0.05f,
    )
}

private fun DrawScope.drawGear(color: Color) {
    val unit = size.minDimension
    val middle = Offset(size.width / 2, size.height / 2)
    repeat(8) { index ->
        rotate(index * 45f, middle) {
            drawLine(
                color,
                Offset(middle.x, unit * 0.04f),
                Offset(middle.x, unit * 0.22f),
                unit * 0.15f,
            )
        }
    }
    drawCircle(color, unit * 0.27f, middle, style = Stroke(unit * 0.15f))
}

private fun DrawScope.drawGrid(color: Color) {
    val unit = size.minDimension
    val cell = unit * 0.38f
    val far = unit * 0.54f
    val near = unit * 0.08f
    listOf(Offset(near, near), Offset(far, near), Offset(near, far), Offset(far, far)).forEach {
        drawRect(color, it, Size(cell, cell))
    }
}

@Composable
fun GlyphButton(glyph: Glyph, color: Color, description: String, onClick: () -> Unit) {
    Canvas(
        Modifier
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(8.dp)
            .size(26.dp),
    ) {
        when (glyph) {
            Glyph.Plus -> drawPlus(color)
            Glyph.Book -> drawBook(color)
            Glyph.Gear -> drawGear(color)
            Glyph.Grid -> drawGrid(color)
        }
    }
}
