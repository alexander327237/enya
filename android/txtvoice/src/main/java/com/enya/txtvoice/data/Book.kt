package com.enya.txtvoice.data

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String,
    val title: String,
    val length: Int,
    /** Character offset of the segment the user last listened to. */
    val charOffset: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long
) {
    val progressPercent: Int
        get() = if (length <= 0) 0 else ((charOffset.toLong() * 100) / length).toInt().coerceIn(0, 100)
}
