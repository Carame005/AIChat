package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryChunkingTest {
    @Test
    fun emptyInputYieldsNoChunks() {
        assertTrue(SummaryChunker.chunk(emptyList(), "User", "Char").isEmpty())
    }

    @Test
    fun coversAllMessagesAcrossChunks() {
        val messages = (1..20).map { index ->
            MessageEntity(
                id = "$index",
                conversationId = "c",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "x".repeat(400),
            )
        }
        val chunks = SummaryChunker.chunk(
            dropped = messages,
            userName = "User",
            characterName = "Char",
            maxChars = 1200,
        )
        assertTrue(chunks.size >= 2)
        val flatIds = chunks.flatten().map { it.id }
        assertEquals(messages.map { it.id }, flatIds)
    }

    @Test
    fun doesNotSplitASingleMessage() {
        val long = MessageEntity(
            id = "1",
            conversationId = "c",
            role = MessageRole.USER,
            content = "y".repeat(8000),
        )
        val chunks = SummaryChunker.chunk(
            dropped = listOf(long),
            userName = "User",
            characterName = "Char",
            maxChars = 500,
        )
        assertEquals(1, chunks.size)
        assertEquals(listOf("1"), chunks.single().map { it.id })
    }

    @Test
    fun respectsMessageBoundariesWhenFillingBudget() {
        val a = MessageEntity("a", "c", MessageRole.USER, "a".repeat(300))
        val b = MessageEntity("b", "c", MessageRole.ASSISTANT, "b".repeat(300))
        val c = MessageEntity("c", "c", MessageRole.USER, "c".repeat(300))
        val chunks = SummaryChunker.chunk(
            dropped = listOf(a, b, c),
            userName = "User",
            characterName = "Char",
            maxChars = 650,
        )
        assertEquals(2, chunks.size)
        assertEquals(listOf("a", "b"), chunks[0].map { it.id })
        assertEquals(listOf("c"), chunks[1].map { it.id })
    }
}
