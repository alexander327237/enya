package com.enya.txtvoice.tts

import java.util.Locale

/** A speech backend. [speak] suspends until the segment has been fully played and stops the audio when cancelled. */
interface TtsEngine {
    /** Preferred maximum segment length for this backend. */
    val maxSegmentChars: Int

    /** How many upcoming segments the player should ask [prepare] for. */
    val prefetchAhead: Int get() = 1

    /** Optional prefetch of an upcoming segment (network engines download audio here). */
    suspend fun prepare(segment: Segment, locale: Locale?) {}

    suspend fun speak(segment: Segment, rate: Float, pitch: Float, locale: Locale?, onStart: () -> Unit)

    /** Applies rate/pitch to audio that is already playing, when the backend can do that. */
    fun applyLive(rate: Float, pitch: Float) {}

    fun release()
}
