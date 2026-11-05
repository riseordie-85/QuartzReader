package com.tim.reader.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.tim.reader.library.LibraryFolder
import java.io.File
import java.util.UUID

private const val FILE_PREFIX = "file:"

/**
 * Images and fonts brought in for themes. The app keeps its own copies, with a second set in the
 * library folder so that themes still work after a reinstall.
 */
object ThemeFiles {
    private var root: File? = null
    private var library: LibraryFolder? = null
    private val fontFamilies = HashMap<String, FontFamily>()

    fun init(context: Context) {
        root = File(context.filesDir, "theme-files").apply { mkdirs() }
        library = LibraryFolder.get(context)
    }

    private fun folder(name: String) = root?.let { File(it, name).apply { mkdirs() } }

    fun isImported(name: String) = name.startsWith(FILE_PREFIX)

    private fun nameOf(file: File) = FILE_PREFIX + file.name

    /** Finds a saved file, bringing it back from the library folder if the app's copy is gone. */
    private fun find(kind: String, name: String): File? {
        val file = File(folder(kind) ?: return null, name.removePrefix(FILE_PREFIX))
        if (!file.exists()) runCatching { library?.restoreThemeFile(file) }
        return file.takeIf { it.exists() }
    }

    fun imageFile(name: String) = find("images", name)

    fun images(): List<String> = folder("images")?.listFiles().orEmpty().sortedBy { it.lastModified() }.map(::nameOf)

    /** The saved font files, each with the name of the font it came from. */
    fun fonts(): List<String> = folder("fonts")?.listFiles().orEmpty().sortedBy { it.name }.map(::nameOf)

    fun fontLabel(name: String) = name.removePrefix(FILE_PREFIX).substringAfter('_').substringBeforeLast('.')

    private fun copyIn(context: Context, source: Uri, kind: String, displayName: String): File? {
        val target = File(folder(kind) ?: return null, "${UUID.randomUUID().toString().take(8)}_$displayName")
        val input = context.contentResolver.openInputStream(source) ?: return null
        try {
            input.use { reading -> target.outputStream().use { reading.copyTo(it) } }
        } catch (error: java.io.IOException) {
            target.delete()
            throw error
        }
        return target
    }

    private fun displayName(context: Context, source: Uri, fallback: String): String {
        val found = context.contentResolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
        return (found ?: fallback).replace(Regex("""[/\\:*?"<>|]"""), "_")
    }

    /** Copies an image in and returns its theme name, or null if it couldn't be read. */
    fun importImage(context: Context, source: Uri): String? {
        val file = copyIn(context, source, "images", displayName(context, source, "image.jpg")) ?: return null
        runCatching { library?.saveThemeFile(file) }
        return nameOf(file)
    }

    /** Copies a font in and returns its theme name, or null if Android can't use it. */
    fun importFont(context: Context, source: Uri): String? {
        val file = copyIn(context, source, "fonts", displayName(context, source, "font.ttf")) ?: return null
        if (runCatching { Typeface.Builder(file).build() }.getOrNull() == null) {
            file.delete()
            return null
        }
        runCatching { library?.saveThemeFile(file) }
        return nameOf(file)
    }

    fun fontFamily(name: String): FontFamily = synchronized(fontFamilies) {
        fontFamilies.getOrPut(name) {
            // A damaged font would crash text drawing, so it's checked before Compose sees it.
            find("fonts", name)
                ?.takeIf { runCatching { Typeface.Builder(it).build() }.getOrNull() != null }
                ?.let { FontFamily(Font(it)) }
                ?: FontFamily.SansSerif
        }
    }
}
