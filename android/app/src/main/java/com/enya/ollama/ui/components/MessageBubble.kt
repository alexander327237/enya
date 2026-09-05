package com.enya.ollama.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.enya.ollama.data.db.MessageEntity
import com.enya.ollama.data.db.Role

@Composable
fun MessageBubble(message: MessageEntity, modifier: Modifier = Modifier) {
    val isUser = message.role == Role.USER
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column {
                val imageCount = message.images?.split("|")?.count { it.isNotEmpty() } ?: 0
                if (imageCount > 0) {
                    Text(
                        "🖼️ $imageCount image${if (imageCount > 1) "s" else ""} attached",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                val displayText = if (message.content.isEmpty() && message.isStreaming) "…" else message.content
                MarkdownText(text = displayText)
            }
        }
    }
}
