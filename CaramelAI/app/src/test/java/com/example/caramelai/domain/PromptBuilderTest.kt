package com.example.caramelai.domain

import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatCompletionRequest
import com.example.caramelai.data.remote.ChatMessageDto
import com.example.caramelai.data.remote.GenerationParams
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val builder = PromptBuilder()

    @Test
    fun replacesPlaceholdersInSystemPrompt() {
        val character = CharacterEntity(
            id = "1",
            name = "Ganyu",
            description = "{{char}} lives with {{user}}.",
        )
        val system = builder.buildSystemPrompt(character, "Carlos")
        assertTrue(system.contains("Ganyu lives with Carlos."))
        assertTrue(!system.contains("{{char}}"))
        assertTrue(system.contains("Roleplay formatting:"))
        assertTrue(system.contains("Turn-taking rules"))
    }

    @Test
    fun includesPersonaDescription() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val persona = PersonaEntity(
            id = "p1",
            name = "Carlos",
            description = "Kind and introverted.",
        )
        val system = builder.buildSystemPrompt(character, persona, "Carlos")
        assertTrue(system.contains("User persona: Kind and introverted."))
    }

    @Test
    fun includesGlobalBehavior() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val system = builder.buildSystemPrompt(
            character = character,
            persona = null,
            userName = "Carlos",
            globalBehavior = "Lean into romantic tension.",
        )
        assertTrue(system.contains("Global user preferences"))
        assertTrue(system.contains("Lean into romantic tension."))
    }

    @Test
    fun systemPromptDoesNotEmbedContinueInstructions() {
        val character = CharacterEntity(id = "1", name = "Ganyu", scenario = "A quiet café.")
        val system = builder.buildSystemPrompt(
            character = character,
            persona = null,
            userName = "Carlos",
        )
        assertFalse(system.contains("CONTINUE your previous"))
    }

    @Test
    fun includesSummaryAndPinnedMemory() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val system = builder.buildSystemPrompt(
            character = character,
            persona = null,
            userName = "Carlos",
            summary = "They met at the harbour.",
            pinnedMemory = "Ganyu has a blue hairpin.",
        )
        assertTrue(system.contains("Story so far:"))
        assertTrue(system.contains("They met at the harbour."))
        assertTrue(system.contains("Key facts"))
        assertTrue(system.contains("Ganyu has a blue hairpin."))
    }

    @Test
    fun omitsExampleDialogueAfterEnoughHistory() {
        val character = CharacterEntity(
            id = "1",
            name = "Ganyu",
            exampleDialogue = "Ganyu: Hello example.",
        )
        val shortHistory = listOf(
            MessageEntity("1", "c", MessageRole.USER, "Hi"),
        )
        val longHistory = (1..8).map { index ->
            MessageEntity(
                id = "$index",
                conversationId = "c",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "Turn $index",
            )
        }
        val early = builder.build(
            character = character,
            history = shortHistory,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
        )
        val later = builder.build(
            character = character,
            history = longHistory,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
        )
        assertTrue(early.first().content.contains("Hello example."))
        assertFalse(later.first().content.contains("Hello example."))
    }

    @Test
    fun styleNoteOmitsOpeningsUnlessStrengthen() {
        val replies = listOf(
            "*She smiles softly* \"Hi.\"",
            "*He leans closer* \"Hello.\"",
        )
        val normal = builder.buildStyleNote(replies, strengthen = false)
        assertTrue(normal.contains("Vary sentence structure"))
        assertFalse(normal.contains("*She smiles softly*"))
        assertFalse(normal.contains("*He leans closer*"))

        val strong = builder.buildStyleNote(replies, strengthen = true)
        assertTrue(strong.contains("too similar"))
        assertTrue(strong.contains("*She smiles softly* \"Hi.\""))
        assertTrue(strong.contains("*He leans closer* \"Hello.\""))
    }

    @Test
    fun trimsOldestMessagesToFitBudget() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val history = (1..40).map { index ->
            MessageEntity(
                id = "$index",
                conversationId = "c",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "Message number $index with some padding text for tokens.",
            )
        }
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 512,
            maxTokens = 128,
        )
        assertEquals(MessageRole.SYSTEM, messages.first().role)
        assertTrue(messages.size < history.size + 1)
        assertTrue(messages.any { it.content.contains("Message number 40") })
    }

    @Test
    fun blockTrimSetsStableCutPoint() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val history = (1..30).map { index ->
            MessageEntity(
                id = "m$index",
                conversationId = "c",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "Message number $index with enough padding for token estimates here.",
            )
        }
        val first = builder.buildDetailed(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 700,
            maxTokens = 128,
        )
        assertNotNull(first.newSummaryUpToMessageId)
        assertTrue(first.droppedForSummary.isNotEmpty())

        val second = builder.buildDetailed(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 700,
            maxTokens = 128,
            summaryUpToMessageId = first.newSummaryUpToMessageId,
            summary = "Earlier events happened.",
        )
        // Same cut: no further trim while history after cut still fits soft budget.
        assertEquals(null, second.newSummaryUpToMessageId)
        assertTrue(second.messages.first().content.contains("Earlier events happened."))
    }

    @Test
    fun prependsUserWhenHistoryStartsWithAssistantGreeting() {
        val character = CharacterEntity(
            id = "1",
            name = "Ganyu",
            greeting = "Hello there.",
        )
        val history = listOf(
            MessageEntity("1", "c", MessageRole.ASSISTANT, "Hello there."),
            MessageEntity("2", "c", MessageRole.USER, "Hi!"),
        )
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
        )
        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
            ),
            messages.map { it.role },
        )
        assertEquals("(Begin the scene.)", messages[1].content)
        assertEquals("Ganyu: Hello there.", messages[2].content)
        assertTrue(messages[3].content.startsWith("Carlos: Hi!"))
    }

    @Test
    fun prefixesSpeakerNamesOnHistory() {
        val character = CharacterEntity(id = "1", name = "Madoka")
        val history = listOf(
            MessageEntity("1", "c", MessageRole.USER, "More intimately? how so?"),
            MessageEntity("2", "c", MessageRole.ASSISTANT, "*blushes* \"Oh...\""),
        )
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
        )
        assertEquals("Carlos: More intimately? how so?", messages[1].content)
        assertEquals("Madoka: *blushes* \"Oh...\"", messages[2].content)
    }

    @Test
    fun doesNotDoublePrefixSpeakerNames() {
        assertEquals(
            "Carlos: Hi",
            builder.withSpeakerPrefix("Carlos", "Carlos: Hi"),
        )
        assertEquals(
            "Madoka: Hello",
            builder.withSpeakerPrefix("Madoka", "Hello"),
        )
    }

    @Test
    fun stripsCopiedSpeakerPrefixFromModelOutput() {
        assertEquals(
            "*blushes*",
            builder.stripSpeakerPrefix("Madoka", "Madoka: *blushes*"),
        )
        assertEquals(
            "*blushes*",
            builder.stripSpeakerPrefix("Madoka", "*blushes*"),
        )
    }

    @Test
    fun continueModeAppendsHiddenUserTurnWhenLastIsAssistant() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val history = listOf(
            MessageEntity("1", "c", MessageRole.USER, "Hi"),
            MessageEntity("2", "c", MessageRole.ASSISTANT, "Hello there, friend."),
        )
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
            continueMode = true,
        )
        assertEquals(MessageRole.USER, messages.last().role)
        assertTrue(messages.last().content.contains("Continue your previous"))
        assertTrue(messages.last().content.contains("NOT a new answer"))
        assertTrue(messages.last().content.contains("Hello there, friend."))
        assertTrue(messages.last().content.startsWith("Carlos:"))
        assertFalse(messages.first().content.contains("CONTINUE your previous"))
    }

    @Test
    fun trimContinuationOverlapStripsEchoedTailAndRestart() {
        assertEquals(
            "and then she smiled softly.",
            builder.trimContinuationOverlap(
                "*She looked away shyly, brushing hair behind her ear*",
                "*She looked away shyly, brushing hair behind her ear* and then she smiled softly.",
            ),
        )
        assertEquals(
            "*His hand found hers gently.*",
            builder.trimContinuationOverlap(
                "He stepped closer until only a breath remained. \"Stay,\" he whispered.",
                "\"Stay,\" he whispered.\n\n*His hand found hers gently.*",
            ),
        )
    }

    @Test
    fun continueModeDoesNotInjectCueWhenLastIsUser() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val history = listOf(
            MessageEntity("1", "c", MessageRole.USER, "Hi"),
        )
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
            continueMode = true,
        )
        assertEquals(MessageRole.USER, messages.last().role)
        assertTrue(messages.last().content.contains("Carlos: Hi"))
    }

    @Test
    fun joinAssistantContinuationGluesMidSentence() {
        assertEquals(
            "Hello wor",
            builder.joinAssistantContinuation("Hello wor", ""),
        )
        assertEquals(
            "Hello world",
            builder.joinAssistantContinuation("Hello wor", "ld"),
        )
        assertEquals(
            "Done.\n\nNext beat.",
            builder.joinAssistantContinuation("Done.", "Next beat."),
        )
    }

    @Test
    fun mergesConsecutiveSameRoleMessages() {
        val normalized = builder.normalizeAlternatingRoles(
            listOf(
                ChatMessageDto(MessageRole.USER, "One"),
                ChatMessageDto(MessageRole.USER, "Two"),
                ChatMessageDto(MessageRole.ASSISTANT, "A"),
                ChatMessageDto(MessageRole.ASSISTANT, "B"),
            ),
        )
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
            ),
            normalized.map { it.role },
        )
        assertTrue(normalized[0].content.contains("One"))
        assertTrue(normalized[0].content.contains("Two"))
        assertTrue(normalized[1].content.contains("A"))
        assertTrue(normalized[1].content.contains("B"))
    }

    @Test
    fun regenerateAvoidTextsInjectsOocCue() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val history = listOf(
            MessageEntity("1", "c", MessageRole.USER, "Tell me a story"),
        )
        val rejected = "Once upon a time the exact same story began."
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
            avoidTexts = listOf(rejected),
        )
        assertEquals(MessageRole.USER, messages.last().role)
        assertTrue(messages.last().content.contains("Regenerate your last reply"))
        assertTrue(messages.last().content.contains(rejected))
        assertTrue(messages.last().content.contains("Avoid echoing"))
    }

    @Test
    fun revisePromptInjectsInstructionAndPreviousVariants() {
        val character = CharacterEntity(id = "1", name = "Ganyu")
        val history = listOf(
            MessageEntity("1", "c", MessageRole.USER, "Tell me a story"),
        )
        val previous = "A bland first draft of the reply."
        val messages = builder.build(
            character = character,
            history = history,
            persona = null,
            fallbackUserName = "Carlos",
            contextSize = 8192,
            maxTokens = 512,
            avoidTexts = listOf(previous),
            revisionInstruction = "Make this reply more suggestive",
        )
        assertEquals(MessageRole.USER, messages.last().role)
        assertTrue(messages.last().content.contains("Rewrite your last reply"))
        assertTrue(messages.last().content.contains("Make this reply more suggestive"))
        assertTrue(messages.last().content.contains(previous))
        assertTrue(messages.last().content.contains("Previous version"))
        assertTrue(!messages.last().content.contains("Regenerate your last reply"))
    }

    @Test
    fun generationParamsMapDryAndXtcFields() {
        val params = GenerationParams(
            temperature = 1.0f,
            topP = 1.0f,
            topK = 0,
            minP = 0.08f,
            repeatPenalty = 1.02f,
            maxTokens = 512,
            dryMultiplier = 0.8f,
            dryBase = 1.75f,
            dryAllowedLength = 2,
            dryPenaltyLastN = -1,
            xtcProbability = 0.5f,
            xtcThreshold = 0.1f,
            idSlot = 0,
        )
        val request = params.toRequest(
            model = "test-model",
            messages = listOf(ChatMessageDto(MessageRole.USER, "Hi")),
            stream = true,
        )
        assertEquals(0.8, request.dryMultiplier!!, 0.001)
        assertEquals(0.5, request.xtcProbability!!, 0.001)
        assertEquals(true, request.cachePrompt)
        assertEquals(null, request.dryPenaltyLastN)
        assertEquals(0, request.idSlot)
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(
            ChatCompletionRequest.serializer(),
            request,
        )
        assertTrue(json.contains("\"dry_multiplier\""))
        assertTrue(json.contains("\"xtc_probability\""))
        assertTrue(json.contains("\"cache_prompt\""))
        assertTrue(json.contains("\"id_slot\":0"))
        assertFalse(json.contains("\"dry_penalty_last_n\""))
    }

    @Test
    fun generationParamsOmitNullIdSlot() {
        val params = GenerationParams(
            temperature = 1.0f,
            topP = 1.0f,
            topK = 0,
            minP = 0.08f,
            repeatPenalty = 1.02f,
            maxTokens = 512,
            idSlot = null,
        )
        val request = params.toRequest(
            model = "test-model",
            messages = listOf(ChatMessageDto(MessageRole.USER, "Hi")),
            stream = false,
        )
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(ChatCompletionRequest.serializer(), request)
        assertFalse(json.contains("\"id_slot\""))
    }
}
