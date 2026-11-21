package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun sanitizeSummaryTrimsIncompleteTailAndCapsPreferringCurrentEnd() {
        assertEquals(
            "They met at the harbour. She offered tea.",
            SummaryText.sanitize(
                "They met at the harbour. She offered tea. Then she started to sa",
            ),
        )
        val long = "Origin names here. " + "Old background. ".repeat(80) +
            "They are in the kitchen now arguing about the letter."
        val capped = SummaryText.sanitize(long, maxChars = 200)
        assertTrue(capped.length <= 200)
        assertTrue(capped.contains("kitchen") || capped.contains("letter"))
        assertFalse(capped.endsWith("Origin names here."))
    }

    @Test
    fun capPreferringCurrentSituationKeepsTail() {
        val text = "Alpha start. " + "mid ".repeat(100) + "Omega current scene ends here."
        val capped = SummaryText.capPreferringCurrentSituation(text, maxChars = 80)
        assertTrue(capped.length <= 80)
        assertTrue(capped.contains("Omega") || capped.contains("current"))
    }

    @Test
    fun defaultChunkBudgetIsLargeForSingleShotFolds() {
        assertEquals(6000, SummaryChunker.DEFAULT_MAX_CHARS)
        assertEquals(2500, SummaryText.MAX_SUMMARY_CHARS)
        assertEquals(4500, SummaryText.SUMMARIZER_TRANSCRIPT_CHARS)
    }

    @Test
    fun stripEphemeralSectionsRemovesDialogueDumps() {
        val dirty = buildString {
            appendLine("They met at the harbour.")
            appendLine()
            appendLine(SummaryText.CLOSING_BEAT_MARKER)
            appendLine("Carlos: We are in the kitchen")
            appendLine("Ganyu: I hide the letter")
        }
        val clean = SummaryText.stripEphemeralSections(dirty)
        assertTrue(clean.contains("harbour"))
        assertFalse(clean.contains(SummaryText.CLOSING_BEAT_MARKER))
        assertFalse(clean.contains("kitchen"))
        assertEquals(clean, SummaryText.sanitize(dirty))
    }

    @Test
    fun stripForPersistenceAlsoDropsOlderEventsProvisional() {
        val dirty = buildString {
            appendLine("They met at the harbour.")
            appendLine()
            appendLine(SummaryText.OLDER_EVENTS_MARKER)
            appendLine("Carlos: Hi")
        }
        val forPrompt = SummaryText.stripEphemeralSections(dirty)
        assertTrue(forPrompt.contains(SummaryText.OLDER_EVENTS_MARKER))
        val forDb = SummaryText.stripForPersistence(dirty)
        assertFalse(forDb.contains(SummaryText.OLDER_EVENTS_MARKER))
        assertTrue(forDb.contains("harbour"))
    }

    @Test
    fun mergeProvisionalDoesNotUseLiveLookingRecentLabel() {
        val merged = SummaryText.mergeProvisionalSummary(
            existing = "They already knew each other.",
            pending = "Carlos: Hi\nGanyu: Hello",
        )
        assertTrue(merged.contains(SummaryText.OLDER_EVENTS_MARKER))
        assertFalse(merged.contains(SummaryText.PENDING_EVENTS_MARKER))
        assertTrue(merged.contains("MORE RECENT") || merged.contains("NOT the present"))
    }

    @Test
    fun closingBeatUsesNewestDroppedTurnsForPromptGuidance() {
        val dropped = listOf(
            MessageEntity("1", "c", MessageRole.USER, "Old hello"),
            MessageEntity("2", "c", MessageRole.ASSISTANT, "Old reply"),
            MessageEntity("3", "c", MessageRole.USER, "We are in the kitchen now"),
            MessageEntity("4", "c", MessageRole.ASSISTANT, "I hide the letter behind the flour"),
        )
        val beat = SummaryText.closingBeatFromDropped(
            dropped = dropped,
            userName = "Carlos",
            characterName = "Ganyu",
            maxMessages = 2,
        )
        assertTrue(beat.contains("kitchen"))
        assertTrue(beat.contains("letter") || beat.contains("flour"))
        assertFalse(beat.contains("Old hello"))
    }

    @Test
    fun ensurePresentClosingAppendsAndReplacesPriorPresent() {
        val first = SummaryText.ensurePresentClosing(
            summary = "They met at the harbour.",
            present = "They stood in the kitchen arguing about the letter.",
        )
        assertTrue(first.contains("harbour"))
        assertTrue(first.contains(SummaryText.PRESENT_MARKER))
        assertTrue(first.endsWith("They stood in the kitchen arguing about the letter."))

        val replaced = SummaryText.ensurePresentClosing(
            summary = first,
            present = "She hid the letter behind the flour.",
        )
        assertTrue(replaced.contains("harbour"))
        assertTrue(replaced.contains("flour"))
        assertFalse(replaced.contains("arguing about the letter"))
        assertEquals(1, replaced.split(SummaryText.PRESENT_MARKER).size - 1)
    }

    @Test
    fun stripEphemeralDoesNotRemovePresentMarker() {
        val text = buildString {
            appendLine("They met at the harbour.")
            appendLine()
            appendLine(SummaryText.PRESENT_MARKER)
            appendLine("They were in the kitchen.")
        }
        val kept = SummaryText.stripEphemeralSections(text)
        assertTrue(kept.contains(SummaryText.PRESENT_MARKER))
        assertTrue(kept.contains("kitchen"))
        assertEquals("They were in the kitchen.", SummaryText.extractPresentClosing(kept))
        assertEquals("They met at the harbour.", SummaryText.stripPresentClosing(kept))
    }

    @Test
    fun sanitizeCapKeepsPresentBlock() {
        val present = "They are in the kitchen now arguing about the letter."
        val long = buildString {
            append("Origin names here. ")
            append("Old background. ".repeat(80))
            appendLine()
            appendLine(SummaryText.PRESENT_MARKER)
            append(present)
        }
        val capped = SummaryText.sanitize(long, maxChars = 220)
        assertTrue(capped.length <= 220)
        assertTrue(capped.contains(SummaryText.PRESENT_MARKER))
        assertTrue(capped.contains("kitchen") || capped.contains("letter"))
    }

    @Test
    fun stripAndReattachPresentSimulatesCondensePath() {
        val full = SummaryText.ensurePresentClosing(
            summary = "Long background about the harbour and the letter.",
            present = "They argued in the kitchen.",
        )
        val present = SummaryText.extractPresentClosing(full)!!
        val body = SummaryText.stripPresentClosing(full)
        assertFalse(body.contains(SummaryText.PRESENT_MARKER))
        val reattached = SummaryText.ensurePresentClosing(body, present)
        assertEquals(present, SummaryText.extractPresentClosing(reattached))
        assertTrue(reattached.contains("harbour"))
    }

    @Test
    fun looksLikeDialogueRejectsSpeakerLabels() {
        assertTrue(SummaryText.looksLikeDialogue("Carlos: We are in the kitchen"))
        assertFalse(
            SummaryText.looksLikeDialogue(
                "Carlos and Ganyu argued in the kitchen about the letter.",
            ),
        )
    }

    @Test
    fun ensurePresentClosingRejectsDialoguePresent() {
        val result = SummaryText.ensurePresentClosing(
            summary = "They met at the harbour.",
            present = "Carlos: Hide it\nGanyu: Okay",
        )
        assertFalse(result.contains(SummaryText.PRESENT_MARKER))
        assertTrue(result.contains("harbour"))
    }
}
