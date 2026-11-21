package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole

/** Splits dropped history into character-budget chunks without splitting a message. */
object SummaryChunker {
    /** Large enough that typical folds fit in one LLM call (faster, ends on latest beats). */
    const val DEFAULT_MAX_CHARS = 6000

    fun chunk(
        dropped: List<MessageEntity>,
        userName: String,
        characterName: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<List<MessageEntity>> {
        if (dropped.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<MessageEntity>>()
        var current = mutableListOf<MessageEntity>()
        var currentLen = 0
        for (message in dropped) {
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            val lineLen = speaker.length + 2 + message.content.trim().length + 1
            if (current.isNotEmpty() && currentLen + lineLen > maxChars) {
                chunks += current
                current = mutableListOf()
                currentLen = 0
            }
            current += message
            currentLen += lineLen
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }
}
