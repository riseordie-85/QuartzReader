package com.tim.reader.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.tim.reader.library.LibraryFolder
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val FILE_PREFIX = "file:"
private const val IMAGES = "images"
private const val FONTS = "fonts"
private const val STYLE_ENTRY = "style.json"

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

    fun imageFile(name: String) = find(IMAGES, name)

    fun images(): List<String> = folder(IMAGES)?.listFiles().orEmpty().sortedBy { it.lastModified() }.map(::nameOf)

    /** The saved font files, each with the name of the font it came from. */
    fun fonts(): List<String> = folder(FONTS)?.listFiles().orEmpty().sortedBy { it.name }.map(::nameOf)

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
        val file = copyIn(context, source, IMAGES, displayName(context, source, "image.jpg")) ?: return null
        runCatching { library?.saveThemeFile(file) }
        return nameOf(file)
    }

    /** Copies a font in and returns its theme name, or null if Android can't use it. */
    fun importFont(context: Context, source: Uri): String? {
        val file = copyIn(context, source, FONTS, displayName(context, source, "font.ttf")) ?: return null
        if (runCatching { Typeface.Builder(file).build() }.getOrNull() == null) {
            file.delete()
            return null
        }
        runCatching { library?.saveThemeFile(file) }
        return nameOf(file)
    }

    /** Every imported image and font a theme uses, as (folder, theme name). */
    private fun filesIn(theme: ShelfTheme): Set<Pair<String, String>> {
        val fills = listOfNotNull(
            theme.background,
            theme.header.fill,
            theme.ledge.fill,
            theme.controls.fill,
            theme.bottom.labelFill,
            theme.button.fill,
        )
        val images = fills.mapNotNull { (it as? ImageFill)?.name }.map { IMAGES to it }
        val fonts = listOf(theme.title.font, theme.button.font).map { FONTS to it }
        return (images + fonts).filter { isImported(it.second) }.toSet()
    }

    /** Writes a style as one zip: the style itself, and the imported images and fonts it uses. */
    fun exportStyle(theme: ShelfTheme, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(STYLE_ENTRY))
            zip.write(ThemeJson.write(theme).toByteArray())
            zip.closeEntry()
            filesIn(theme).forEach { (kind, name) ->
                val file = find(kind, name) ?: return@forEach
                zip.putNextEntry(ZipEntry("$kind/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a style from a zip or a plain style file, copying in any images and fonts it carries.
     * Returns the style's text, or null if there isn't one.
     */
    fun importStyle(input: InputStream): String? {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val isZip = buffered.read() == 'P'.code && buffered.read() == 'K'.code
        buffered.reset()
        if (!isZip) return buffered.readBytes().decodeToString()

        var style: String? = null
        ZipInputStream(buffered).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val kind = entry.name.substringBefore('/', "")
                // Only the file's own name is used, so an entry can't write outside these folders.
                val fileName = entry.name.substringAfterLast('/')
                when {
                    entry.name == STYLE_ENTRY -> style = zip.readBytes().decodeToString()
                    (kind == IMAGES || kind == FONTS) && fileName.isNotEmpty() -> keepImported(kind, fileName, zip)
                }
                zip.closeEntry()
            }
        }
        return style
    }

    private fun keepImported(kind: String, fileName: String, source: InputStream) {
        val target = File(folder(kind) ?: return, fileName)
        if (target.exists()) return
        val partial = File(target.path + ".tmp")
        partial.outputStream().use { source.copyTo(it) }
        if (kind == FONTS && runCatching { Typeface.Builder(partial).build() }.getOrNull() == null) {
            partial.delete()
            return
        }
        if (partial.renameTo(target)) runCatching { library?.saveThemeFile(target) }
    }

    fun fontFamily(name: String): FontFamily = synchronized(fontFamilies) {
        fontFamilies.getOrPut(name) {
            // A damaged font would crash text drawing, so it's checked before Compose sees it.
            find(FONTS, name)
                ?.takeIf { runCatching { Typeface.Builder(it).build() }.getOrNull() != null }
                ?.let { FontFamily(Font(it)) }
                ?: FontFamily.SansSerif
        }
    }
}
