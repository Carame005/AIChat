package com.example.caramelai.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionDetectorTest {
    @Test
    fun scoreIsLowForDifferentReplies() {
        val score = RepetitionDetector.repetitionScore(
            "*She walks to the window and opens it.* \"Fresh air.\"",
            listOf("*He laughs and shakes his head.* \"Not tonight.\""),
        )
        assertTrue(score < RepetitionDetector.STRENGTHEN_THRESHOLD)
    }

    @Test
    fun scoreIsHighForNearDuplicateAndMatchingOpening() {
        val previous = "*She smiles softly at him* \"I missed you so much tonight.\""
        val next = "*She smiles softly at him* \"I missed you so much this evening.\""
        val score = RepetitionDetector.repetitionScore(next, listOf(previous))
        assertTrue(score >= RepetitionDetector.STRENGTHEN_THRESHOLD)
        assertTrue(RepetitionDetector.shouldStrengthenStyle(next, listOf(previous)))
    }

    @Test
    fun emptyInputsScoreZero() {
        assertTrue(RepetitionDetector.repetitionScore("", listOf("hello")) == 0.0)
        assertTrue(RepetitionDetector.repetitionScore("hello", emptyList()) == 0.0)
        assertFalse(RepetitionDetector.shouldStrengthenStyle("hello", emptyList()))
    }

    @Test
    fun isReanswerDetectsEchoOfPreviousAssistant() {
        val previous = "*She smiles softly at him* \"I missed you so much tonight.\""
        val draft = "*She smiles softly at him* \"I missed you so much this evening.\""
        assertTrue(
            RepetitionDetector.isReanswer(
                draft = draft,
                lastUser = "What are you thinking?",
                lastAssistant = previous,
            ),
        )
    }

    @Test
    fun isReanswerDetectsQuotingUserMessage() {
        val user = "Please tell me about the harbour festival and why you left early."
        val draft = buildString {
            append("*She nods.* You asked me to ")
            append("please tell me about the harbour festival and why you left early, ")
            append("so here is what happened.")
        }
        assertTrue(
            RepetitionDetector.isReanswer(
                draft = draft,
                lastUser = user,
                lastAssistant = "*She looks away.* \"Later.\"",
            ),
        )
    }

    @Test
    fun isReanswerIsFalseForFreshContinuation() {
        assertFalse(
            RepetitionDetector.isReanswer(
                draft = "*She walks to the balcony and breathes in the night air.* \"Come with me.\"",
                lastUser = "What should we do next?",
                lastAssistant = "*She laughs softly.* \"You always ask that.\"",
            ),
        )
    }
}
