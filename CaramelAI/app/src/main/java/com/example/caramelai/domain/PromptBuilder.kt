package com.example.caramelai.domain

import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatMessageDto

data class PromptBuildResult(
    val messages: List<ChatMessageDto>,
    /** Message id that should become the new summary cut-point, if trimming occurred. */
    val newSummaryUpToMessageId: String? = null,
    /** Messages dropped from the prompt window that should be folded into the summary. */
    val droppedForSummary: List<MessageEntity> = emptyList(),
)

class PromptBuilder {
    fun build(
        character: CharacterEntity,
        history: List<MessageEntity>,
        persona: PersonaEntity?,
        fallbackUserName: String,
        contextSize: Int,
        maxTokens: Int,
        continueMode: Boolean = false,
        globalBehavior: String = "",
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        summary: String = "",
        pinnedMemory: String = "",
        summaryUpToMessageId: String? = null,
        strengthenStyleNote: Boolean = false,
    ): List<ChatMessageDto> = buildDetailed(
        character = character,
        history = history,
        persona = persona,
        fallbackUserName = fallbackUserName,
        contextSize = contextSize,
        maxTokens = maxTokens,
        continueMode = continueMode,
        globalBehavior = globalBehavior,
        avoidTexts = avoidTexts,
        revisionInstruction = revisionInstruction,
        summary = summary,
        pinnedMemory = pinnedMemory,
        summaryUpToMessageId = summaryUpToMessageId,
        strengthenStyleNote = strengthenStyleNote,
    ).messages

    fun buildDetailed(
        character: CharacterEntity,
        history: List<MessageEntity>,
        persona: PersonaEntity?,
        fallbackUserName: String,
        contextSize: Int,
        maxTokens: Int,
        continueMode: Boolean = false,
        globalBehavior: String = "",
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        summary: String = "",
        pinnedMemory: String = "",
        summaryUpToMessageId: String? = null,
        strengthenStyleNote: Boolean = false,
    ): PromptBuildResult {
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: fallbackUserName
        val includeExamples = history.size < EXAMPLE_DIALOGUE_MESSAGE_LIMIT
        val system = buildSystemPrompt(
            character = character,
            persona = persona,
            userName = userName,
            globalBehavior = globalBehavior,
            summary = summary,
            pinnedMemory = pinnedMemory,
            includeExampleDialogue = includeExamples,
        )
        val systemTokens = estimateTokens(system)
        val reservedForReply = maxTokens
        val remainingAfterSystem = contextSize - reservedForReply - systemTokens
        if (remainingAfterSystem < MIN_HISTORY_BUDGET) {
            error(
                "System prompt (~$systemTokens tokens) does not fit in context " +
                    "($contextSize) with maxTokens=$maxTokens. Shorten the character card or raise context.",
            )
        }

        val visibleHistory = historyAfterCut(history, summaryUpToMessageId)
        val recentAssistant = visibleHistory
            .asReversed()
            .filter { it.role == MessageRole.ASSISTANT }
            .take(3)
            .map { it.content }
            .reversed()

        val tailNotes = mutableListOf<String>()
        if (continueMode && visibleHistory.lastOrNull()?.role == MessageRole.ASSISTANT) {
            val previousReply = visibleHistory.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty()
            tailNotes += buildContinueCue(character.name, userName, previousReply)
        }
        val revision = revisionInstruction?.trim().orEmpty()
        if (revision.isNotEmpty()) {
            tailNotes += buildReviseCue(character.name, userName, revision, avoidTexts)
        } else if (avoidTexts.isNotEmpty()) {
            tailNotes += buildRegenerateAvoidCue(character.name, userName, avoidTexts)
        }
        tailNotes += buildStyleNote(recentAssistant, strengthen = strengthenStyleNote)
        tailNotes += buildTurnNote(character.name, userName)

        val notesCost = tailNotes.sumOf { estimateTokens(it) + 8 }
        val historyBudget = (remainingAfterSystem - notesCost)
            .coerceAtLeast(MIN_HISTORY_BUDGET)

        val selection = selectHistoryWithinBudget(
            history = visibleHistory,
            characterName = character.name,
            userName = userName,
            budget = historyBudget,
            softTargetRatio = HISTORY_SOFT_TARGET_RATIO,
        )

        val chatMessages = selection.kept.map { message ->
            val speaker = if (message.role == MessageRole.USER) userName else character.name
            val role = if (message.role == MessageRole.USER) MessageRole.USER else MessageRole.ASSISTANT
            val body = applyPlaceholders(message.content, character.name, userName)
            ChatMessageDto(
                role = role,
                content = withSpeakerPrefix(speaker, body),
            )
        }.toMutableList()

        for (note in tailNotes) {
            chatMessages += ChatMessageDto(
                role = MessageRole.USER,
                content = withSpeakerPrefix(userName, note),
            )
        }

        val systemMessage = ChatMessageDto(role = MessageRole.SYSTEM, content = system)
        return PromptBuildResult(
            messages = listOf(systemMessage) + normalizeAlternatingRoles(chatMessages),
            newSummaryUpToMessageId = selection.newCutMessageId,
            droppedForSummary = selection.dropped,
        )
    }

