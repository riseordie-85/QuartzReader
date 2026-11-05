package com.tim.reader.theme

import org.json.JSONArray
import org.json.JSONObject

/** Themes are stored as JSON, ready for theme export and sync in later phases. */
object ThemeJson {
    fun write(theme: ShelfTheme): String = JSONObject().apply {
        put("background", fill(theme.background))
        put("header", JSONObject().apply {
            put("fill", fill(theme.header.fill))
            put("corner", theme.header.cornerDp.toDouble())
            putOpt("stroke", stroke(theme.header.stroke))
            putOpt("shadow", shadow(theme.header.shadow))
            put("text", theme.header.textColor)
            put("icon", theme.header.iconColor)
        })
        put("title", text(theme.title))
        put("ledge", JSONObject().apply {
            put("fill", fill(theme.ledge.fill))
            put("corner", theme.ledge.cornerDp.toDouble())
            put("height", theme.ledge.heightDp.toDouble())
            put("width", theme.ledge.widthFraction.toDouble())
            put("align", theme.ledge.align.name)
            putOpt("stroke", stroke(theme.ledge.stroke))
            putOpt("shadow", shadow(theme.ledge.shadow))
        })
        put("book", JSONObject().apply {
            putOpt("shadow", shadow(theme.book.shadow))
            put("highlight", theme.book.highlight)
            put("spacing", theme.book.spacingDp.toDouble())
        })
        put("controls", JSONObject().apply {
            put("color", theme.controls.color)
            putOpt("stroke", stroke(theme.controls.stroke))
        })
        put("bottom", JSONObject().apply {
            put("fade", theme.bottom.fadeColor)
            put("height", theme.bottom.heightDp.toDouble())
            put("label", theme.bottom.labelColor)
            theme.bottom.labelFill?.let { put("labelFill", fill(it)) }
        })
        put("button", JSONObject().apply {
            put("fill", fill(theme.button.fill))
            put("corner", theme.button.cornerDp.toDouble())
            put("font", theme.button.font)
            put("bold", theme.button.bold)
            put("italic", theme.button.italic)
            put("text", theme.button.textColor)
            putOpt("stroke", stroke(theme.button.stroke))
            putOpt("shadow", shadow(theme.button.shadow))
        })
    }.toString()

