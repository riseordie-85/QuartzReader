package com.tim.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tim.reader.theme.StrokeLook

enum class Glyph { Plus, Book, Gear, Grid, List, Bookmark, BookmarkOutline, Search, Notes, Share }

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

private fun DrawScope.drawGrid(color: Color, outline: StrokeLook?) {
    val unit = size.minDimension
    val cell = unit * 0.38f
    val far = unit * 0.54f
    val near = unit * 0.08f
    listOf(Offset(near, near), Offset(far, near), Offset(near, far), Offset(far, far)).forEach {
        drawRect(color, it, Size(cell, cell))
        if (outline != null) {
            drawRect(Color(outline.color), it, Size(cell, cell), style = Stroke(outline.widthDp.dp.toPx()))
        }
    }
}

private fun DrawScope.drawList(color: Color) {
    val unit = size.minDimension
    listOf(0.2f, 0.5f, 0.8f).forEach { height ->
        drawCircle(color, unit * 0.07f, Offset(unit * 0.12f, unit * height))
        drawLine(color, Offset(unit * 0.32f, unit * height), Offset(unit * 0.94f, unit * height), unit * 0.11f, StrokeCap.Round)
    }
}

private fun DrawScope.drawSearch(color: Color) {
    val unit = size.minDimension
    drawCircle(color, unit * 0.3f, Offset(unit * 0.42f, unit * 0.42f), style = Stroke(unit * 0.11f))
    drawLine(color, Offset(unit * 0.64f, unit * 0.64f), Offset(unit * 0.92f, unit * 0.92f), unit * 0.13f, StrokeCap.Round)
}

private fun DrawScope.drawNotes(color: Color) {
    val unit = size.minDimension
    val line = unit * 0.09f
    drawRoundRect(
        color,
        Offset(unit * 0.14f, unit * 0.1f),
        Size(unit * 0.72f, unit * 0.84f),
        CornerRadius(unit * 0.08f),
        style = Stroke(line),
    )
    listOf(0.34f, 0.62f).forEach { x ->
        drawLine(color, Offset(unit * x, unit * 0.02f), Offset(unit * x, unit * 0.2f), line, StrokeCap.Round)
    }
    listOf(0.42f, 0.58f, 0.74f).forEach { y ->
        drawLine(color, Offset(unit * 0.3f, unit * y), Offset(unit * 0.7f, unit * y), line * 0.8f, StrokeCap.Round)
    }
}

private fun DrawScope.drawShare(color: Color) {
    val unit = size.minDimension
    val top = Offset(unit * 0.76f, unit * 0.18f)
    val left = Offset(unit * 0.22f, unit * 0.5f)
    val bottom = Offset(unit * 0.76f, unit * 0.82f)
    drawLine(color, left, top, unit * 0.08f)
    drawLine(color, left, bottom, unit * 0.08f)
    listOf(top, left, bottom).forEach { drawCircle(color, unit * 0.15f, it) }
}

private fun DrawScope.ribbon(): Path {
    val unit = size.minDimension
    return Path().apply {
        moveTo(unit * 0.2f, unit * 0.06f)
        lineTo(unit * 0.8f, unit * 0.06f)
        lineTo(unit * 0.8f, unit * 0.94f)
        lineTo(unit * 0.5f, unit * 0.72f)
        lineTo(unit * 0.2f, unit * 0.94f)
        close()
    }
}

@Composable
fun GlyphButton(glyph: Glyph, color: Color, description: String, onClick: () -> Unit) {
    GlyphButton(glyph, color, description, iconSize = 26.dp, onClick = onClick)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlyphButton(
    glyph: Glyph,
    color: Color,
    description: String,
    iconSize: Dp,
    onLongClick: (() -> Unit)? = null,
    outline: StrokeLook? = null,
    onClick: () -> Unit,
) {
    Canvas(
        Modifier
            .combinedClickable(onClickLabel = description, onLongClick = onLongClick, onClick = onClick)
            .padding(8.dp)
            .size(iconSize),
    ) {
        when (glyph) {
            Glyph.Plus -> drawPlus(color)
            Glyph.Book -> drawBook(color)
            Glyph.Gear -> drawGear(color)
            Glyph.Grid -> drawGrid(color, outline)
            Glyph.List -> drawList(color)
            Glyph.Search -> drawSearch(color)
            Glyph.Notes -> drawNotes(color)
            Glyph.Share -> drawShare(color)
            Glyph.Bookmark -> drawPath(ribbon(), color)
            Glyph.BookmarkOutline -> drawPath(ribbon(), color, style = Stroke(size.minDimension * 0.09f))
        }
    }
}
