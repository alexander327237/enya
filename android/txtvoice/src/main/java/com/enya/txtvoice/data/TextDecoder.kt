package com.enya.txtvoice.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes raw bytes of a text file, guessing the encoding. Handles BOMs, strict UTF-8, and the two
 * legacy Cyrillic encodings that plain .txt books most often come in (Windows-1251 and KOI8-R).
 */
object TextDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // not UTF-8, try the legacy encodings below
        }
        val candidates = listOf("windows-1251", "KOI8-R", "ISO-8859-1")
            .filter { Charset.isSupported(it) }
            .map { Charset.forName(it) }
        return candidates
            .map { cs -> String(bytes, cs) }
            .maxByOrNull { score(it) }
            ?: String(bytes, Charsets.ISO_8859_1)
    }

    private const val FREQUENT_CYRILLIC = "оеаинтсрвлкмдпуя"
    private const val PUNCTUATION = ".,!?;:-—«»\"'()"

    /** Higher is more plausible: rewards frequent Cyrillic/Latin letters, penalises pseudo-graphics. */
    private fun score(text: String): Int {
        var score = 0
        val sample = if (text.length > 20000) text.substring(0, 20000) else text
        for (ch in sample) {
            when {
                ch in FREQUENT_CYRILLIC -> score += 3
                ch in 'а'..'я' || ch == 'ё' -> score += 1
                ch in 'А'..'Я' || ch == 'Ё' -> score += 0
                ch in 'a'..'z' -> score += 1
                ch == ' ' || ch == '\n' || ch == '\t' -> score += 1
                ch in PUNCTUATION -> score += 1
                ch.code in 0x2500..0x259F -> score -= 3 // box drawing / block elements
                ch.code in 0x80..0xBF -> score -= 3 // C1 controls and odd Latin-1 symbols
            }
        }
        return score
    }
}
