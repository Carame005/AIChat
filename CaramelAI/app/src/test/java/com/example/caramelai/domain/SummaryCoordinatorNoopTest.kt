package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SummaryCoordinator.foldUnderLock] only marks a conversation active when
 * [PromptBuilder.pendingSummaryWork] reports work. These cases are the no-op
 * paths that must not flash "Updating memory".
 */
class SummaryCoordinatorNoopTest {
    private val builder = PromptBuilder()

    private fun msg(id: String, role: String = MessageRole.USER, content: String = "text $id") =
        MessageEntity(id, "c", role, content)

    @Test
    fun alreadyFoldedCutIsNoop() {
        val history = listOf(
            msg("1"),
            msg("2", MessageRole.ASSISTANT),
            msg("3"),
            msg("4", MessageRole.ASSISTANT),
        )
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = "2",
            proposedCut = "2",
            dropped = listOf(history[0], history[1]),
        )
        assertFalse(
            "Coordinator must skip _active when hasWork is false",
            pending.hasWork,
        )
    }

    @Test
    fun dropsBehindCutIsNoop() {
        val history = listOf(
            msg("1"),
            msg("2", MessageRole.ASSISTANT),
            msg("3"),
            msg("4", MessageRole.ASSISTANT),
        )
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = "3",
            proposedCut = "2",
            dropped = listOf(history[0], history[1]),
        )
        assertFalse(pending.hasWork)
    }

    @Test
    fun freshOverflowHasWork() {
        val history = listOf(
            msg("1"),
            msg("2", MessageRole.ASSISTANT),
            msg("3"),
            msg("4", MessageRole.ASSISTANT),
        )
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = null,
            proposedCut = "2",
            dropped = listOf(history[0], history[1]),
        )
        assertTrue(pending.hasWork)
    }

    @Test
    fun shadowCutGatesSameOverflowAsNoop() {
        // Mid-update: effective/shadow cut already at 2. Re-proposing the same
        // drop range must not schedule another fold (the chat-while-updating loop).
        val history = listOf(
            msg("1"),
            msg("2", MessageRole.ASSISTANT),
            msg("3"),
            msg("4", MessageRole.ASSISTANT),
            msg("5"),
            msg("6", MessageRole.ASSISTANT),
        )
        val shadowCut = "2"
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = shadowCut,
            proposedCut = "2",
            dropped = listOf(history[0], history[1]),
        )
        assertFalse(pending.hasWork)
    }

    @Test
    fun overflowBeyondShadowCutStillHasWork() {
        val history = listOf(
            msg("1"),
            msg("2", MessageRole.ASSISTANT),
            msg("3"),
            msg("4", MessageRole.ASSISTANT),
            msg("5"),
            msg("6", MessageRole.ASSISTANT),
        )
        val pending = builder.pendingSummaryWork(
            history = history,
            currentCut = "2",
            proposedCut = "4",
            dropped = listOf(history[2], history[3]),
        )
        assertTrue(pending.hasWork)
        assertEquals("4", pending.cutId)
        assertEquals(listOf("3", "4"), pending.dropped.map { it.id })
    }

    @Test
    fun tinyOverflowIsNotSignificantFold() {
        val dropped = listOf(
            msg("1", content = "short"),
            msg("2", MessageRole.ASSISTANT, content = "also short"),
        )
        assertFalse(builder.isSignificantFold(dropped, "User", "Char"))
    }

    @Test
    fun largeBatchIsSignificantFold() {
        val dropped = (1..10).map { index ->
            msg(
                id = "$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(40),
            )
        }
        assertTrue(builder.isSignificantFold(dropped, "User", "Char"))
    }

    @Test
    fun refoldBlockedUntilLiveThreadIsLongEnough() {
        val history = (1..24).map { index ->
            msg(
                id = "$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(40),
            )
        }
        val dropped = history.take(10)
        // Cut at 12 → only 12 live messages; need 16 before another fold.
        assertFalse(
            builder.shouldAdvanceSummaryCut(
                history = history,
                currentCut = "12",
                dropped = dropped,
                userName = "User",
                characterName = "Char",
            ),
        )
        val longer = history + (25..32).map { index ->
            msg(
                id = "$index",
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "word ".repeat(40),
            )
        }
        assertTrue(
            builder.shouldAdvanceSummaryCut(
                history = longer,
                currentCut = "12",
                dropped = dropped,
                userName = "User",
                characterName = "Char",
            ),
        )
    }
}
