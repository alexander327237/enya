package com.enya.txtvoice

import com.enya.txtvoice.tts.EdgeTtsClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Date

class EdgeTtsClientTest {

    @Test
    fun secMsGecMatchesReferenceImplementation() {
        // Value produced by the Python edge-tts DRM.generate_sec_ms_gec() for the same timestamp.
        assertEquals(
            "CA99F0B37F2EAC6F5D719AE4BA7C98978842B3F335D9F42979070A8DB149F0A5",
            EdgeTtsClient.generateSecMsGec(1790000000.0)
        )
        // Rounded down to 5 minutes, so the token is stable inside the window.
        assertEquals(EdgeTtsClient.generateSecMsGec(1790000000.0), EdgeTtsClient.generateSecMsGec(1790000099.9))
    }

    @Test
    fun expandsShortVoiceNames() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (ru-RU, SvetlanaNeural)",
            EdgeTtsClient.longVoiceName("ru-RU-SvetlanaNeural")
        )
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            EdgeTtsClient.longVoiceName("zh-CN-liaoning-XiaobeiNeural")
        )
    }

    @Test
    fun ssmlEscapesMarkupAndDropsControlCharacters() {
        val ssml = EdgeTtsClient.buildSsml("A & B <c>\u000Bd", "ru-RU-DmitryNeural")
        assertTrue(ssml.contains(">A &amp; B &lt;c&gt; d</prosody>"))
        assertTrue(ssml.contains("(ru-RU, DmitryNeural)"))
    }

    @Test
    fun dateStringLooksLikeJavaScript() {
        assertEquals(
            "Sun Sep 13 2026 12:26:40 GMT+0000 (Coordinated Universal Time)",
            EdgeTtsClient.dateToString(Date(1789302400000L))
        )
    }

    @Test
    fun extractsAudioFromBinaryFrames() {
        val header = "X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n".toByteArray()
        val audio = byteArrayOf(1, 2, 3, 4)
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + audio
        assertArrayEquals(audio, EdgeTtsClient.extractAudio(frame))

        val endHeader = "X-RequestId:abc\r\nPath:audio\r\n".toByteArray()
        val end = byteArrayOf(0, endHeader.size.toByte()) + endHeader
        assertNull(EdgeTtsClient.extractAudio(end))
    }

    /** Talks to the real service. Run with EDGE_TTS_LIVE=1. */
    @Test
    fun liveSynthesisReturnsMp3() {
        assumeTrue(System.getenv("EDGE_TTS_LIVE") == "1")
        val out = ByteArrayOutputStream()
        EdgeTtsClient(OkHttpClient()).synthesize("Привет! Это проверка голоса.", "ru-RU-SvetlanaNeural", out)
        val bytes = out.toByteArray()
        assertTrue("got ${bytes.size} bytes", bytes.size > 5000)
        // MPEG audio frame sync: 11 set bits.
        assertTrue((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0)
        System.getenv("EDGE_TTS_OUT")?.let { java.io.File(it).writeBytes(bytes) }
    }
}
