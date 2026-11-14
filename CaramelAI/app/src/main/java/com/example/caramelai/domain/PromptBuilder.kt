package com.example.caramelai.domain

import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatMessageDto

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
    ): List<ChatMessageDto> {
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: fallbackUserName
        val system = buildSystemPrompt(
            character = character,
            persona = persona,
            userName = userName,
            globalBehavior = globalBehavior,
            continueMode = continueMode,
        )
        val systemMessage = ChatMessageDto(role = MessageRole.SYSTEM, content = system)
        val budget = (contextSize - maxTokens - estimateTokens(system)).coerceAtLeast(256)

        val kept = mutableListOf<MessageEntity>()
        var used = 0
        for (message in history.asReversed()) {
            if (message.role == MessageRole.SYSTEM) continue
            val cost = estimateTokens(message.content) + 8
            if (used + cost > budget && kept.isNotEmpty()) break
            kept.add(0, message)
            used += cost
        }

        val chatMessages = kept.map { message ->
            val speaker = if (message.role == MessageRole.USER) userName else character.name
            val role = if (message.role == MessageRole.USER) MessageRole.USER else MessageRole.ASSISTANT
            val body = applyPlaceholders(message.content, character.name, userName)
            ChatMessageDto(
                role = role,
                content = withSpeakerPrefix(speaker, body),
            )
        }.toMutableList()

        // Only inject a continue cue when the last turn is already the assistant
        // (truncation / "skip my turn"). If the last turn is the user, just answer it.
        if (continueMode && chatMessages.lastOrNull()?.role == MessageRole.ASSISTANT) {
            val previousReply = kept.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty()
            chatMessages += ChatMessageDto(
                role = MessageRole.USER,
                content = withSpeakerPrefix(
                    userName,
                    buildContinueCue(character.name, userName, previousReply),
                ),
            )
        }

        val revision = revisionInstruction?.trim().orEmpty()
        if (revision.isNotEmpty()) {
            chatMessages += ChatMessageDto(
                role = MessageRole.USER,
                content = withSpeakerPrefix(
                    userName,
                    buildReviseCue(character.name, userName, revision, avoidTexts),
                ),
            )
        } else if (avoidTexts.isNotEmpty()) {
            chatMessages += ChatMessageDto(
                role = MessageRole.USER,
                content = withSpeakerPrefix(
                    userName,
                    buildRegenerateAvoidCue(character.name, userName, avoidTexts),
                ),
            )
        }

        // Mistral Instruct Jinja requires: optional system, then user/assistant/user/assistant...
        return listOf(systemMessage) + normalizeAlternatingRoles(chatMessages)
    }

    /** OOC cue so regenerations do not copy prior variants word-for-word. */
    fun buildRegenerateAvoidCue(
        characterName: String,
        userName: String,
        avoidTexts: List<String>,
    ): String {
        val rejected = formatVariantFeedback(avoidTexts)
        return "(OOC: Regenerate your last reply as $characterName. " +
            "Do NOT repeat the rejected replies below word-for-word. " +
            "Write a fresh alternative: different wording, details, pacing, or angle, " +
            "while staying in character and answering $userName's latest message. " +
            "Rejected replies:\n---\n$rejected\n---)"
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
        val feedback = formatVariantFeedback(previousVariants)
        val body = buildString {
            append("(OOC: Rewrite your last reply as $characterName. ")
            append("Follow these instructions exactly: \"${instruction.trim()}\". ")
            append("Stay in character, answer $userName's latest message, and output only the new reply. ")
            if (feedback.isNotEmpty()) {
                append("Treat the previous versions below as feedback: keep what still fits, ")
                append("change what the instructions ask for, and do not copy them word-for-word. ")
                append("Previous versions:\n---\n$feedback\n---)")
            } else {
                append(')')
            }
        }
        return body
    }

    private fun formatVariantFeedback(texts: List<String>): String =
        texts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(MAX_AVOID_VARIANTS)
            .joinToString("\n---\n") { it.take(MAX_AVOID_CHARS_PER_VARIANT) }

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

    private companion object {
        val COMPLETE_ENDINGS = setOf('.', '!', '?', '…', '"', '\u201d', '*')
        const val MAX_AVOID_VARIANTS = 3
        const val MAX_AVOID_CHARS_PER_VARIANT = 900
        const val MAX_CONTINUE_TAIL_CHARS = 280
        const val MAX_CONTINUE_OVERLAP_CHARS = 220
        const val MIN_CONTINUE_OVERLAP_CHARS = 16
        const val MAX_CONTINUE_RESTART_CHARS = 160
        const val MIN_CONTINUE_RESTART_CHARS = 24
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
        continueMode: Boolean = false,
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
            if (character.exampleDialogue.isNotBlank()) {
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
            appendLine()
            appendLine("Turn-taking rules (critical):")
            appendLine("- Chat history lines are labeled \"$userName:\" (user) and \"${character.name}:\" (you).")
            if (continueMode) {
                appendLine("- $userName is skipping their turn so you CONTINUE your previous ${character.name} reply.")
                appendLine("- Do not start a new answer to an older $userName message.")
                appendLine("- Do not restart or repeat your previous reply; only write the continuation.")
            } else {
                appendLine("- Reply ONLY as ${character.name} to $userName's LATEST message.")
                appendLine("- Do not continue or extend your previous message unless the user asked you to continue; write a fresh reply.")
            }
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
            if (continueMode) {
                appendLine("Write only the continuation of ${character.name}'s previous reply. Do not narrate for $userName.")
            } else {
                appendLine("Write only ${character.name}'s next reply. Do not narrate for $userName.")
            }
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

    /** Rough token estimate for English/Spanish mixed RP text. */
    fun estimateTokens(text: String): Int =
        (text.length / 3.5).toInt().coerceAtLeast(1)
}
