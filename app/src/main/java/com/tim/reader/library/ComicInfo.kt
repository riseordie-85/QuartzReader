package com.tim.reader.library

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

data class ComicInfo(
    val title: String?,
    val series: String?,
    val number: Double?,
    val writer: String?,
    val rightToLeft: Boolean,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val year: String? = null,
)

/** Reads the ComicInfo.xml that comic tools embed in archives. Returns null if it can't be read. */
fun parseComicInfo(bytes: ByteArray): ComicInfo? = try {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(bytes), null)
    val fields = HashMap<String, String>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.depth == 2) {
            val name = parser.name
            if (parser.next() == XmlPullParser.TEXT) fields[name] = parser.text.trim()
        }
        event = parser.next()
    }
    ComicInfo(
        title = fields["Title"]?.takeIf { it.isNotBlank() },
        series = fields["Series"]?.takeIf { it.isNotBlank() },
        number = (fields["Number"] ?: fields["Volume"])?.toDoubleOrNull(),
        writer = fields["Writer"]?.takeIf { it.isNotBlank() },
        rightToLeft = fields["Manga"].equals("YesAndRightToLeft", ignoreCase = true),
        summary = fields["Summary"]?.takeIf { it.isNotBlank() },
        genres = fields["Genre"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        publisher = fields["Publisher"]?.takeIf { it.isNotBlank() },
        year = fields["Year"]?.takeIf { it.isNotBlank() },
    )
} catch (_: Exception) {
    null
}
