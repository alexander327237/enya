package com.enya.txtvoice

import com.enya.txtvoice.data.TextDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TextDecoderTest {

    private val sample = "Мороз и солнце; день чудесный! Ещё ты дремлешь, друг прелестный.\nПора, красавица, проснись."

    @Test
    fun utf8WithBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample, TextDecoder.decode(bytes))
    }

    @Test
    fun utf8WithoutBom() {
        assertEquals(sample, TextDecoder.decode(sample.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun utf16LittleEndianWithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        assertEquals(sample, TextDecoder.decode(bytes))
    }

    @Test
    fun windows1251() {
        assertEquals(sample, TextDecoder.decode(sample.toByteArray(Charset.forName("windows-1251"))))
    }

    @Test
    fun koi8r() {
        assertEquals(sample, TextDecoder.decode(sample.toByteArray(Charset.forName("KOI8-R"))))
    }
}
