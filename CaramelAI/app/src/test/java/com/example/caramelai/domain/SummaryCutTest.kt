package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.remote.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCutTest {
    private val builder = PromptBuilder()

    private fun msg(id: String, role: String, content: String = "text $id") =
        MessageEntity(id, "c", role, content)

    @Test
    fun isCutAheadAdvancesEqualsAndBacktracks() {
        val history = listOf(
            msg("1", MessageRole.USER),
            msg("2", MessageRole.ASSISTANT),
            msg("3", MessageRole.USER),
            msg("4", MessageRole.ASSISTANT),
        )
        assertTrue(builder.isCutAhead(history, currentCut = null, newCut = "2"))
        assertTrue(builder.isCutAhead(history, currentCut = "1", newCut = "3"))
        assertFalse(builder.isCutAhead(history, currentCut = "3", newCut = "3"))
        assertFalse(builder.isCutAhead(history, currentCut = "3", newCut = "2"))
        assertFalse(builder.isCutAhead(history, currentCut = "2", newCut = "missing"))
        assertFalse(builder.isCutAhead(history, currentCut = null, newCut = null))
    }

    @Test
    fun pendingSummaryWorkClearsDropsAlreadyBehindCut() {
        val history = listOf(
            msg("1", MessageRole.USER),
            msg("2", MessageRole.ASSISTANT),
            msg("3", MessageRole.USER),
            msg("4", MessageRole.ASSISTANT),
        )
        val dropped = listOf(history[0], history[1])
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = "2",
            proposedCut = "2",
            dropped = dropped,
        )
        assertFalse(pending.hasWork)
        assertEquals(null, pending.cutId)
        assertTrue(pending.dropped.isEmpty())
    }

    @Test
    fun pendingSummaryWorkKeepsVisibleDropsWhenCutAhead() {
        val history = listOf(
            msg("1", MessageRole.USER),
            msg("2", MessageRole.ASSISTANT),
            msg("3", MessageRole.USER),
            msg("4", MessageRole.ASSISTANT),
        )
        val dropped = listOf(history[0], history[1])
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = null,
            proposedCut = "2",
            dropped = dropped,
        )
        assertTrue(pending.hasWork)
        assertEquals("2", pending.cutId)
        assertEquals(listOf("1", "2"), pending.dropped.map { it.id })
    }

    @Test
    fun pendingSummaryWorkClearsStaleProposedCut() {
        val history = listOf(
            msg("1", MessageRole.USER),
            msg("2", MessageRole.ASSISTANT),
            msg("3", MessageRole.USER),
            msg("4", MessageRole.ASSISTANT),
        )
        // Cut already at 3; proposing 2 again must not re-schedule a fold.
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = "3",
            proposedCut = "2",
            dropped = listOf(history[0], history[1]),
        )
        assertFalse(pending.hasWork)
        assertTrue(pending.dropped.isEmpty())
    }

    @Test
    fun pendingSummaryWorkFiltersToVisibleOnly() {
        val history = listOf(
            msg("1", MessageRole.USER),
            msg("2", MessageRole.ASSISTANT),
            msg("3", MessageRole.USER),
            msg("4", MessageRole.ASSISTANT),
        )
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = "1",
            proposedCut = "3",
            dropped = listOf(history[0], history[1], history[2]),
        )
        assertTrue(pending.hasWork)
        assertEquals("3", pending.cutId)
        assertEquals(listOf("2", "3"), pending.dropped.map { it.id })
    }

    @Test
    fun trimmedHistoryStartsOnUserTurn() {
        // Plenty of older filler so soft-trim drops from the front of the older
        // region while the recent window (ending on an assistant) stays intact.
        // The kept block as a whole should still open on a user turn when possible.
        // Budget must clear the recent floor so we exercise soft-trim, not recentTruncated.
        val history = (1..40).map { index ->
            msg(
                id = "$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(40),
            )
        }
        val selection = builder.selectHistoryWithinBudget(
            history = history,
            characterName = "Char",
            userName = "User",
            budget = 5_000,
            softTargetRatio = 0.65f,
        )
        assertTrue(selection.kept.isNotEmpty())
        assertTrue(selection.kept.any { it.id == "40" })
        assertFalse(selection.recentTruncated)
        // Soft trim may omit older turns from kept; when hard-dropped exist, prefer user-led.
        if (selection.dropped.isNotEmpty()) {
            assertEquals(MessageRole.USER, selection.kept.first().role)
        }
    }

    @Test
    fun normalizeUsesContinueWhenPriorContext() {
        val messages = listOf(
            ChatMessageDto(MessageRole.ASSISTANT, "Hello from the middle."),
            ChatMessageDto(MessageRole.USER, "Hi again"),
        )
        val withContext = builder.normalizeAlternatingRoles(messages, hasPriorContext = true)
        assertEquals(MessageRole.USER, withContext.first().role)
        assertEquals("(Continue the story.)", withContext.first().content)

        val fresh = builder.normalizeAlternatingRoles(messages, hasPriorContext = false)
        assertEquals("(Begin the scene.)", fresh.first().content)
    }

    @Test
    fun softTrimAloneDoesNotAdvanceSummaryCut() {
        // History hard-fits at ratio 1.0, but soft-trim omits older turns from the prompt.
        val history = (1..16).map { index ->
            msg(
                id = "m$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(25),
            )
        }
        val hard = builder.selectHistoryWithinBudget(
            history = history,
            characterName = "Char",
            userName = "User",
            budget = 2_000,
            softTargetRatio = 1.0f,
        )
        assertTrue(hard.dropped.isEmpty())
        assertEquals(null, hard.newCutMessageId)

        val soft = builder.selectHistoryWithinBudget(
            history = history,
            characterName = "Char",
            userName = "User",
            budget = 2_000,
            softTargetRatio = 0.65f,
        )
        assertTrue(soft.kept.size <= hard.kept.size)
        assertTrue(soft.dropped.isEmpty())
        assertEquals(null, soft.newCutMessageId)
        assertTrue(soft.kept.any { it.id == "m16" })
    }

    @Test
    fun hardOverflowCutExcludesProtectedRecentWindow() {
        val history = (1..40).map { index ->
            msg(
                id = "m$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(40),
            )
        }
        val selection = builder.selectHistoryWithinBudget(
            history = history,
            characterName = "Char",
            userName = "User",
            budget = 1_200,
            softTargetRatio = 1.0f,
        )
        assertTrue(selection.dropped.isNotEmpty())
        assertNotNull(selection.newCutMessageId)
        val recent = builder.selectRecentWindow(history, "Char", "User", 1_200)
        val recentIds = recent.map { it.id }.toSet()
        assertTrue(selection.dropped.none { it.id in recentIds })
        assertTrue(selection.kept.any { it.id == "m40" })
    }

    @Test
    fun recentTruncationDoesNotFoldLiveThreadMessages() {
        val history = (1..12).map { index ->
            msg(
                id = "m$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(80),
            )
        }
        val selection = builder.selectHistoryWithinBudget(
            history = history,
            characterName = "Char",
            userName = "User",
            budget = 200,
            softTargetRatio = 1.0f,
        )
        assertTrue(selection.recentTruncated)
        val recent = builder.selectRecentWindow(history, "Char", "User", 200)
        val recentIds = recent.map { it.id }.toSet()
        // Live-floor messages omitted from the prompt must not advance the cut.
        assertTrue(selection.dropped.none { it.id in recentIds })
        assertTrue(selection.kept.isNotEmpty())
        assertTrue(selection.kept.any { it.id == "m12" })
    }

    @Test
    fun longSummaryStillKeepsNewestHistoryInPrompt() {
        val character = com.example.caramelai.data.db.CharacterEntity(id = "1", name = "Ganyu")
        val history = (1..20).map { index ->
            MessageEntity(
                id = "m$index",
                conversationId = "c",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "Live beat $index with enough padding for token estimates here.",
            )
        }
        val bloatedSummary = "Earlier scene detail. ".repeat(80)
        val built = builder.buildDetailed(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 2_500,
            maxTokens = 256,
            summary = bloatedSummary,
            summaryUpToMessageId = null,
            softTargetRatio = 1.0f,
        )
        assertTrue(built.messages.any { it.content.contains("Live beat 20") })
        assertTrue(built.messages.any { it.content.contains("Live beat 19") })
        if (built.recentTruncated) {
            assertTrue(built.droppedForSummary.none { it.content.contains("Live beat 20") })
        }
    }

    @Test
    fun provisionalSummaryKeepsDroppedTurnsVisibleInSystemPrompt() {
        val character = com.example.caramelai.data.db.CharacterEntity(id = "1", name = "Ganyu")
        val dropped = listOf(
            msg("d1", MessageRole.USER, "We met at the harbour."),
            msg("d2", MessageRole.ASSISTANT, "I bought tea for both of us."),
        )
        val pending = SummaryText.compactPendingTranscript(dropped, "Carlos", "Ganyu")
        val provisional = SummaryText.mergeProvisionalSummary(
            existing = "They already knew each other.",
            pending = pending,
        )
        val history = listOf(
            msg("3", MessageRole.USER, "What now?"),
            msg("4", MessageRole.ASSISTANT, "Walk with me."),
        )
        val built = builder.buildDetailed(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 256,
            summary = provisional,
            summaryUpToMessageId = "d2",
            softTargetRatio = 1.0f,
        )
        val system = built.messages.first().content
        assertTrue(system.contains("Story so far:"))
        assertTrue(system.contains("They already knew each other."))
        assertTrue(system.contains(SummaryText.OLDER_EVENTS_MARKER))
        assertTrue(system.contains("We met at the harbour."))
        assertTrue(system.contains("I bought tea for both of us."))
        assertTrue(system.contains("MORE RECENT") || system.contains("live chat"))
    }

    @Test
    fun userLedTrimMovesStrippedAssistantIntoDropped() {
        // Force hard overflow with an older assistant-led edge that preferUserLedWindow
        // will strip so the kept window starts on a user turn.
        val history = mutableListOf<MessageEntity>()
        history += msg("a0", MessageRole.ASSISTANT, "orphan opener ".repeat(30))
        for (index in 1..36) {
            history += msg(
                id = "m$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(40),
            )
        }
        val selection = builder.selectHistoryWithinBudget(
            history = history,
            characterName = "Char",
            userName = "User",
            budget = 1_200,
            softTargetRatio = 1.0f,
        )
        assertTrue(selection.dropped.isNotEmpty())
        if (selection.kept.firstOrNull()?.role == MessageRole.USER) {
            // Any assistant opener removed for alignment must be folded, not lost.
            val keptIds = selection.kept.map { it.id }.toSet()
            if ("a0" !in keptIds) {
                assertTrue(selection.dropped.any { it.id == "a0" })
            }
        }
        assertNotNull(selection.newCutMessageId)
    }

    @Test
    fun branchSummaryClearedWhenCutNotInKept() {
        // Mirrors ChatRepository.branchConversation keepSummary rule.
        val keptIds = setOf("1", "2", "3")
        val sourceCut = "9"
        val branchedSummaryCut = sourceCut.takeIf { it in keptIds }
        val keepSummary = branchedSummaryCut != null && "old summary".isNotBlank()
        assertFalse(keepSummary)
        assertEquals(null, branchedSummaryCut)
    }
}