    data class HistorySelection(
        val kept: List<MessageEntity>,
        val dropped: List<MessageEntity>,
        val newCutMessageId: String?,
    )

    /**
     * Fills from newest to oldest. If over budget, trims down to [softTargetRatio] of budget
     * so the next turns can append without shifting the window every message.
     */
    fun selectHistoryWithinBudget(
        history: List<MessageEntity>,
        characterName: String,
        userName: String,
        budget: Int,
        softTargetRatio: Float = HISTORY_SOFT_TARGET_RATIO,
    ): HistorySelection {
        if (history.isEmpty()) {
            return HistorySelection(emptyList(), emptyList(), null)
        }
        val kept = mutableListOf<MessageEntity>()
        var used = 0
        var overflowed = false
        for (message in history.asReversed()) {
            if (message.role == MessageRole.SYSTEM) continue
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            val cost = messageTokenCost(message.content, speaker)
            if (used + cost > budget && kept.isNotEmpty()) {
                overflowed = true
                break
            }
            kept.add(0, message)
            used += cost
        }

        if (!overflowed) {
            return HistorySelection(kept, emptyList(), null)
        }

        val softBudget = (budget * softTargetRatio).toInt().coerceAtLeast(MIN_HISTORY_BUDGET)
        while (kept.size > 1 && used > softBudget) {
            val removed = kept.removeAt(0)
            val speaker = if (removed.role == MessageRole.USER) userName else characterName
            used -= messageTokenCost(removed.content, speaker)
        }

        val keptIds = kept.map { it.id }.toSet()
        val dropped = history.filter { it.id !in keptIds && it.role != MessageRole.SYSTEM }
        // Cut point = last dropped message id (everything after it is kept).
        val newCut = dropped.lastOrNull()?.id
        return HistorySelection(kept, dropped, newCut)
    }

    fun historyAfterCut(
        history: List<MessageEntity>,
        summaryUpToMessageId: String?,
    ): List<MessageEntity> {
        if (summaryUpToMessageId.isNullOrBlank()) return history
        val index = history.indexOfFirst { it.id == summaryUpToMessageId }
        if (index < 0) return history
        return history.drop(index + 1)
    }

