package com.tim.reader.theme

/** The built-in looks: Tim's own styles first, then the ones from his early mockups. */
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


    // Tim's own styles, exported from the app. Their art and fonts ship with it.

    val books: ShelfTheme = ThemeJson.read(
        """{"background":{"type":"image","name":"crystal","mode":"Crop","scale":1},"header":{"fill":{"type":"gradient","colors":[3019898879,4294967295],"direction":"Vertical"},"corner":32,"stroke":{"color":4294967295,"width":0.5},"shadow":{"color":4278190080,"angle":45,"distance":5.78,"blur":8,"opacity":1},"text":4283051910,"icon":4283051910},"title":{"font":"pack:NV Legible Next","size":34,"bold":true,"italic":false,"color":4283051910,"stroke":{"color":4294967295,"width":2}},"ledge":{"fill":{"type":"gradient","colors":[3717098712,3715079136],"direction":"Horizontal"},"corner":10,"height":18,"width":1,"align":"Center","stroke":{"color":3439329279,"width":1.5},"shadow":{"color":4278190080,"angle":90,"distance":8,"blur":10,"opacity":0.45}},"book":{"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5},"highlight":false,"spacing":16},"controls":{"color":4283051910,"fill":{"type":"solid","color":2583691263}},"bottom":{"fade":4287527888,"height":120,"label":4294967295},"button":{"fill":{"type":"gradient","colors":[3717098712,3715079136],"direction":"Horizontal"},"corner":18,"font":"pack:Libron","bold":true,"italic":false,"text":4294967295,"stroke":{"color":3439329279,"width":1.5}}}""",
    )

    val comics: ShelfTheme = ThemeJson.read(
        """{"background":{"type":"image","name":"comics","mode":"Crop","scale":1.0,"tint":3088410048},"header":{"fill":{"type":"gradient","colors":[4285143962,4278190080],"direction":"Vertical"},"corner":8.92,"stroke":{"color":4278190080,"width":1.42},"text":4294901502,"icon":4294704123},"title":{"font":"pack:Permanent Marker","size":36.14,"bold":false,"italic":false,"color":4294704123,"stroke":{"color":4278190080,"width":2},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5}},"ledge":{"fill":{"type":"gradient","colors":[4285143962,4278190080],"direction":"Vertical"},"corner":8.92,"height":26,"width":1,"align":"Center","stroke":{"color":4278190080,"width":1.42}},"book":{"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5},"highlight":true,"spacing":14.16},"controls":{"color":4294967295,"fill":{"type":"solid","color":4279592384},"textColor":4294967295},"bottom":{"fade":4278190080,"height":95.57,"label":4294967295},"button":{"fill":{"type":"image","name":"comics","mode":"Crop","scale":1,"tint":2976521114},"corner":32,"font":"sans","bold":true,"italic":true,"text":4294901502,"stroke":{"color":4278190080,"width":3.28},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5}}}""",
    )

    val horror: ShelfTheme = ThemeJson.read(
        """{"background":{"type":"image","name":"horror","mode":"Crop","scale":1},"header":{"fill":{"type":"solid","color":2820745771},"corner":0,"stroke":{"color":3093396349,"width":1},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5},"text":4284578685,"icon":4284578685},"title":{"font":"pack:NV Cardo","size":30,"bold":false,"italic":false,"color":4284578685,"stroke":{"color":4278190080,"width":2}},"ledge":{"fill":{"type":"solid","color":2820745771},"corner":0,"height":4.67,"width":1,"align":"Center","stroke":{"color":3093396349,"width":1},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5}},"book":{"shadow":{"color":4284578685,"angle":45,"distance":3.28,"blur":27.91,"opacity":1},"highlight":true,"spacing":16},"controls":{"color":4284578685,"fill":{"type":"gradient","colors":[4278190080,4281545523],"direction":"Vertical"}},"bottom":{"fade":4284578685,"height":155.83,"label":4294967295},"button":{"fill":{"type":"image","name":"horror","mode":"Crop","scale":1,"tint":2600468480},"corner":0,"font":"sans","bold":true,"italic":false,"text":4294967295,"stroke":{"color":4291176488,"width":2},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5}}}""",
    )

    val manga: ShelfTheme = ThemeJson.read(
        """{"background":{"type":"image","name":"manga","mode":"Crop","scale":1,"tint":3012332684},"header":{"fill":{"type":"image","name":"manga","mode":"Crop","scale":1,"tint":3238002688},"corner":32,"stroke":{"color":4278190080,"width":2},"text":4294967295,"icon":4294967295},"title":{"font":"pack:Bungee","size":30,"bold":true,"italic":false,"color":4278190080,"stroke":{"color":4289045925,"width":4}},"ledge":{"fill":{"type":"image","name":"manga","mode":"Crop","scale":1,"tint":3238002688},"corner":32,"height":22,"width":1,"align":"Center","stroke":{"color":4278190080,"width":2}},"book":{"shadow":{"color":4278190080,"angle":45,"distance":8,"blur":10,"opacity":0.7},"highlight":false,"spacing":16},"controls":{"color":4278190080,"stroke":{"color":4287401100,"width":2},"fill":{"type":"solid","color":4278190080},"textColor":4294967295},"bottom":{"fade":4278190080,"height":153.41,"label":4294967295},"button":{"fill":{"type":"image","name":"manga","mode":"Crop","scale":1,"tint":3238002688},"corner":32,"font":"display","bold":true,"italic":false,"text":4294967295,"stroke":{"color":4278190080,"width":2}}}""",
    )

    val manhwa: ShelfTheme = ThemeJson.read(
        """{"background":{"type":"image","name":"manhwa","mode":"Crop","scale":1,"tint":1712678336},"header":{"fill":{"type":"gradient","colors":[4278190080,4279592384],"direction":"Vertical"},"corner":40,"stroke":{"color":4279792860,"width":1},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":1},"text":4294967295,"icon":4294967295},"title":{"font":"pack:Caveat","size":40.21,"bold":false,"italic":false,"color":4294967295,"stroke":{"color":4278190080,"width":2},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":3.79,"opacity":0.89}},"ledge":{"fill":{"type":"gradient","colors":[4278190080,4279592384],"direction":"Vertical"},"corner":40,"height":9.86,"width":1,"align":"Center","stroke":{"color":4279792860,"width":1},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":1}},"book":{"shadow":{"color":4279793376,"angle":45,"distance":6,"blur":6.27,"opacity":0.98},"highlight":false,"spacing":16},"controls":{"color":4294967295,"textColor":4294967295},"bottom":{"fade":4278790994,"height":120,"label":4294967295},"button":{"fill":{"type":"gradient","colors":[4294440951,4290624957],"direction":"Vertical"},"corner":10,"font":"sans","bold":false,"italic":false,"text":4278190080}}""",
    )

    val romantasy: ShelfTheme = ThemeJson.read(
        """{"background":{"type":"image","name":"romantasy","mode":"Crop","scale":1},"header":{"fill":{"type":"gradient","colors":[4279501876,4282780427],"direction":"Vertical"},"corner":32,"stroke":{"color":4294179770,"width":0.68},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":1},"text":4294179770,"icon":4294179770},"title":{"font":"pack:Great Vibes","size":41.69,"bold":false,"italic":false,"color":4294179770,"stroke":{"color":4278190080,"width":2},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":0.5}},"ledge":{"fill":{"type":"gradient","colors":[4279501876,4282780427],"direction":"Vertical"},"corner":32,"height":26,"width":1,"align":"Center","stroke":{"color":4294179770,"width":0.68},"shadow":{"color":4278190080,"angle":45,"distance":6,"blur":8,"opacity":1}},"book":{"shadow":{"color":4294179770,"angle":106.42,"distance":2.8,"blur":13.58,"opacity":1},"highlight":true,"spacing":20.8},"controls":{"color":4281605642,"stroke":{"color":4294179770,"width":0.83},"textColor":4294179770},"bottom":{"fade":4278190080,"height":95.57,"label":4294179770},"button":{"fill":{"type":"image","name":"romantasy","mode":"Crop","scale":0.28},"corner":0,"font":"pack:Great Vibes","bold":false,"italic":false,"text":4294179770,"stroke":{"color":4294179770,"width":0.5},"shadow":{"color":4294179770,"angle":45,"distance":4.51,"blur":8,"opacity":0.93}}}""",
    )

    /** What Home and new shelves start with. */
    val default: ShelfTheme get() = books

    val all = listOf(
        "Books" to books,
        "Comics" to comics,
        "Manga" to manga,
        "Manhwa" to manhwa,
        "Horror" to horror,
        "Romantasy" to romantasy,
        "Silver Gloss" to silverGloss,
        "Brushed Metal" to brushedMetal,
        "Speed Lines" to speedLines,
        "Crystal" to crystal,
    )
}
