package com.tim.reader.theme

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle as CssStyle
import org.readium.r2.navigator.epub.css.FontWeight as CssWeight
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFamily

class BookFontFace(val file: String, val weight: Int, val italic: Boolean)

/** A font that ships with the app. Its name is also the name the reader's CSS uses. */
class BookFont(val name: String, val isCore: Boolean, val faces: List<BookFontFace>)

private const val FOLDER = "fonts"
private const val THEME_PREFIX = "pack:"

/** The reading fonts packaged with the app, for both the reader and shelf themes. */
object BookFonts {
    val all: List<BookFont> get() = BOOK_FONT_LIST

    private var assets: AssetManager? = null
    private val families = HashMap<String, FontFamily>()

    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    fun find(name: String) = all.firstOrNull { it.name == name }

    /** How a shelf theme refers to a packaged font. */
    fun themeName(font: BookFont) = THEME_PREFIX + font.name

    fun isThemeName(name: String) = name.startsWith(THEME_PREFIX)

    /** The font for Compose, from either a theme name or a reader font name. */
    @OptIn(ExperimentalTextApi::class)
    fun family(name: String): FontFamily? {
        val font = find(name.removePrefix(THEME_PREFIX)) ?: return null
        val manager = assets ?: return null
        return synchronized(families) {
            families.getOrPut(font.name) {
                FontFamily(
                    font.faces.map { face ->
                        Font(
                            path = "$FOLDER/${face.file}",
                            assetManager = manager,
                            weight = FontWeight(face.weight),
                            style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
                        )
                    },
                )
            }
        }
    }

    /** Lets the reader's pages use every packaged font by name. */
    @OptIn(ExperimentalReadiumApi::class)
    fun declareIn(configuration: EpubNavigatorFragment.Configuration) {
        all.forEach { font ->
            configuration.addFontFamilyDeclaration(ReadiumFamily(font.name)) {
                font.faces.forEach { face ->
                    addFontFace {
                        addSource("$FOLDER/${face.file}")
                        setFontStyle(if (face.italic) CssStyle.ITALIC else CssStyle.NORMAL)
                        setFontWeight(if (face.weight >= 600) CssWeight.BOLD else CssWeight.NORMAL)
                    }
                }
            }
        }
    }

    /** The files Readium has to serve for these fonts. */
    const val SERVED = "$FOLDER/.*"
}
