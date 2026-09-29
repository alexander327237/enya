package com.enya.txtvoice.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for Microsoft Edge's "Read aloud" neural voices (the service behind the Edge browser's
 * read-aloud feature). Free and keyless, but unofficial: Microsoft can change it at any time.
 *
 * Mirrors the protocol of the reference `edge-tts` Python package: one WebSocket per request,
 * a `speech.config` message, an SSML message, then binary `Path:audio` frames until `turn.end`.
 *
 * Pure JVM (OkHttp only), so it can be exercised from unit tests.
 */
class EdgeTtsClient(private val http: OkHttpClient) {

    class EdgeTtsException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Synthesizes [text] with [voice] (short form like `ru-RU-SvetlanaNeural`) and writes MP3 bytes
     * to [out]. Blocking. Retries once after correcting the clock when the service answers 403,
     * since the auth token is derived from the current time.
     */
    fun synthesize(text: String, voice: String, out: OutputStream, timeoutSeconds: Long = 60) {
        try {
            synthesizeOnce(text, voice, out, timeoutSeconds)
        } catch (e: HandshakeRejected) {
            if (e.code != 403 || !adjustClockSkew(e.serverDate)) {
                throw EdgeTtsException("Edge TTS HTTP ${e.code}", e)
            }
            synthesizeOnce(text, voice, out, timeoutSeconds)
        }
    }

    private class HandshakeRejected(val code: Int, val serverDate: String?) : IOException("HTTP $code")

    private fun synthesizeOnce(text: String, voice: String, out: OutputStream, timeoutSeconds: Long) {
        val request = Request.Builder()
            .url(
                "$WSS_URL&ConnectionId=${connectId()}" +
                    "&Sec-MS-GEC=${generateSecMsGec(nowUnixSeconds())}" +
                    "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
            )
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${generateMuid()};")
            .build()

        val done = CountDownLatch(1)
        val turnEnded = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val audioBytes = AtomicLong(0)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(speechConfigMessage(dateToString()))
                webSocket.send(ssmlMessage(connectId(), dateToString(), buildSsml(text, voice)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val path = parseTextPath(text)
                if (path == "turn.end") {
                    turnEnded.set(true)
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val audio = extractAudio(bytes.toByteArray())
                    if (audio != null && audio.isNotEmpty()) {
                        out.write(audio)
                        audioBytes.addAndGet(audio.size.toLong())
                    }
                } catch (e: Throwable) {
                    failure.set(e)
                    webSocket.cancel()
                    done.countDown()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (turnEnded.get()) return // the stream already completed; late socket errors don't matter
                failure.set(if (response != null && !response.isSuccessful && response.code != 101) {
                    HandshakeRejected(response.code, response.header("Date"))
                } else {
                    t
                })
                done.countDown()
            }
        }

        val ws = http.newWebSocket(request, listener)
        val finished = try {
            done.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            ws.cancel()
            throw EdgeTtsException("Interrupted", e)
        }
        if (!finished) {
            ws.cancel()
            throw EdgeTtsException("Edge TTS timed out")
        }
        failure.get()?.let { f ->
            if (f is HandshakeRejected) throw f
            throw EdgeTtsException(f.message ?: f.toString(), f)
        }
        if (audioBytes.get() == 0L) throw EdgeTtsException("Edge TTS returned no audio")
    }

    companion object {
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        const val WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
        private const val WIN_EPOCH = 11644473600.0

        /** Correction applied to the device clock, learned from the server's Date header. */
        @Volatile
        private var clockSkewSeconds = 0.0

        private val random = SecureRandom()

        private fun nowUnixSeconds(): Double = System.currentTimeMillis() / 1000.0 + clockSkewSeconds

        /** Token = SHA-256 of (Windows file time rounded down to 5 minutes) + client token, upper-case hex. */
        fun generateSecMsGec(unixSeconds: Double): String {
            var ticks = unixSeconds + WIN_EPOCH
            ticks -= ticks % 300
            ticks *= 1e9 / 100
            val toHash = String.format(Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN
            val digest = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it) }
        }

        private fun adjustClockSkew(serverDate: String?): Boolean {
            if (serverDate == null) return false
            val parsed = runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(serverDate)
            }.getOrNull() ?: return false
            clockSkewSeconds += parsed.time / 1000.0 - nowUnixSeconds()
            return true
        }

        private fun generateMuid(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02X".format(it) }
        }

        private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

        /** JavaScript-style date string, as sent by the Edge browser. */
        fun dateToString(date: Date = Date()): String {
            val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(date)
        }

        fun speechConfigMessage(timestamp: String): String =
            "X-Timestamp:$timestamp\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

        fun ssmlMessage(requestId: String, timestamp: String, ssml: String): String =
            "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${timestamp}Z\r\n" + // the trailing Z mirrors a quirk of the Edge browser
                "Path:ssml\r\n\r\n" +
                ssml

        /** Expands `ru-RU-SvetlanaNeural` to the long name the service expects. */
        fun longVoiceName(voice: String): String {
            val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(voice) ?: return voice
            var lang = m.groupValues[1]
            var region = m.groupValues[2]
            var name = m.groupValues[3]
            if ('-' in name) {
                region = "$region-${name.substringBefore('-')}"
                name = name.substringAfter('-')
            }
            return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
        }

        fun buildSsml(text: String, voice: String, rate: String = "+0%", pitch: String = "+0Hz", volume: String = "+0%"): String =
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='${longVoiceName(voice)}'>" +
                "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
                escapeXml(removeIncompatibleCharacters(text)) +
                "</prosody></voice></speak>"

        /** The service rejects some control characters (vertical tab is common in OCR-ed text). */
        fun removeIncompatibleCharacters(text: String): String = buildString(text.length) {
            for (ch in text) {
                val c = ch.code
                append(if (c in 0..8 || c in 11..12 || c in 14..31) ' ' else ch)
            }
        }

        fun escapeXml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        private fun parseTextPath(message: String): String? {
            val headerEnd = message.indexOf("\r\n\r\n").let { if (it == -1) message.length else it }
            return message.substring(0, headerEnd).split("\r\n")
                .firstOrNull { it.startsWith("Path:") }
                ?.substringAfter("Path:")
        }

        /**
         * A binary frame is: 2-byte big-endian header length, text headers, audio bytes.
         * Returns the audio payload, or null for frames that carry none (the final empty frame).
         */
        fun extractAudio(frame: ByteArray): ByteArray? {
            if (frame.size < 2) throw EdgeTtsException("Binary frame without header length")
            val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
            if (headerLength + 2 > frame.size) throw EdgeTtsException("Header length exceeds frame size")
            val headers = String(frame, 2, headerLength, Charsets.UTF_8).split("\r\n")
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            if (headers["Path"] != "audio") throw EdgeTtsException("Unexpected binary frame path: ${headers["Path"]}")
            val payload = frame.copyOfRange(2 + headerLength, frame.size)
            if (headers["Content-Type"] == null) {
                if (payload.isEmpty()) return null
                throw EdgeTtsException("Audio frame without Content-Type")
            }
            return payload
        }
    }
}
