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
    ): List<ChatMessageDto> {
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: fallbackUserName
        val system = buildSystemPrompt(character, persona, userName, globalBehavior)
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
        // (truncation / "keep going"). If the last turn is the user, just answer it.
        if (continueMode && chatMessages.lastOrNull()?.role == MessageRole.ASSISTANT) {
            chatMessages += ChatMessageDto(
                role = MessageRole.USER,
                content = withSpeakerPrefix(
                    userName,
                    "(OOC: Continue your previous ${character.name} reply mid-sentence only. " +
                        "Do not restart, summarize, or re-ask. Do not speak as $userName.)",
                ),
            )
        }

        // Mistral Instruct Jinja requires: optional system, then user/assistant/user/assistant...
        return listOf(systemMessage) + normalizeAlternatingRoles(chatMessages)
    }

    /** Glue a continuation onto a possibly mid-sentence truncated assistant reply. */
    fun joinAssistantContinuation(existing: String, addition: String): String {
        if (existing.isEmpty()) return addition
        if (addition.isEmpty()) return existing
        val endsComplete = existing.trimEnd().lastOrNull() in COMPLETE_ENDINGS
        return if (endsComplete && !existing.endsWith("\n") && !addition.startsWith("\n")) {
            existing.trimEnd() + "\n\n" + addition.trimStart()
        } else {
            existing + addition
        }
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
            appendLine("- Reply ONLY as ${character.name} to $userName's LATEST message.")
            appendLine("- Never speak, act, think, or decide for $userName.")
            appendLine("- Never attribute your own words, questions, or suggestions to $userName.")
            appendLine("- If $userName already answered or accepted something, advance the scene — do not re-ask the same thing.")
            appendLine("- Do not continue or extend your previous message unless the user asked you to continue; write a fresh reply.")
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

    /** Rough token estimate for English/Spanish mixed RP text. */
    fun estimateTokens(text: String): Int =
        (text.length / 3.5).toInt().coerceAtLeast(1)
}
