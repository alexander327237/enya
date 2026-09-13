package com.enya.txtvoice.tts

/** A chunk of the book that is spoken and highlighted as one unit. Offsets index into the original text. */
data class Segment(
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val paragraphStart: Boolean
)

object Segmenter {

    private val sentenceBoundary = Regex("""(?<=[.!?…]["»)'”]?)\s+""")

    /** Splits [text] into paragraphs, then sentences, then merges sentences into segments of at most [maxChars]. */
    fun split(text: String, maxChars: Int): List<Segment> {
        val result = ArrayList<Segment>()
        var pos = 0
        val length = text.length
        while (pos < length) {
            val nl = text.indexOf('\n', pos).let { if (it == -1) length else it }
            val paraStart = pos
            val paraEnd = nl
            pos = nl + 1
            if (text.subSequence(paraStart, paraEnd).isBlank()) continue

            var first = true
            var segStart = -1
            var segEnd = -1
            fun flush() {
                if (segStart >= 0) {
                    val body = text.substring(segStart, segEnd).trim()
                    if (body.isNotEmpty()) {
                        result += Segment(result.size, body, segStart, segEnd, first)
                        first = false
                    }
                    segStart = -1
                }
            }
            for (sentence in sentencesWithOffsets(text, paraStart, paraEnd)) {
                for (chunk in hardSplit(text, sentence.first, sentence.second, maxChars)) {
                    when {
                        segStart < 0 -> { segStart = chunk.first; segEnd = chunk.second }
                        chunk.second - segStart <= maxChars -> segEnd = chunk.second
                        else -> { flush(); segStart = chunk.first; segEnd = chunk.second }
                    }
                }
            }
            flush()
        }
        return result
    }

    /** Index of the segment containing [offset], or the last segment starting before it. */
    fun indexForOffset(segments: List<Segment>, offset: Int): Int {
        if (segments.isEmpty()) return 0
        return segments.indexOfLast { it.start <= offset }.coerceAtLeast(0)
    }

    private fun sentencesWithOffsets(text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val para = text.substring(from, to)
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (m in sentenceBoundary.findAll(para)) {
            val end = m.range.first
            if (end > start) out += (from + start) to (from + end)
            start = m.range.last + 1
        }
        if (start < para.length) out += (from + start) to (from + para.length)
        return out
    }

    /** A single sentence longer than [maxChars] is cut at whitespace (or hard, as a last resort). */
    private fun hardSplit(text: String, from: Int, to: Int, maxChars: Int): List<Pair<Int, Int>> {
        if (to - from <= maxChars) return listOf(from to to)
        val out = ArrayList<Pair<Int, Int>>()
        var s = from
        while (to - s > maxChars) {
            var cut = text.lastIndexOf(' ', s + maxChars)
            if (cut <= s) cut = s + maxChars
            out += s to cut
            s = cut
            while (s < to && text[s] == ' ') s++
        }
        if (s < to) out += s to to
        return out
    }
}
