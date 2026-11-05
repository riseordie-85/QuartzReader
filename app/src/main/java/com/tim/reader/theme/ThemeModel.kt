package com.tim.reader.theme

/** Colors are stored as 0xAARRGGBB. */
enum class GradientDirection { Horizontal, Vertical, Diagonal }

enum class ImageMode { Crop, Tile }

enum class LedgeAlign { Left, Center, Right }

sealed interface Fill

data class SolidFill(val color: Long) : Fill

data class GradientFill(val colors: List<Long>, val direction: GradientDirection = GradientDirection.Vertical) : Fill

/** A wallpaper or texture. The tint is drawn over the image, so one texture can suit several parts. */
data class ImageFill(
    val name: String,
    val mode: ImageMode = ImageMode.Crop,
    val tileScale: Float = 1f,
    val tint: Long? = null,
) : Fill

data class StrokeLook(val color: Long, val widthDp: Float)

data class ShadowLook(
    val color: Long = 0xFF000000,
    val angle: Float = 45f,
    val distanceDp: Float = 6f,
    val blurDp: Float = 8f,
    val opacity: Float = 0.5f,
)

data class TextLook(
    val font: String = "sans",
    val sizeSp: Float = 30f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Long = 0xFF000000,
    val stroke: StrokeLook? = null,
    val shadow: ShadowLook? = null,
)

data class BarLook(
    val fill: Fill,
    val cornerDp: Float,
    val stroke: StrokeLook? = null,
    val shadow: ShadowLook? = null,
    val textColor: Long,
    val iconColor: Long,
)

data class LedgeLook(
    val fill: Fill,
    val cornerDp: Float,
    val heightDp: Float = 22f,
    /** 1 spans the full width. A narrower ledge lines up with [align]. */
    val widthFraction: Float = 1f,
    val align: LedgeAlign = LedgeAlign.Center,
    val stroke: StrokeLook? = null,
    val shadow: ShadowLook? = ShadowLook(angle = 90f, distanceDp = 8f, blurDp = 10f, opacity = 0.45f),
)

data class BookLook(
    val shadow: ShadowLook? = ShadowLook(),
    val highlight: Boolean = false,
    val spacingDp: Float = 16f,
)

data class ControlsLook(val color: Long, val stroke: StrokeLook? = null)

data class BottomLook(
    val fadeColor: Long,
    val heightDp: Float = 120f,
    val labelColor: Long,
    val labelFill: Fill? = null,
)

data class ButtonLook(
    val fill: Fill,
    val cornerDp: Float = 10f,
    val font: String = "sans",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val textColor: Long,
    val stroke: StrokeLook? = null,
    val shadow: ShadowLook? = null,
)

data class ShelfTheme(
    val background: Fill,
    val header: BarLook,
    val title: TextLook,
    val ledge: LedgeLook,
    val book: BookLook = BookLook(),
    val controls: ControlsLook,
    val bottom: BottomLook,
    val button: ButtonLook,
)
