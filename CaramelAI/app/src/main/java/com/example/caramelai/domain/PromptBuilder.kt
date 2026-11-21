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
    /** True when the live recent window did not fit and was truncated for this prompt. */
    val recentTruncated: Boolean = false,
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
        softTargetRatio: Float = HISTORY_SOFT_TARGET_RATIO,
        continueRejectedOpening: String? = null,
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

        val continuing = continueMode &&
            visibleHistory.lastOrNull()?.role == MessageRole.ASSISTANT

        // Standing notes sit before the latest user message so the real reply
        // stays closest to generation. Action cues (continue/regen/revise) stay last.
        val standingNotes = mutableListOf<String>()
        standingNotes += buildStyleNote(recentAssistant, strengthen = strengthenStyleNote)
        if (continuing) {
            standingNotes += buildContinueTurnNote(character.name, userName)
        } else {
            standingNotes += buildTurnNote(character.name, userName)
        }

        val actionNotes = mutableListOf<String>()
        if (continuing) {
            actionNotes += buildContinueCue(
                characterName = character.name,
                userName = userName,
                rejectedOpening = continueRejectedOpening,
            )
        }
        if (avoidTexts.isNotEmpty() && revisionInstruction.isNullOrBlank()) {
            actionNotes += buildRegenerateAvoidCue(character.name, userName, avoidTexts)
        }
        val revision = revisionInstruction?.trim().orEmpty()
        if (revision.isNotEmpty()) {
            // Absolute last: instruction-following beats style/turn notes.
            actionNotes += buildReviseCue(character.name, userName, revision, avoidTexts)
        }

        val standingBlock = joinStandingNotes(character.name, standingNotes)
        val notesCost = estimateTokens(standingBlock) + 8 +
            actionNotes.sumOf { estimateTokens(it) + 8 }
        val historyBudget = (remainingAfterSystem - notesCost)
            .coerceAtLeast(MIN_HISTORY_BUDGET)

        val selection = selectHistoryWithinBudget(
            history = visibleHistory,
            characterName = character.name,
            userName = userName,
            budget = historyBudget,
            softTargetRatio = softTargetRatio,
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

        val standingDto = ChatMessageDto(role = MessageRole.USER, content = standingBlock)
        val lastUserIndex = chatMessages.indexOfLast { it.role == MessageRole.USER }
        // Only bury notes under the latest user turn when that turn is last in history.
        // If history ends on assistant, notes must trail so the prompt still ends on user.
        val insertBeforeUser = lastUserIndex >= 0 &&
            visibleHistory.lastOrNull()?.role == MessageRole.USER
        if (insertBeforeUser) {
            chatMessages.add(lastUserIndex, standingDto)
        } else {
            chatMessages += standingDto
        }
        for (note in actionNotes) {
            chatMessages += ChatMessageDto(role = MessageRole.USER, content = note)
        }

        val hasPriorContext = summary.isNotBlank() || !summaryUpToMessageId.isNullOrBlank()
        val systemMessage = ChatMessageDto(role = MessageRole.SYSTEM, content = system)
        return PromptBuildResult(
            messages = listOf(systemMessage) + normalizeAlternatingRoles(
                chatMessages,
                hasPriorContext = hasPriorContext,
            ),
            newSummaryUpToMessageId = selection.newCutMessageId,
            droppedForSummary = selection.dropped,
            recentTruncated = selection.recentTruncated,
        )
    }

    /**
     * True when [newCut] is strictly after [currentCut] in [history] order.
     * Missing ids are treated as not ahead (reject the update).
     */
    fun isCutAhead(
        history: List<MessageEntity>,
        currentCut: String?,
        newCut: String?,
    ): Boolean {
        if (newCut.isNullOrBlank()) return false
        val newIndex = history.indexOfFirst { it.id == newCut }
        if (newIndex < 0) return false
        if (currentCut.isNullOrBlank()) return true
        val currentIndex = history.indexOfFirst { it.id == currentCut }
        if (currentIndex < 0) return true
        return newIndex > currentIndex
    }

    data class PendingSummaryWork(
        val cutId: String?,
        val dropped: List<MessageEntity>,
    ) {
        val hasWork: Boolean get() = dropped.isNotEmpty() || !cutId.isNullOrBlank()
    }

    /**
     * Drops already behind [currentCut], or a [proposedCut] that is not ahead,
     * are cleared so callers do not re-schedule a no-op fold.
     */
    fun pendingSummaryWork(
        history: List<MessageEntity>,
        currentCut: String?,
        proposedCut: String?,
        dropped: List<MessageEntity>,
    ): PendingSummaryWork {
        if (dropped.isEmpty() && proposedCut.isNullOrBlank()) {
            return PendingSummaryWork(cutId = null, dropped = emptyList())
        }
        val visibleIds = historyAfterCut(history, currentCut).map { it.id }.toSet()
        val stillNeeded = dropped.filter { it.id in visibleIds }
        if (stillNeeded.isEmpty()) {
            return PendingSummaryWork(cutId = null, dropped = emptyList())
        }
        if (proposedCut != null && !isCutAhead(history, currentCut, proposedCut)) {
            return PendingSummaryWork(cutId = null, dropped = emptyList())
        }
        return PendingSummaryWork(cutId = proposedCut, dropped = stillNeeded)
    }

    /**
     * True when overflow is large enough to advance the summary cut / run an LLM fold.
     * Tiny overflows (1–2 turns past the recent window) must stay raw so we do not
     * fold the live scene every message and wipe current context into a lossy summary.
     */
    fun isSignificantFold(
        dropped: List<MessageEntity>,
        userName: String,
        characterName: String,
    ): Boolean {
        if (dropped.isEmpty()) return false
        if (dropped.size >= MIN_FOLD_MESSAGES) return true
        val tokens = dropped.sumOf { message ->
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            messageTokenCost(message.content, speaker)
        }
        return tokens >= MIN_FOLD_TOKENS
    }

    /**
     * After a cut exists, require a long enough live thread before folding again so
     * memory does not update on every few messages.
     */
    fun shouldAdvanceSummaryCut(
        history: List<MessageEntity>,
        currentCut: String?,
        dropped: List<MessageEntity>,
        userName: String,
        characterName: String,
    ): Boolean {
        if (!isSignificantFold(dropped, userName, characterName)) return false
        if (currentCut.isNullOrBlank()) return true
        val liveCount = historyAfterCut(history, currentCut)
            .count { it.role != MessageRole.SYSTEM }
        return liveCount >= MIN_MESSAGES_AFTER_CUT_BEFORE_REFOLD
    }

    data class HistorySelection(
        val kept: List<MessageEntity>,
        /** Hard-overflow only: safe to fold into long-term memory / advance the cut. */
        val dropped: List<MessageEntity>,
        val newCutMessageId: String?,
        /** True when the protected recent window itself did not fit the budget. */
        val recentTruncated: Boolean = false,
    )

    /**
     * Fills from newest to oldest. A recent window is never soft-trimmed so the
     * live thread stays in the prompt.
     *
     * Permanent [HistorySelection.dropped] / cut only reflect **hard** overflow
     * (what does not fit at ratio 1.0). [softTargetRatio] below 1.0 may omit extra
     * older turns from [HistorySelection.kept] for KV headroom without advancing
     * the summary cut.
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

        val recent = selectRecentWindow(history, characterName, userName, budget)
        val recentIds = recent.map { it.id }.toSet()
        var recentUsed = recent.sumOf { message ->
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            messageTokenCost(message.content, speaker)
        }
        // Recent floor does not fit: trim the prompt, but never fold live-thread
        // messages into memory. Only messages older than the recent window may cut.
        if (recentUsed > budget) {
            val fitted = mutableListOf<MessageEntity>()
            var used = 0
            for (message in recent.asReversed()) {
                val speaker = if (message.role == MessageRole.USER) userName else characterName
                val cost = messageTokenCost(message.content, speaker)
                if (used + cost > budget && fitted.isNotEmpty()) break
                fitted.add(0, message)
                used += cost
            }
            val olderDropped = history.filter {
                it.id !in recentIds && it.role != MessageRole.SYSTEM
            }
            return HistorySelection(
                kept = fitted,
                dropped = olderDropped,
                newCutMessageId = olderDropped.lastOrNull()?.id,
                recentTruncated = true,
            )
        }

        val older = history.filter { it.id !in recentIds && it.role != MessageRole.SYSTEM }
        val olderBudget = (budget - recentUsed).coerceAtLeast(0)
        val olderKept = mutableListOf<MessageEntity>()
        var olderUsed = 0
        var overflowed = false
        for (message in older.asReversed()) {
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            val cost = messageTokenCost(message.content, speaker)
            if (olderUsed + cost > olderBudget) {
                overflowed = true
                break
            }
            olderKept.add(0, message)
            olderUsed += cost
        }

        val hardKeptIds = (olderKept + recent).map { it.id }.toSet()
        val hardDropped = if (overflowed) {
            history.filter { it.id !in hardKeptIds && it.role != MessageRole.SYSTEM }.toMutableList()
        } else {
            mutableListOf()
        }

        // Soft-trim only affects this prompt's kept window — never the permanent cut.
        if (softTargetRatio < 1.0f) {
            val softBudget = (olderBudget * softTargetRatio).toInt().coerceAtLeast(0)
            while (olderKept.isNotEmpty() && olderUsed > softBudget) {
                val removed = olderKept.removeAt(0)
                val speaker = if (removed.role == MessageRole.USER) userName else characterName
                olderUsed -= messageTokenCost(removed.content, speaker)
            }
        }

        if (!overflowed && softTargetRatio >= 1.0f && olderKept.size == older.size) {
            return HistorySelection(olderKept + recent, emptyList(), null)
        }

        val aligned = preferUserLedWindow(olderKept + recent, recent.size, history)
        // Only fold alignment strips into permanent memory on hard overflow.
        // Soft-trim must never advance the summary cut.
        if (overflowed) {
            for (message in aligned.removed) {
                if (hardDropped.none { it.id == message.id }) {
                    hardDropped += message
                }
            }
            val keptIds = aligned.kept.map { it.id }.toSet()
            hardDropped.removeAll { it.id in keptIds }
            hardDropped.sortBy { msg -> history.indexOfFirst { it.id == msg.id } }
        }
        val hardCut = hardDropped.lastOrNull()?.id
        return HistorySelection(aligned.kept, hardDropped, hardCut)
    }

    data class UserLedAlignment(
        val kept: List<MessageEntity>,
        val removed: List<MessageEntity>,
    )

    /** Prefer starting the kept window on a user turn (never strip the recent floor). */
    private fun preferUserLedWindow(
        messages: List<MessageEntity>,
        recentSize: Int,
        history: List<MessageEntity>,
    ): UserLedAlignment {
        val kept = messages.toMutableList()
        val removed = mutableListOf<MessageEntity>()
        while (
            kept.size > recentSize &&
            kept.size > 1 &&
            kept.first().role == MessageRole.ASSISTANT
        ) {
            removed += kept.removeAt(0)
        }
        if (kept.isNotEmpty() && kept.first().role == MessageRole.ASSISTANT) {
            val firstId = kept.first().id
            val indexInHistory = history.indexOfFirst { it.id == firstId }
            if (indexInHistory > 0) {
                val previous = history[indexInHistory - 1]
                if (previous.role == MessageRole.USER && previous.id !in kept.map { it.id }) {
                    kept.add(0, previous)
                }
            }
        }
        return UserLedAlignment(kept, removed)
    }

    /** Newest messages that must stay in the prompt (live thread). */
    fun selectRecentWindow(
        history: List<MessageEntity>,
        characterName: String,
        userName: String,
        totalBudget: Int,
    ): List<MessageEntity> {
        val floor = RECENT_TOKEN_FLOOR.coerceAtMost((totalBudget * 0.55f).toInt().coerceAtLeast(MIN_HISTORY_BUDGET))
        val recent = mutableListOf<MessageEntity>()
        var used = 0
        for (message in history.asReversed()) {
            if (message.role == MessageRole.SYSTEM) continue
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            val cost = messageTokenCost(message.content, speaker)
            val hitFloor = used + cost > floor && recent.size >= MIN_RECENT_MESSAGES
            if (hitFloor) break
            recent.add(0, message)
            used += cost
            if (recent.size >= MAX_RECENT_MESSAGES && used >= floor) break
        }
        return recent
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
     * OOC cue for Continue: the user wants the bot to keep talking — a new
     * assistant message that carries the thread forward (not a mid-sentence patch).
     * The previous reply is already in history; we do not quote it (avoids echo).
     */
    fun buildContinueCue(
        characterName: String,
        userName: String,
        rejectedOpening: String? = null,
    ): String = buildString {
        append("(OOC: $userName wants you to KEEP TALKING as $characterName. ")
        append("Write a NEW in-character message that continues the same thread — ")
        append("develop the moment, add the next beat, action, feeling, or dialogue. ")
        append("Do not wait for $userName. Do not speak as $userName. ")
        append("Do not repeat, rephrase, or summarize your previous message; ")
        append("do not restart the scene or re-answer an older $userName line.")
        val rejected = rejectedOpening?.trim().orEmpty()
        if (rejected.isNotEmpty()) {
            append(" Do not start like: \"$rejected\".")
        }
        append(')')
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
            append("(OOC: PRIORITY — rewrite your last reply as $characterName. ")
            append("You MUST apply this change (it overrides style habits): \"${instruction.trim()}\". ")
            append("Stay in character. Answer $userName's latest message. Output ONLY the new reply. ")
            if (last.isNotEmpty()) {
                append("Previous version is feedback only — change what the instruction asks for; ")
                append("do not copy it word-for-word.\nPrevious:\n---\n$last\n---)")
            } else {
                append(')')
            }
        }
    }

    fun joinStandingNotes(characterName: String, notes: List<String>): String {
        val body = notes.joinToString(" ") { it.trim() }.trim()
        return "[Notes for $characterName: $body]"
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
        "(OOC: Continue as $characterName, reacting to $userName's last message. " +
            "$characterName makes their own choices and acts on them; " +
            "only $userName's own words and actions are off-limits.)"

    /** Standing note used instead of [buildTurnNote] when Continue follows an assistant turn. */
    fun buildContinueTurnNote(characterName: String, userName: String): String =
        "(OOC: Keep talking as $characterName — continue your own thread. " +
            "Do not re-answer an older $userName line. " +
            "$characterName makes their own choices and acts on them; " +
            "only $userName's own words and actions are off-limits.)"

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
     * Returns empty when a full restart is still mostly a copy of [existing].
     */
    fun trimContinuationOverlap(existing: String, addition: String): String {
        var cleaned = addition.trimStart()
        if (cleaned.isEmpty() || existing.isBlank()) return cleaned

        val previous = existing.trimEnd()
        val maxCheck = minOf(previous.length, cleaned.length, MAX_CONTINUE_OVERLAP_CHARS)

        // Model may echo the ending of the previous assistant message.
        for (len in maxCheck downTo MIN_CONTINUE_OVERLAP_CHARS) {
            if (cleaned.startsWith(previous.takeLast(len))) {
                cleaned = cleaned.substring(len).trimStart()
                break
            }
        }
        if (cleaned.isEmpty()) return cleaned

        // Full restart: continuation begins like the original reply.
        val prefixCheck = minOf(previous.length, cleaned.length, MAX_CONTINUE_RESTART_CHARS)
        var restarted = false
        val beforeRestartStrip = cleaned
        for (len in prefixCheck downTo MIN_CONTINUE_RESTART_CHARS) {
            if (cleaned.startsWith(previous.take(len))) {
                cleaned = cleaned.substring(len).trimStart()
                restarted = true
                break
            }
        }
        if (restarted &&
            RepetitionDetector.repetitionScore(beforeRestartStrip, listOf(previous)) >=
            RepetitionDetector.STRENGTHEN_THRESHOLD
        ) {
            return ""
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
    fun normalizeAlternatingRoles(
        messages: List<ChatMessageDto>,
        hasPriorContext: Boolean = false,
    ): List<ChatMessageDto> {
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
            val filler = if (hasPriorContext) {
                "(Continue the story.)"
            } else {
                "(Begin the scene.)"
            }
            merged.add(
                0,
                ChatMessageDto(
                    role = MessageRole.USER,
                    content = filler,
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
            appendLine()
            appendLine("Turn-taking rules (critical):")
            appendLine("- Chat history lines are labeled \"$userName:\" (user) and \"${character.name}:\" (you).")
            appendLine("- Reply only as ${character.name}; do not write $userName's dialogue, actions, or decisions.")
            appendLine("- ${character.name} drives the scene and ends replies on their own action or line, not by handing the decision back to $userName.")
            appendLine("- Do not summarize the chat or repeat large parts of your last reply.")
            appendLine()
            appendLine("Roleplay formatting:")
            appendLine("- Put ALL actions and narration in *asterisks* (italic). Plain narration without asterisks is wrong.")
            appendLine("- Write spoken dialogue in \"quotation marks\".")
            appendLine("- Use **double asterisks** for emphasis when appropriate.")
            appendLine("- Mirror the user's formatting style when they use *, **, or quotes.")
            // Preferences + memory near the end so long character cards do not bury them.
            if (globalBehavior.isNotBlank()) {
                appendLine()
                appendLine("USER PREFERENCES (follow every reply; tone/pacing/NSFW/SFW/style):")
                appendLine(globalBehavior.trim())
            }
            // Story summary late; live chat turns after this system message are the present.
            val cleanSummary = SummaryText.stripEphemeralSections(summary)
            if (cleanSummary.isNotBlank()) {
                appendLine()
                appendLine("Story so far:")
                appendLine("(BACKGROUND only — third-person memory, not the live exchange.)")
                appendLine(
                    "(If a \"${SummaryText.PRESENT_MARKER}\" block appears, it is the last " +
                        "memorized beat at the memory cut — still background. " +
                        "The labeled chat turns below are the live present.)",
                )
                appendLine(cleanSummary.trim())
                appendLine(
                    "(The labeled chat turns that follow are MORE RECENT than this summary. " +
                        "Reply to those turns. Never continue as if a line inside the summary " +
                        "were the latest user message.)",
                )
            }
            if (pinnedMemory.isNotBlank()) {
                appendLine()
                appendLine("KEY FACTS (absolute; never contradict; prefer these over older story details):")
                appendLine(pinnedMemory.trim())
            }
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

    companion object {
        const val HISTORY_SOFT_TARGET_RATIO = 0.65f
        /** Always keep at least this many newest non-system messages when possible. */
        const val MIN_RECENT_MESSAGES = 12
        const val MAX_RECENT_MESSAGES = 24
        /** Soft token floor for the live thread (~last several RP turns). */
        const val RECENT_TOKEN_FLOOR = 4000
        /**
         * Do not advance the summary cut / run a fold LLM until at least this many
         * messages (or [MIN_FOLD_TOKENS]) sit past the recent window.
         */
        const val MIN_FOLD_MESSAGES = 10
        const val MIN_FOLD_TOKENS = 2000
        /** After a cut exists, wait until the live thread is this long before refolding. */
        const val MIN_MESSAGES_AFTER_CUT_BEFORE_REFOLD = 16

        private val COMPLETE_ENDINGS = setOf('.', '!', '?', '…', '"', '\u201d', '*')
        private const val MAX_AVOID_VARIANTS = 3
        private const val MAX_AVOID_CHARS_PER_VARIANT = 300
        private const val MAX_CONTINUE_OVERLAP_CHARS = 220
        private const val MIN_CONTINUE_OVERLAP_CHARS = 16
        private const val MAX_CONTINUE_RESTART_CHARS = 160
        private const val MIN_CONTINUE_RESTART_CHARS = 24
        private const val EXAMPLE_DIALOGUE_MESSAGE_LIMIT = 6
        private const val MIN_HISTORY_BUDGET = 64
    }
}
