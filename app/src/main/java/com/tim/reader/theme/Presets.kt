package com.tim.reader.theme

/** The built-in looks, from Tim's mockups. Wood and Burst use placeholder art until his own arrives. */
object Presets {
    val silverGloss = ShelfTheme(
        background = GradientFill(listOf(0xFFB2B2B2, 0xFFD6D6D6)),
        header = BarLook(
            fill = GradientFill(listOf(0xFFF7F7F7, 0xFFA8A8A8)),
            cornerDp = 32f,
            stroke = StrokeLook(0x33000000, 1f),
            textColor = 0xFF000000,
            iconColor = 0xFF000000,
        ),
        title = TextLook(color = 0xFF000000),
        ledge = LedgeLook(fill = GradientFill(listOf(0xFFFAFAFA, 0xFFB5B5B5)), cornerDp = 14f, heightDp = 26f),
        controls = ControlsLook(0xFF000000),
        bottom = BottomLook(fadeColor = 0xFFD6D6D6, labelColor = 0xFF000000),
        button = ButtonLook(fill = GradientFill(listOf(0xFFF7F7F7, 0xFFBDBDBD)), textColor = 0xFF000000),
    )

    val brushedMetal = ShelfTheme(
        background = SolidFill(0xFFADADAD),
        header = BarLook(
            fill = GradientFill(
                listOf(0xFF9C9C9C, 0xFFC8C8C8, 0xFFA2A2A2, 0xFFC4C4C4, 0xFF9C9C9C),
                GradientDirection.Horizontal,
            ),
            cornerDp = 4f,
            stroke = StrokeLook(0x33FFFFFF, 1f),
            textColor = 0xFF000000,
            iconColor = 0xFF222222,
        ),
        title = TextLook(color = 0xFF000000),
        ledge = LedgeLook(
            fill = GradientFill(
                listOf(0xFF9C9C9C, 0xFFC8C8C8, 0xFFA2A2A2, 0xFFC4C4C4, 0xFF9C9C9C),
                GradientDirection.Horizontal,
            ),
            cornerDp = 2f,
        ),
        controls = ControlsLook(0xFF000000),
        bottom = BottomLook(fadeColor = 0xFFADADAD, labelColor = 0xFF000000),
        button = ButtonLook(
            fill = GradientFill(listOf(0xFF9C9C9C, 0xFFC8C8C8, 0xFF9C9C9C), GradientDirection.Horizontal),
            cornerDp = 4f,
            textColor = 0xFF000000,
        ),
    )

    val wood = ShelfTheme(
        background = ImageFill("wood"),
        header = BarLook(
            fill = ImageFill("wood", ImageMode.Tile, tint = 0x665A3216),
            cornerDp = 4f,
            textColor = 0xFFF2DFC0,
            iconColor = 0xFFF2DFC0,
        ),
        title = TextLook(color = 0xFFF2DFC0),
        ledge = LedgeLook(fill = ImageFill("wood", ImageMode.Tile, tint = 0x665A3216), cornerDp = 2f),
        controls = ControlsLook(0xFFF2DFC0),
        bottom = BottomLook(fadeColor = 0xFF6B3F22, labelColor = 0xFFF2DFC0),
        button = ButtonLook(
            fill = ImageFill("wood", ImageMode.Tile, tint = 0x665A3216),
            cornerDp = 8f,
            font = "serif",
            textColor = 0xFFF2DFC0,
        ),
    )

    private val purpleBar = GradientFill(
        listOf(0xFF8F44BF, 0xFFB86BD8, 0xFF8F44BF),
        GradientDirection.Diagonal,
    )

    val pinkPattern = ShelfTheme(
        background = ImageFill("kawaii"),
        header = BarLook(
            fill = purpleBar,
            cornerDp = 32f,
            stroke = StrokeLook(0x66FFFFFF, 1f),
            textColor = 0xFFF7E9C8,
            iconColor = 0xFFF7E9C8,
        ),
        title = TextLook(
            font = "cursive",
            sizeSp = 34f,
            bold = true,
            color = 0xFFF7D9F5,
            stroke = StrokeLook(0xFF8F44BF, 3f),
        ),
        ledge = LedgeLook(fill = purpleBar, cornerDp = 14f),
        controls = ControlsLook(0xFF8F44BF),
        bottom = BottomLook(fadeColor = 0xFFA959CC, labelColor = 0xFFF7D9F5),
        button = ButtonLook(fill = purpleBar, font = "cursive", textColor = 0xFFF7E9C8),
    )

    val burst = pinkPattern.copy(
        background = ImageFill("burst"),
        title = pinkPattern.title.copy(font = "sans", bold = true),
        bottom = pinkPattern.bottom.copy(fadeColor = 0xFF8F44BF),
    )

    private val royalBlue = 0xFF0000F0

    val speedLines = ShelfTheme(
        background = ImageFill("speed"),
        header = BarLook(
            fill = SolidFill(royalBlue),
            cornerDp = 32f,
            textColor = 0xFFFFFFFF,
            iconColor = 0xFFFFFFFF,
        ),
        title = TextLook(
            font = "display",
            bold = true,
            color = 0xFFFFFFFF,
            stroke = StrokeLook(0xFF000000, 4f),
        ),
        ledge = LedgeLook(fill = SolidFill(royalBlue), cornerDp = 14f),
        book = BookLook(shadow = ShadowLook(distanceDp = 8f, blurDp = 10f, opacity = 0.7f)),
        controls = ControlsLook(royalBlue),
        bottom = BottomLook(fadeColor = royalBlue, labelColor = 0xFFFFFFFF),
        button = ButtonLook(
            fill = GradientFill(listOf(0xFF3F52FF, 0xFF0014BF)),
            font = "display",
            bold = true,
            textColor = 0xFFFFFFFF,
        ),
    )

    private val amethyst = 0xFF4A2F86
    private val crystalBar = GradientFill(listOf(0xDD8E6CD8, 0xDD6F9BE0), GradientDirection.Horizontal)

    /** Tim's crystal art, to match the app icon. */
    val crystal = ShelfTheme(
        background = ImageFill("crystal"),
        header = BarLook(
            fill = SolidFill(0xB3FFFFFF),
            cornerDp = 32f,
            stroke = StrokeLook(0xCCFFFFFF, 1.5f),
            textColor = amethyst,
            iconColor = amethyst,
        ),
        title = TextLook(
            font = "pack:Libron",
            sizeSp = 34f,
            bold = true,
            color = amethyst,
            stroke = StrokeLook(0xFFFFFFFF, 2f),
        ),
        ledge = LedgeLook(fill = crystalBar, cornerDp = 10f, heightDp = 18f, stroke = StrokeLook(0xCCFFFFFF, 1.5f)),
        controls = ControlsLook(amethyst, fill = SolidFill(0x99FFFFFF)),
        bottom = BottomLook(fadeColor = 0xFF8E7BD0, labelColor = 0xFFFFFFFF),
        button = ButtonLook(
            fill = crystalBar,
            cornerDp = 18f,
            font = "pack:Libron",
            bold = true,
            textColor = 0xFFFFFFFF,
            stroke = StrokeLook(0xCCFFFFFF, 1.5f),
        ),
    )

    val all = listOf(
        "Silver Gloss" to silverGloss,
        "Brushed Metal" to brushedMetal,
        "Wood" to wood,
        "Pink Pattern" to pinkPattern,
        "Burst" to burst,
        "Speed Lines" to speedLines,
        "Crystal" to crystal,
    )
}