    /** A theme that can't be read falls back to Silver Gloss rather than breaking the shelf. */
    fun read(json: String): ShelfTheme = try {
        val root = JSONObject(json)
        val header = root.getJSONObject("header")
        val ledge = root.getJSONObject("ledge")
        val book = root.getJSONObject("book")
        val controls = root.getJSONObject("controls")
        val bottom = root.getJSONObject("bottom")
        val button = root.getJSONObject("button")
        ShelfTheme(
            background = fill(root.getJSONObject("background")),
            header = BarLook(
                fill = fill(header.getJSONObject("fill")),
                cornerDp = header.getDouble("corner").toFloat(),
                stroke = stroke(header.optJSONObject("stroke")),
                shadow = shadow(header.optJSONObject("shadow")),
                textColor = header.getLong("text"),
                iconColor = header.getLong("icon"),
            ),
            title = text(root.getJSONObject("title")),
            ledge = LedgeLook(
                fill = fill(ledge.getJSONObject("fill")),
                cornerDp = ledge.getDouble("corner").toFloat(),
                heightDp = ledge.getDouble("height").toFloat(),
                widthFraction = ledge.getDouble("width").toFloat(),
                align = enumValueOrNull<LedgeAlign>(ledge.optString("align")) ?: LedgeAlign.Center,
                stroke = stroke(ledge.optJSONObject("stroke")),
                shadow = shadow(ledge.optJSONObject("shadow")),
            ),
            book = BookLook(
                shadow = shadow(book.optJSONObject("shadow")),
                highlight = book.optBoolean("highlight"),
                spacingDp = book.getDouble("spacing").toFloat(),
            ),
            controls = ControlsLook(controls.getLong("color"), stroke(controls.optJSONObject("stroke"))),
            bottom = BottomLook(
                fadeColor = bottom.getLong("fade"),
                heightDp = bottom.getDouble("height").toFloat(),
                labelColor = bottom.getLong("label"),
                labelFill = bottom.optJSONObject("labelFill")?.let { fill(it) },
            ),
            button = ButtonLook(
                fill = fill(button.getJSONObject("fill")),
                cornerDp = button.getDouble("corner").toFloat(),
                font = button.getString("font"),
                bold = button.optBoolean("bold"),
                italic = button.optBoolean("italic"),
                textColor = button.getLong("text"),
                stroke = stroke(button.optJSONObject("stroke")),
                shadow = shadow(button.optJSONObject("shadow")),
            ),
        )
    } catch (_: Exception) {
        Presets.silverGloss
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private fun fill(value: Fill): JSONObject = JSONObject().apply {
        when (value) {
            is SolidFill -> {
                put("type", "solid")
                put("color", value.color)
            }
            is GradientFill -> {
                put("type", "gradient")
                put("colors", JSONArray(value.colors))
                put("direction", value.direction.name)
            }
            is ImageFill -> {
                put("type", "image")
                put("name", value.name)
                put("mode", value.mode.name)
                put("scale", value.tileScale.toDouble())
                value.tint?.let { put("tint", it) }
            }
        }
    }

    private fun fill(json: JSONObject): Fill = when (json.getString("type")) {
        "gradient" -> {
            val colors = json.getJSONArray("colors")
            GradientFill(
                colors = List(colors.length()) { colors.getLong(it) },
                direction = enumValueOrNull<GradientDirection>(json.optString("direction")) ?: GradientDirection.Vertical,
            )
        }
        "image" -> ImageFill(
            name = json.getString("name"),
            mode = enumValueOrNull<ImageMode>(json.optString("mode")) ?: ImageMode.Crop,
            tileScale = json.optDouble("scale", 1.0).toFloat(),
            tint = if (json.has("tint")) json.getLong("tint") else null,
        )
        else -> SolidFill(json.getLong("color"))
    }

    private fun stroke(value: StrokeLook?): JSONObject? = value?.let {
        JSONObject().put("color", it.color).put("width", it.widthDp.toDouble())
    }

    private fun stroke(json: JSONObject?): StrokeLook? =
        json?.let { StrokeLook(it.getLong("color"), it.getDouble("width").toFloat()) }

    private fun shadow(value: ShadowLook?): JSONObject? = value?.let {
        JSONObject()
            .put("color", it.color)
            .put("angle", it.angle.toDouble())
            .put("distance", it.distanceDp.toDouble())
            .put("blur", it.blurDp.toDouble())
            .put("opacity", it.opacity.toDouble())
    }

    private fun shadow(json: JSONObject?): ShadowLook? = json?.let {
        ShadowLook(
            color = it.getLong("color"),
            angle = it.getDouble("angle").toFloat(),
            distanceDp = it.getDouble("distance").toFloat(),
            blurDp = it.getDouble("blur").toFloat(),
            opacity = it.getDouble("opacity").toFloat(),
        )
    }

    private fun text(value: TextLook) = JSONObject().apply {
        put("font", value.font)
        put("size", value.sizeSp.toDouble())
        put("bold", value.bold)
        put("italic", value.italic)
        put("color", value.color)
        putOpt("stroke", stroke(value.stroke))
        putOpt("shadow", shadow(value.shadow))
    }

    private fun text(json: JSONObject) = TextLook(
        font = json.getString("font"),
        sizeSp = json.getDouble("size").toFloat(),
        bold = json.optBoolean("bold"),
        italic = json.optBoolean("italic"),
        color = json.getLong("color"),
        stroke = stroke(json.optJSONObject("stroke")),
        shadow = shadow(json.optJSONObject("shadow")),
    )
}