    /** OOC cue so regenerations do not copy prior variants word-for-word. */
    fun buildRegenerateAvoidCue(
        characterName: String,
        userName: String,
        avoidTexts: List<String>,
    ): String {
        val last = avoidTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull()
            ?.take(MAX_AVOID_CHARS_PER_VARIANT)
            .orEmpty()
        val openings = avoidTexts
            .map { openingPhrase(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_AVOID_VARIANTS)
        return buildString {
            append("(OOC: Regenerate your last reply as $characterName. ")
            append("Write a fresh alternative with different wording, details, pacing, or angle, ")
            append("while staying in character and answering $userName's latest message. ")
            if (openings.isNotEmpty()) {
                append("Do not start like: ")
                append(openings.joinToString("; ") { "\"$it\"" })
                append(". ")
            }
            if (last.isNotEmpty()) {
                append("Avoid echoing this rejected draft:\n---\n")
                append(last)
                append("\n---)")
            } else {
                append(')')
            }
        }
    }

    /**
     * OOC cue for "skip my turn" / truncated replies: keep writing the same assistant
     * message instead of starting a fresh answer to the previous user turn.
     */
    fun buildContinueCue(
        characterName: String,
        userName: String,
        previousReply: String,
    ): String {
        val trimmed = previousReply.trimEnd()
        val incomplete = trimmed.isNotEmpty() && trimmed.last() !in COMPLETE_ENDINGS
        val tail = when {
            trimmed.isEmpty() -> ""
            trimmed.length <= MAX_CONTINUE_TAIL_CHARS -> trimmed
            else -> "…" + trimmed.takeLast(MAX_CONTINUE_TAIL_CHARS)
        }
        return buildString {
            append("(OOC: Continue your previous $characterName reply in-character. ")
            append("This is NOT a new turn and NOT a new answer to $userName. ")
            append("Do not restart from the beginning. Do not rephrase, summarize, or repeat ")
            append("what you already wrote. Do not re-ask or re-answer $userName's earlier message. ")
            if (incomplete) {
                append("Your previous text was cut off mid-sentence — resume exactly where it stopped. ")
            } else {
                append("Keep going with the next beat, action, or dialogue of the SAME reply. ")
            }
            append("Output ONLY the new continuation text. Do not speak as $userName.")
            if (tail.isNotEmpty()) {
                append(" Your previous reply ended with:\n\"\"\"\n")
                append(tail)
                append("\n\"\"\")")
            } else {
                append(')')
            }
        }
    }

    /**
     * OOC cue for prompt-guided rewrite: follow user instructions and treat prior
     * variants as feedback rather than the final answer.
     */
    fun buildReviseCue(
        characterName: String,
        userName: String,
        instruction: String,
        previousVariants: List<String>,
    ): String {
        val last = previousVariants
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull()
            ?.take(MAX_AVOID_CHARS_PER_VARIANT)
            .orEmpty()
        return buildString {
            append("(OOC: Rewrite your last reply as $characterName. ")
            append("Follow these instructions exactly: \"${instruction.trim()}\". ")
            append("Stay in character, answer $userName's latest message, and output only the new reply. ")
            if (last.isNotEmpty()) {
                append("Treat the previous version below as feedback: keep what still fits, ")
                append("change what the instructions ask for, and do not copy it word-for-word. ")
                append("Previous version:\n---\n$last\n---)")
            } else {
                append(')')
            }
        }
    }

    fun buildStyleNote(
        recentAssistantReplies: List<String>,
        strengthen: Boolean = false,
    ): String {
        val openings = if (strengthen) {
            recentAssistantReplies
                .map { openingPhrase(it) }
                .filter { it.isNotEmpty() }
                .distinct()
        } else {
            emptyList()
        }
        return buildString {
            append("(OOC: ")
            if (strengthen) {
                append("Your recent replies were too similar. Strongly vary sentence structure, ")
                append("pacing, and opening words. ")
            } else {
                append("Vary sentence structure and openings. ")
            }
            if (openings.isNotEmpty()) {
                append("Recent replies began with: ")
                append(openings.joinToString(", ") { "\"$it\"" })
                append(". Start differently. ")
            }
            append("Do not summarize the chat or repeat large parts of your last reply.)")
        }
    }

    fun buildTurnNote(characterName: String, userName: String): String =
        "(OOC: Reply ONLY as $characterName to $userName's LATEST message. " +
            "Never speak, act, think, or decide for $userName. " +
            "If $userName already answered or accepted something, advance the scene.)"

    fun openingPhrase(text: String, maxWords: Int = 5): String {
        val cleaned = text
            .trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
        if (cleaned.isEmpty()) return ""
        return cleaned.split(Regex("\\s+")).take(maxWords).joinToString(" ")
    }

    /** Glue a continuation onto a possibly mid-sentence truncated assistant reply. */
    fun joinAssistantContinuation(existing: String, addition: String): String {
        if (existing.isEmpty()) return addition
        if (addition.isEmpty()) return existing
        val cleaned = trimContinuationOverlap(existing, addition)
        if (cleaned.isEmpty()) return existing
        val endsComplete = existing.trimEnd().lastOrNull() in COMPLETE_ENDINGS
        return if (endsComplete && !existing.endsWith("\n") && !cleaned.startsWith("\n")) {
            existing.trimEnd() + "\n\n" + cleaned.trimStart()
        } else {
            existing.trimEnd() + cleaned
        }
    }

    /**
     * Drops echoed tails/restarts so "continue" does not paste the previous reply again.
     */
    fun trimContinuationOverlap(existing: String, addition: String): String {
        var cleaned = addition.trimStart()
        if (cleaned.isEmpty() || existing.isBlank()) return cleaned

        val previous = existing.trimEnd()
        val maxCheck = minOf(previous.length, cleaned.length, MAX_CONTINUE_OVERLAP_CHARS)

        // Model often echoes the ending we showed in the continue cue.
        for (len in maxCheck downTo MIN_CONTINUE_OVERLAP_CHARS) {
            if (cleaned.startsWith(previous.takeLast(len))) {
                cleaned = cleaned.substring(len).trimStart()
                break
            }
        }
        if (cleaned.isEmpty()) return cleaned

        // Full restart: continuation begins like the original reply.
        val prefixCheck = minOf(previous.length, cleaned.length, MAX_CONTINUE_RESTART_CHARS)
        for (len in prefixCheck downTo MIN_CONTINUE_RESTART_CHARS) {
            if (cleaned.startsWith(previous.take(len))) {
                cleaned = cleaned.substring(len).trimStart()
                break
            }
        }
        return cleaned
    }

    /**
     * Labels each turn so the model cannot confuse who said what.
     * Skips if the content already starts with "Name:".
     */
    fun withSpeakerPrefix(speaker: String, content: String): String {
        val trimmed = content.trimStart()
        if (trimmed.isEmpty()) return "$speaker: "
        val prefix = "$speaker:"
        if (trimmed.startsWith(prefix, ignoreCase = true)) return content
        return "$speaker: $content"
    }

    /** Removes a leading "Name:" the model may copy from labeled history. */
    fun stripSpeakerPrefix(speaker: String, content: String): String {
        val trimmed = content.trimStart()
        val prefix = "$speaker:"
        if (trimmed.startsWith(prefix, ignoreCase = true)) {
            return trimmed.substring(prefix.length).trimStart()
        }
        return content
    }

    /**
     * Makes history safe for llama-server --jinja with Mistral-style templates:
     * - merge consecutive same-role turns
     * - ensure the first turn after system is user
     */
    fun normalizeAlternatingRoles(messages: List<ChatMessageDto>): List<ChatMessageDto> {
        if (messages.isEmpty()) return emptyList()

        val merged = mutableListOf<ChatMessageDto>()
        for (message in messages) {
            val role = if (message.role == MessageRole.USER) MessageRole.USER else MessageRole.ASSISTANT
            val content = message.content
            val last = merged.lastOrNull()
            if (last != null && last.role == role) {
                merged[merged.lastIndex] = last.copy(
                    content = (last.content.trimEnd() + "\n\n" + content.trimStart()).trim(),
                )
            } else {
                merged += ChatMessageDto(role = role, content = content)
            }
        }

        if (merged.first().role == MessageRole.ASSISTANT) {
            merged.add(
                0,
                ChatMessageDto(
                    role = MessageRole.USER,
                    content = "(Begin the scene.)",
                ),
            )
        }

        return merged
    }

    fun buildSystemPrompt(
        character: CharacterEntity,
        persona: PersonaEntity?,
        userName: String,
        globalBehavior: String = "",
        summary: String = "",
        pinnedMemory: String = "",
        includeExampleDialogue: Boolean = true,
    ): String {
        val raw = buildString {
            if (character.systemPrompt.isNotBlank()) {
                appendLine(character.systemPrompt.trim())
                appendLine()
            }
            appendLine("You are ${character.name}. Stay in character.")
            if (character.description.isNotBlank()) {
                appendLine("Description: ${character.description.trim()}")
            }
            if (character.personality.isNotBlank()) {
                appendLine("Personality: ${character.personality.trim()}")
            }
            if (character.scenario.isNotBlank()) {
                appendLine("Scenario: ${character.scenario.trim()}")
            }
            if (includeExampleDialogue && character.exampleDialogue.isNotBlank()) {
                appendLine()
                appendLine("Example dialogue:")
                appendLine(character.exampleDialogue.trim())
            }
            appendLine()
            appendLine("The user is named $userName.")
            if (!persona?.description.isNullOrBlank()) {
                appendLine("User persona: ${persona!!.description.trim()}")
            }
            if (globalBehavior.isNotBlank()) {
                appendLine()
                appendLine("Global user preferences (apply to all chats):")
                appendLine(globalBehavior.trim())
            }
            if (pinnedMemory.isNotBlank()) {
                appendLine()
                appendLine("Key facts (always true; do not contradict):")
                appendLine(pinnedMemory.trim())
            }
            if (summary.isNotBlank()) {
                appendLine()
                appendLine("Story so far:")
                appendLine(summary.trim())
            }
            appendLine()
            appendLine("Turn-taking rules (critical):")
            appendLine("- Chat history lines are labeled \"$userName:\" (user) and \"${character.name}:\" (you).")
            appendLine("- Reply ONLY as ${character.name} to $userName's LATEST message.")
            appendLine("- Never speak, act, think, or decide for $userName.")
            appendLine("- Never attribute your own words, questions, or suggestions to $userName.")
            appendLine("- If $userName already answered or accepted something, advance the scene — do not re-ask the same thing.")
            appendLine("- Do not summarize the chat or repeat large parts of your last reply.")
            appendLine()
            appendLine("Roleplay formatting:")
            appendLine("- Put ALL actions and narration in *asterisks* (italic). Plain narration without asterisks is wrong.")
            appendLine("- Write spoken dialogue in \"quotation marks\".")
            appendLine("- Use **double asterisks** for emphasis when appropriate.")
            appendLine("- Mirror the user's formatting style when they use *, **, or quotes.")
            appendLine()
            appendLine("Write only ${character.name}'s next reply. Do not narrate for $userName.")
        }
        return applyPlaceholders(raw.trim(), character.name, userName)
    }

    /** Backwards-compatible helper used by older tests. */
    fun buildSystemPrompt(character: CharacterEntity, userName: String): String =
        buildSystemPrompt(character, persona = null, userName = userName, globalBehavior = "")

    private fun applyPlaceholders(text: String, charName: String, userName: String): String =
        text
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)
            .replace("<CHAR>", charName, ignoreCase = true)
            .replace("<USER>", userName, ignoreCase = true)

    fun messageTokenCost(content: String, speaker: String): Int =
        estimateTokens(withSpeakerPrefix(speaker, content)) + 8

    /** Rough token estimate for English/Spanish mixed RP text (conservative). */
    fun estimateTokens(text: String): Int {
        val raw = (text.length / 3.0).toInt().coerceAtLeast(1)
        return (raw * 1.08).toInt().coerceAtLeast(1)
    }

    private companion object {
        val COMPLETE_ENDINGS = setOf('.', '!', '?', '…', '"', '\u201d', '*')
        const val MAX_AVOID_VARIANTS = 3
        const val MAX_AVOID_CHARS_PER_VARIANT = 300
        const val MAX_CONTINUE_TAIL_CHARS = 280
        const val MAX_CONTINUE_OVERLAP_CHARS = 220
        const val MIN_CONTINUE_OVERLAP_CHARS = 16
        const val MAX_CONTINUE_RESTART_CHARS = 160
        const val MIN_CONTINUE_RESTART_CHARS = 24
        const val EXAMPLE_DIALOGUE_MESSAGE_LIMIT = 6
        const val MIN_HISTORY_BUDGET = 64
        const val HISTORY_SOFT_TARGET_RATIO = 0.65f
    }
}
