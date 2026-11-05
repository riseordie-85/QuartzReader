package com.tim.reader.engine

/** What a file really is, from its contents. Extensions on comics are often wrong. */
enum class Kind(val label: String) {
    ZIP("ZIP"),
    EPUB("EPUB"),
    RAR4("RAR4"),
    RAR5("RAR5"),
    SEVEN_ZIP("7z"),
    PDF("PDF"),
    MOBI("MOBI or AZW"),
    UNKNOWN("Unknown"),
}

private fun ByteArray.startsWith(vararg signature: Int) =
    size >= signature.size && signature.indices.all { (this[it].toInt() and 0xFF) == signature[it] }

private fun ByteArray.asciiAt(offset: Int, text: String) =
    size >= offset + text.length && text.indices.all { this[offset + it].toInt() == text[it].code }

fun sniff(head: ByteArray, fileName: String): Kind = when {
    head.startsWith(0x50, 0x4B) ->
        if (head.asciiAt(30, "mimetypeapplication/epub+zip") || fileName.endsWith(".epub", ignoreCase = true)) {
            Kind.EPUB
        } else {
            Kind.ZIP
        }
    head.startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) -> Kind.RAR5
    head.startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) -> Kind.RAR4
    head.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVEN_ZIP
    head.asciiAt(0, "%PDF") -> Kind.PDF
    head.asciiAt(60, "BOOKMOBI") -> Kind.MOBI
    else -> Kind.UNKNOWN
}

/** Reads the header fields that decide whether a MOBI can be converted. */
fun describeMobi(file: ByteArray): String {
    fun u16(at: Int) = ((file[at].toInt() and 0xFF) shl 8) or (file[at + 1].toInt() and 0xFF)
    fun u32(at: Int) = (u16(at) shl 16) or u16(at + 2)

    val record0 = u32(78)
    val compression = when (val value = u16(record0)) {
        1 -> "none"
        2 -> "PalmDOC"
        17480 -> "Huffman"
        else -> "type $value"
    }
    val encryption = when (val value = u16(record0 + 12)) {
        0 -> "none"
        1, 2 -> "DRM"
        else -> "type $value"
    }
    return "MOBI file version ${u32(record0 + 36)}, compression $compression, encryption $encryption"
}
