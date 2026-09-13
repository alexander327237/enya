package com.enya.txtvoice

import com.enya.txtvoice.tts.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {

    @Test
    fun splitsParagraphsAndMergesSentencesUpToLimit() {
        val text = "Первое предложение. Второе предложение! Третье?\n\nНовый абзац."
        val segments = Segmenter.split(text, 40)
        assertEquals(listOf("Первое предложение. Второе предложение!", "Третье?", "Новый абзац."), segments.map { it.text })
        assertTrue(segments[0].paragraphStart)
        assertTrue(!segments[1].paragraphStart)
        assertTrue(segments[2].paragraphStart)
        segments.forEach { assertEquals(it.text, text.substring(it.start, it.end).trim()) }
        assertEquals(listOf(0, 1, 2), segments.map { it.index })
    }

    @Test
    fun cutsOverlongSentencesAtWhitespace() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val segments = Segmenter.split(text, 30)
        assertTrue(segments.size > 1)
        segments.forEach { assertTrue(it.text.length <= 30) }
        assertEquals(text, segments.joinToString(" ") { it.text })
    }

    @Test
    fun indexForOffsetFindsContainingSegment() {
        val text = "One. Two. Three."
        val segments = Segmenter.split(text, 6)
        assertEquals(3, segments.size)
        assertEquals(0, Segmenter.indexForOffset(segments, 0))
        assertEquals(1, Segmenter.indexForOffset(segments, segments[1].start + 1))
        assertEquals(2, Segmenter.indexForOffset(segments, text.length))
    }

    @Test
    fun skipsBlankParagraphs() {
        assertEquals(emptyList<String>(), Segmenter.split("\n\n   \n", 100).map { it.text })
    }
}
