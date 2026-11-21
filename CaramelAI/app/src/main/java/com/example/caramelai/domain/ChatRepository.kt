package com.example.caramelai.domain

import androidx.room.withTransaction
import com.example.caramelai.data.db.AppDatabase
import com.example.caramelai.data.db.ConversationDao
import com.example.caramelai.data.db.ConversationEntity
import com.example.caramelai.data.db.MessageDao
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.MessageVariants
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatMessageDto
import com.example.caramelai.data.remote.ChatStreamEvent
import com.example.caramelai.data.remote.GenerationParams
import com.example.caramelai.data.remote.LlmClient
import com.example.caramelai.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

class ChatRepository(
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val database: AppDatabase,
) {
    fun observeConversations(characterId: String): Flow<List<ConversationEntity>> =
        conversationDao.observeByCharacter(characterId)

    fun observeConversation(id: String): Flow<ConversationEntity?> =
        conversationDao.observeById(id)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(conversationId)

    suspend fun createConversation(
        characterId: String,
        personaId: String? = null,
    ): ConversationEntity {
        val character = characterRepository.getById(characterId)
            ?: error("Character not found")
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            title = "Chat with ${character.name}",
            personaId = personaId,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.upsert(conversation)
        if (character.greeting.isNotBlank()) {
            messageDao.upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversation.id,
                    role = MessageRole.ASSISTANT,
                    content = character.greeting,
                    createdAt = now,
                ),
            )
        }
        return conversation
    }

    suspend fun findOrCreateLatestConversation(
        characterId: String,
        defaultPersonaId: String? = null,
    ): ConversationEntity {
        return conversationDao.getLatestForCharacter(characterId)
            ?: createConversation(characterId, defaultPersonaId)
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteById(id)
    }

    suspend fun setConversationPersona(conversationId: String, personaId: String?) {
        conversationDao.updatePersona(
            id = conversationId,
            personaId = personaId,
            updatedAt = System.currentTimeMillis(),
        )
    }

    sealed interface MemoryUpdateResult {
        data object Updated : MemoryUpdateResult
        data class Conflict(val current: ConversationEntity) : MemoryUpdateResult
    }

    /**
     * Compare-and-set memory write. Fails if [expectedSummary]/[expectedCut] no longer
     * match the row (e.g. a background fold landed while the dialog was open).
     * Blank [summary] also clears the cut so older turns become visible again.
     */
    suspend fun updateConversationMemory(
        conversationId: String,
        summary: String,
        pinnedMemory: String,
        expectedSummary: String,
        expectedCut: String?,
    ): MemoryUpdateResult = database.withTransaction {
        val current = conversationDao.getById(conversationId)
            ?: return@withTransaction MemoryUpdateResult.Conflict(
                ConversationEntity(
                    id = conversationId,
                    characterId = "",
                    title = "",
                ),
            )
        if (current.summary != expectedSummary || current.summaryUpToMessageId != expectedCut) {
            return@withTransaction MemoryUpdateResult.Conflict(current)
        }
        val trimmed = summary.trim()
        val pinned = pinnedMemory.trim()
        val now = System.currentTimeMillis()
        if (trimmed.isEmpty()) {
            conversationDao.updateMemoryAndCut(
                id = conversationId,
                summary = "",
                cutId = null,
                pinned = pinned,
                updatedAt = now,
            )
        } else {
            conversationDao.updateMemoryAndCut(
                id = conversationId,
                summary = trimmed,
                cutId = current.summaryUpToMessageId,
                pinned = pinned,
                updatedAt = now,
            )
        }
        MemoryUpdateResult.Updated
    }

    suspend fun addUserMessage(conversationId: String, content: String): MessageEntity {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content.trim(),
            createdAt = System.currentTimeMillis(),
        )
        messageDao.upsert(message)
        touchConversation(conversationId)
        return message
    }

    /**
     * Updates a message and removes every message that came after it so the
     * model never keeps answering a superseded branch of the chat.
     */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        content: String,
    ): MessageEntity? = database.withTransaction {
        val messages = messageDao.getByConversation(conversationId)
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return@withTransaction null
        val existing = messages[index]
        val trimmed = content.trim()
        val updated = if (existing.role == MessageRole.ASSISTANT) {
            val variants = MessageVariants.listOf(existing).toMutableList()
            val active = existing.activeVariantIndex.coerceIn(0, variants.lastIndex)
            variants[active] = trimmed
            MessageVariants.withActive(existing, variants, active)
        } else {
            MessageVariants.withActive(
                existing,
                variants = listOf(trimmed),
                activeIndex = 0,
            )
        }
        messageDao.update(updated)
        deleteMessagesFrom(messages, index + 1)
        invalidateSummaryIfNeeded(conversationId, messages, index)
        touchConversation(conversationId)
        updated
    }

    /**
     * Deletes [messageId] and every later message. Removing only a middle
     * message leaves a broken turn order that gets merged and makes the model
     * answer the wrong prompt.
     */
    suspend fun deleteMessage(conversationId: String, messageId: String) {
        database.withTransaction {
            val messages = messageDao.getByConversation(conversationId)
            val index = messages.indexOfFirst { it.id == messageId }
            if (index < 0) return@withTransaction
            invalidateSummaryIfNeeded(conversationId, messages, index)
            deleteMessagesFrom(messages, index)
            touchConversation(conversationId)
        }
    }

    suspend fun deleteLastAssistantMessage(conversationId: String): Boolean {
        val messages = messageDao.getByConversation(conversationId)
        val last = messages.lastOrNull() ?: return false
        if (last.role != MessageRole.ASSISTANT) return false
        messageDao.deleteById(last.id)
        touchConversation(conversationId)
        return true
    }

    suspend fun getLastMessage(conversationId: String): MessageEntity? =
        messageDao.getByConversation(conversationId).lastOrNull()

    suspend fun getMessagesSnapshot(conversationId: String): List<MessageEntity> =
        messageDao.getByConversation(conversationId)

    suspend fun getSummaryCut(conversationId: String): String? =
        conversationDao.getById(conversationId)?.summaryUpToMessageId

    suspend fun appendToAssistantMessage(
        conversationId: String,
        messageId: String,
        addition: String,
    ): MessageEntity? {
        val existing = messageDao.getById(messageId) ?: return null
        if (existing.conversationId != conversationId) return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val cleaned = sanitizeAssistantOutput(conversationId, addition)
        val joined = promptBuilder.joinAssistantContinuation(existing.content, cleaned)
        val variants = MessageVariants.listOf(existing).toMutableList()
        val index = existing.activeVariantIndex.coerceIn(0, variants.lastIndex)
        variants[index] = joined
        val updated = MessageVariants.withActive(existing, variants, index)
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    /** Appends a regenerated reply as a new variant and selects it. */
    suspend fun addAssistantVariant(
        conversationId: String,
        messageId: String,
        content: String,
    ): MessageEntity? {
        val existing = messageDao.getById(messageId) ?: return null
        if (existing.conversationId != conversationId) return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val cleaned = sanitizeAssistantOutput(conversationId, content)
        if (cleaned.isBlank()) return existing
        val variants = MessageVariants.listOf(existing) + cleaned
        val updated = MessageVariants.withActive(existing, variants, variants.lastIndex)
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    suspend fun setActiveVariant(
        conversationId: String,
        messageId: String,
        index: Int,
    ): MessageEntity? {
        val existing = messageDao.getById(messageId) ?: return null
        if (existing.conversationId != conversationId) return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val variants = MessageVariants.listOf(existing)
        if (variants.isEmpty()) return existing
        val updated = MessageVariants.withActive(existing, variants, index)
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    suspend fun branchConversation(
        sourceConversationId: String,
        upToMessageId: String,
    ): ConversationEntity = database.withTransaction {
        val source = conversationDao.getById(sourceConversationId)
            ?: error("Conversation not found")
        val messages = messageDao.getByConversation(sourceConversationId)
        val cutIndex = messages.indexOfFirst { it.id == upToMessageId }
        if (cutIndex < 0) error("Message not found")
        val kept = messages.take(cutIndex + 1)
        val now = System.currentTimeMillis()
        val idMap = kept.associate { it.id to UUID.randomUUID().toString() }
        val branchedSummaryCut = source.summaryUpToMessageId
            ?.takeIf { cutId -> kept.any { it.id == cutId } }
            ?.let { idMap[it] }
        // Cut after the branch point (or missing) would leave future facts in memory.
        val keepSummary = branchedSummaryCut != null && source.summary.isNotBlank()
        val branched = ConversationEntity(
            id = UUID.randomUUID().toString(),
            characterId = source.characterId,
            title = source.title + " (branch)",
            personaId = source.personaId,
            createdAt = now,
            updatedAt = now,
            summary = if (keepSummary) source.summary else "",
            summaryUpToMessageId = if (keepSummary) branchedSummaryCut else null,
            pinnedMemory = source.pinnedMemory,
            previousSummary = "",
        )
        conversationDao.upsert(branched)
        val copies = kept.mapIndexed { index, message ->
            message.copy(
                id = idMap[message.id] ?: UUID.randomUUID().toString(),
                conversationId = branched.id,
                createdAt = now + index,
            )
        }
        messageDao.upsertAll(copies)
        branched
    }

    data class StreamReplyPlan(
        val events: Flow<ChatStreamEvent>,
        val newSummaryUpToMessageId: String?,
        val droppedForSummary: List<MessageEntity>,
    )

    fun streamReply(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        continueMode: Boolean = false,
        excludeMessageId: String? = null,
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        strengthenStyleNote: Boolean = false,
        continueRejectedOpening: String? = null,
    ): Flow<ChatStreamEvent> = flow {
        val plan = prepareStreamReply(
            conversationId = conversationId,
            settings = settings,
            characterId = characterId,
            continueMode = continueMode,
            excludeMessageId = excludeMessageId,
            avoidTexts = avoidTexts,
            revisionInstruction = revisionInstruction,
            strengthenStyleNote = strengthenStyleNote,
            continueRejectedOpening = continueRejectedOpening,
        )
        plan.events.collect { emit(it) }
    }

    suspend fun prepareStreamReply(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        continueMode: Boolean = false,
        excludeMessageId: String? = null,
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        strengthenStyleNote: Boolean = false,
        continueRejectedOpening: String? = null,
        /**
         * In-flight / shadow cut from [SummaryCoordinator]. When set (and ahead of
         * the DB cut), history after this id is used so mid-update chats do not
         * rediscover the same overflow.
         */
        effectiveSummaryCut: String? = null,
        /** Drops already queued for fold — kept visible via provisional summary text. */
        inFlightDropped: List<MessageEntity> = emptyList(),
        fold: (suspend (
            conversationId: String,
            settings: AppSettings,
            characterId: String,
            cutId: String?,
            dropped: List<MessageEntity>,
        ) -> Boolean)? = null,
        onSummarizing: ((Boolean) -> Unit)? = null,
    ): StreamReplyPlan {
        val character = characterRepository.getById(characterId)
            ?: error("Character not found")
        val persona = resolvePersona(
            conversationDao.getById(conversationId)?.personaId,
            settings,
        )
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: settings.userName
        val history = messageDao.getByConversation(conversationId)
            .filter { excludeMessageId == null || it.id != excludeMessageId }

        // Hard overflow only (ratio 1.0) advances the summary cut. Soft-trim must not.
        var conversation = conversationDao.getById(conversationId)
        val dbCut = conversation?.summaryUpToMessageId
        val cutForBuild = when {
            effectiveSummaryCut != null &&
                promptBuilder.isCutAhead(history, dbCut, effectiveSummaryCut) ->
                effectiveSummaryCut
            else -> dbCut
        }
        val hadSummary = !conversation?.summary.isNullOrBlank() ||
            !dbCut.isNullOrBlank() ||
            cutForBuild != null
        // Regen / revise-by-prompt discard the current reply; they must not advance
        // the summary cut (avoid-texts would otherwise shrink the budget and "count").
        val isRewrite = excludeMessageId != null

        fun resolveCut(latestDbCut: String?): String? = when {
            cutForBuild != null &&
                promptBuilder.isCutAhead(history, latestDbCut, cutForBuild) -> cutForBuild
            else -> latestDbCut
        }

        fun summaryWithInFlight(base: String, latestDbCut: String?): String {
            val activeCut = resolveCut(latestDbCut)
            if (inFlightDropped.isEmpty() || activeCut == null || activeCut == latestDbCut) {
                return base
            }
            val pending = SummaryText.compactPendingTranscript(
                dropped = inFlightDropped,
                userName = userName,
                characterName = character.name,
            )
            return SummaryText.mergeProvisionalSummary(existing = base, pending = pending)
        }

        suspend fun rebuild(summaryOverride: String? = null): PromptBuildResult {
            val conv = conversationDao.getById(conversationId)
            conversation = conv
            val latestDbCut = conv?.summaryUpToMessageId
            val baseSummary = summaryOverride ?: conv?.summary.orEmpty()
            return promptBuilder.buildDetailed(
                character = character,
                history = history,
                persona = persona,
                fallbackUserName = settings.userName,
                contextSize = settings.effectiveContextSize(),
                maxTokens = settings.maxTokens,
                continueMode = continueMode,
                globalBehavior = settings.globalBehavior,
                avoidTexts = avoidTexts,
                revisionInstruction = revisionInstruction,
                summary = if (summaryOverride != null) {
                    baseSummary
                } else {
                    summaryWithInFlight(baseSummary, latestDbCut)
                },
                pinnedMemory = conv?.pinnedMemory.orEmpty(),
                summaryUpToMessageId = resolveCut(latestDbCut),
                strengthenStyleNote = strengthenStyleNote,
                softTargetRatio = 1.0f,
                continueRejectedOpening = continueRejectedOpening,
            )
        }

        var built: PromptBuildResult
        if (isRewrite) {
            built = rebuild().copy(
                newSummaryUpToMessageId = null,
                droppedForSummary = emptyList(),
            )
        } else {
            built = rebuild()

            // Hard overflow: keep a bridge in the system prompt, fold in the background
            // when warranted. Never block the reply on a sync summarizer (that made
            // chats feel much slower than before).
            if (built.droppedForSummary.isNotEmpty()) {
                val dropSnapshot = built.droppedForSummary
                val cutSnapshot = built.newSummaryUpToMessageId
                val latestDbCut = conversationDao.getById(conversationId)?.summaryUpToMessageId
                val shouldFold = promptBuilder.shouldAdvanceSummaryCut(
                    history = history,
                    currentCut = latestDbCut ?: cutForBuild,
                    dropped = dropSnapshot,
                    userName = userName,
                    characterName = character.name,
                )

                suspend fun withBridgeProvisional(
                    dropped: List<MessageEntity>,
                ): PromptBuildResult {
                    // Tail of dropped = closest to the live thread (what users notice).
                    val bridge = if (dropped.size <= 10) {
                        dropped
                    } else {
                        dropped.takeLast(10)
                    }
                    val pending = SummaryText.compactPendingTranscript(
                        dropped = bridge,
                        userName = userName,
                        characterName = character.name,
                    )
                    val provisional = SummaryText.mergeProvisionalSummary(
                        existing = conversationDao.getById(conversationId)?.summary.orEmpty(),
                        pending = pending,
                    )
                    return rebuild(summaryOverride = provisional)
                }

                val bridged = withBridgeProvisional(dropSnapshot)
                built = if (shouldFold) {
                    // Schedule background fold; shadow cut + provisional cover the gap.
                    bridged.copy(
                        newSummaryUpToMessageId = cutSnapshot,
                        droppedForSummary = dropSnapshot,
                    )
                } else {
                    // Not folding yet — still keep the bridge so context is not erased.
                    bridged.copy(
                        newSummaryUpToMessageId = null,
                        droppedForSummary = emptyList(),
                    )
                }
            }
        }

        val revising = !revisionInstruction.isNullOrBlank()
        val regenerating = avoidTexts.isNotEmpty() && !revising
        val params = GenerationParams(
            temperature = when {
                // Instruction-following needs lower entropy than free regen.
                revising -> (settings.temperature * 0.7f).coerceIn(0.45f, 0.85f)
                regenerating -> (settings.temperature + 0.15f).coerceAtMost(1.5f)
                else -> settings.temperature
            },
            topP = settings.topP,
            topK = settings.topK,
            minP = settings.minP,
            repeatPenalty = settings.repeatPenalty,
            maxTokens = settings.maxTokens,
            repeatLastN = settings.repeatLastN,
            dryMultiplier = when {
                revising -> (settings.dryMultiplier * 0.5f).coerceAtLeast(0f)
                regenerating -> (settings.dryMultiplier + 0.2f).coerceAtMost(2.0f)
                else -> settings.dryMultiplier
            },
            dryBase = settings.dryBase,
            dryAllowedLength = settings.dryAllowedLength,
            dryPenaltyLastN = settings.dryPenaltyLastN,
            xtcProbability = if (revising) {
                (settings.xtcProbability * 0.5f).coerceAtLeast(0f)
            } else {
                settings.xtcProbability
            },
            xtcThreshold = settings.xtcThreshold,
            idSlot = LlmClient.CHAT_SLOT,
        )
        val events = llmClient.streamChat(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = com.example.caramelai.data.remote.LlmModels.normalizeId(settings.modelId),
            messages = built.messages,
            params = params,
        )
        return StreamReplyPlan(
            events = events,
            newSummaryUpToMessageId = built.newSummaryUpToMessageId,
            droppedForSummary = built.droppedForSummary,
        )
    }

    suspend fun saveAssistantMessage(conversationId: String, content: String): MessageEntity {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = sanitizeAssistantOutput(conversationId, content),
            createdAt = System.currentTimeMillis(),
        )
        messageDao.upsert(message)
        touchConversation(conversationId)
        return message
    }

    /**
     * Saves a "skip my turn" reply as a new assistant message, stripping any
     * echoed overlap with the previous assistant text.
     */
    suspend fun saveContinuationMessage(
        conversationId: String,
        previousContent: String,
        content: String,
    ): MessageEntity? {
        val cleaned = sanitizeAssistantOutput(conversationId, content)
        val deduped = promptBuilder.trimContinuationOverlap(previousContent, cleaned)
        if (deduped.isBlank()) return null
        return saveAssistantMessage(conversationId, deduped)
    }

    suspend fun updatePinnedMemory(conversationId: String, pinnedMemory: String) {
        conversationDao.updatePinned(
            id = conversationId,
            pinned = pinnedMemory.trim(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Folds [dropped] into the conversation summary after a block trim.
     * Safe to call after the user-facing reply is already saved.
     * Returns false if summarization failed or the cut was invalidated mid-flight
     * (so the cut point is left untouched for a later retry).
     */
    suspend fun applySummaryUpdate(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        newCutId: String?,
        dropped: List<MessageEntity>,
    ): Boolean {
        if (dropped.isEmpty() && newCutId == null) return true
        val conversation = conversationDao.getById(conversationId) ?: return false
        val expectedCut = conversation.summaryUpToMessageId
        val history = messageDao.getByConversation(conversationId)
        if (newCutId != null && !promptBuilder.isCutAhead(history, expectedCut, newCutId)) {
            return false
        }
        val character = characterRepository.getById(characterId)
        val persona = resolvePersona(conversation.personaId, settings)
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: settings.userName
        val charName = character?.name ?: "Character"

        val mergedSummary = if (dropped.isEmpty()) {
            SummaryText.sanitize(conversation.summary)
        } else {
            // Closing beat feeds a dedicated present micro-pass — never persist raw dialogue.
            val closingBeat = SummaryText.closingBeatFromDropped(
                dropped = dropped,
                userName = userName,
                characterName = charName,
            )
            val folded = summarizeDropped(
                settings = settings,
                characterName = charName,
                userName = userName,
                previousSummary = SummaryText.stripPresentClosing(
                    SummaryText.stripForPersistence(conversation.summary),
                ),
                dropped = dropped,
                closingBeat = closingBeat,
            ) ?: return false
            val present = summarizePresentBeat(settings, closingBeat) ?: return false
            val withPresent = SummaryText.ensurePresentClosing(folded, present)
            // ensurePresentClosing drops dialogue-looking present; treat as fold failure.
            if (SummaryText.extractPresentClosing(withPresent).isNullOrBlank()) return false
            finalizeSummaryText(settings, withPresent)
        }

        // Re-read: discard if edit/delete or a manual memory edit raced us.
        val fresh = conversationDao.getById(conversationId) ?: return false
        if (fresh.summaryUpToMessageId != expectedCut) return false
        if (fresh.summary != conversation.summary) return false
        if (newCutId != null) {
            val stillExists = messageDao.getById(newCutId) != null
            if (!stillExists) return false
            val freshHistory = messageDao.getByConversation(conversationId)
            if (!promptBuilder.isCutAhead(freshHistory, fresh.summaryUpToMessageId, newCutId)) {
                return false
            }
        }

        conversationDao.updateSummary(
            id = conversationId,
            summary = mergedSummary,
            cutId = newCutId ?: fresh.summaryUpToMessageId,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    private suspend fun finalizeSummaryText(settings: AppSettings, text: String): String {
        val present = SummaryText.extractPresentClosing(text)
        val body = SummaryText.stripPresentClosing(text)
        var result = SummaryText.sanitize(body)
        if (result.length > SummaryText.MAX_SUMMARY_CHARS) {
            val condensed = condenseSummary(settings, result)
            result = if (condensed != null) {
                SummaryText.sanitize(condensed)
            } else {
                SummaryText.sanitize(result)
            }
        }
        return if (present.isNullOrBlank()) {
            result
        } else {
            SummaryText.ensurePresentClosing(result, present)
        }
    }

    /**
     * Tiny third-person beat for the fold cut. Returns null if the model fails,
     * truncates, or emits labeled dialogue (which must never be persisted).
     */
    private suspend fun summarizePresentBeat(
        settings: AppSettings,
        closingBeat: String,
    ): String? {
        if (closingBeat.isBlank()) return null
        val prompt = buildString {
            appendLine("Write ONLY 2–4 sentences of third-person past-tense story memory.")
            appendLine("Cover who is where, what just happened, and what is still open.")
            appendLine("No dialogue, no speaker labels, no quotes, no markdown.")
            appendLine("Finish every sentence.")
            appendLine()
            appendLine("Latest beats to paraphrase:")
            appendLine(closingBeat.trim())
            appendLine()
            appendLine("Current situation:")
        }
        val result = runSummaryCompletion(settings, prompt, maxTokens = 150) ?: return null
        if (result.finishReason.equals("length", ignoreCase = true)) return null
        val text = result.text.trim()
        if (text.isBlank() || SummaryText.looksLikeDialogue(text)) return null
        // Drop accidental marker echo from the model.
        return text.removePrefix(SummaryText.PRESENT_MARKER).trim()
            .takeIf { it.isNotBlank() && !SummaryText.looksLikeDialogue(it) }
    }

    private suspend fun summarizeDropped(
        settings: AppSettings,
        characterName: String,
        userName: String,
        previousSummary: String,
        dropped: List<MessageEntity>,
        closingBeat: String = "",
    ): String? {
        // One LLM call, transcript = newest dropped beats (tail). Faster than multi-chunk
        // folds and forces the summary to end on what was just before the live thread.
        val transcript = SummaryText.compactPendingTranscript(
            dropped = dropped,
            userName = userName,
            characterName = characterName,
            maxChars = SummaryText.SUMMARIZER_TRANSCRIPT_CHARS,
        )
        if (transcript.isBlank()) return previousSummary.trim().takeIf { it.isNotBlank() }
        return summarizeTranscript(
            settings = settings,
            previousSummary = previousSummary,
            transcript = transcript,
            closingBeat = closingBeat,
            wordLimit = 220,
        )
    }

    private suspend fun summarizeTranscript(
        settings: AppSettings,
        previousSummary: String,
        transcript: String,
        closingBeat: String = "",
        wordLimit: Int = 220,
    ): String? {
        val prompt = buildSummaryPrompt(previousSummary, transcript, wordLimit, closingBeat)
        val completion = runSummaryCompletion(settings, prompt, maxTokens = 700) ?: return null
        if (!completion.finishReason.equals("length", ignoreCase = true)) {
            return completion.text
        }
        val condensedPrompt = buildSummaryPrompt(
            previousSummary,
            transcript,
            wordLimit = 140,
            closingBeat = closingBeat,
        )
        val retry = runSummaryCompletion(settings, condensedPrompt, maxTokens = 450) ?: return null
        if (retry.finishReason.equals("length", ignoreCase = true)) return null
        return retry.text
    }

    private fun buildSummaryPrompt(
        previousSummary: String,
        transcript: String,
        wordLimit: Int,
        closingBeat: String = "",
    ): String = buildString {
        appendLine("You are a concise story memory writer for a roleplay chat.")
        appendLine("Write ONE updated summary in third person, past tense. No quoted dialogue.")
        appendLine("Brief background first, then end on the CURRENT situation from the NEW events.")
        appendLine("The FINAL 2–4 sentences MUST say who is where, what just happened, and what is open.")
        appendLine("Keep names, relationships, locations, promises still relevant now.")
        appendLine("Aim for at most $wordLimit words. Finish every sentence.")
        appendLine()
        // Previous first so NEW events sit closest to generation (less background bias).
        if (previousSummary.isNotBlank()) {
            appendLine("Previous summary (background only — shorten or drop stale parts):")
            appendLine(previousSummary.trim())
            appendLine()
        }
        appendLine("NEW events (newest last — the summary MUST end on the latest of these):")
        appendLine(transcript.trim())
        appendLine()
        if (closingBeat.isNotBlank()) {
            appendLine("Latest beats to cover in the FINAL sentences (paraphrase, do not quote as chat):")
            appendLine(closingBeat.trim())
            appendLine()
        }
        appendLine("Updated summary:")
    }

    private suspend fun condenseSummary(settings: AppSettings, summary: String): String? {
        val prompt = buildString {
            appendLine("Condense this roleplay story summary.")
            appendLine("Keep names, relationships, locations, promises, and current plot beats.")
            appendLine("If you must shorten, drop the OLDEST background first.")
            appendLine("Never drop the CURRENT situation — the last sentences must stay.")
            appendLine("End on the current situation. Write in third person, past tense.")
            appendLine("Finish every sentence. No dialogue.")
            appendLine("Aim for at most 150 words.")
            appendLine()
            appendLine(summary.trim())
            appendLine()
            appendLine("Condensed summary:")
        }
        val result = runSummaryCompletion(settings, prompt, maxTokens = 500) ?: return null
        if (result.finishReason.equals("length", ignoreCase = true)) return null
        return result.text
    }

    /**
     * Shrinks a bloated conversation summary in place so the live recent window
     * can fit after a sync fold. Keeps the existing cut point.
     */
    private suspend fun tryCondenseConversationSummary(
        conversationId: String,
        settings: AppSettings,
    ): Boolean {
        val conversation = conversationDao.getById(conversationId) ?: return false
        if (conversation.summary.isBlank()) return false
        val expectedSummary = conversation.summary
        val expectedCut = conversation.summaryUpToMessageId
        val present = SummaryText.extractPresentClosing(conversation.summary)
        val body = SummaryText.stripPresentClosing(conversation.summary)
        val condensed = condenseSummary(settings, body) ?: return false
        val sanitizedBody = SummaryText.sanitize(condensed)
        val sanitized = if (present.isNullOrBlank()) {
            sanitizedBody
        } else {
            SummaryText.ensurePresentClosing(sanitizedBody, present)
        }
        if (sanitized.isBlank() || sanitized.length >= conversation.summary.length) return false
        val fresh = conversationDao.getById(conversationId) ?: return false
        if (fresh.summary != expectedSummary || fresh.summaryUpToMessageId != expectedCut) {
            return false
        }
        conversationDao.updateSummary(
            id = conversationId,
            summary = sanitized,
            cutId = expectedCut,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    private suspend fun runSummaryCompletion(
        settings: AppSettings,
        prompt: String,
        maxTokens: Int,
    ): com.example.caramelai.data.remote.CompletionResult? {
        val messages = listOf(
            ChatMessageDto(role = MessageRole.USER, content = prompt),
        )
        val params = GenerationParams(
            temperature = 0.3f,
            topP = 1.0f,
            topK = 0,
            minP = 0.05f,
            repeatPenalty = 1.0f,
            maxTokens = maxTokens,
            dryMultiplier = 0f,
            xtcProbability = 0f,
            idSlot = LlmClient.SUMMARY_SLOT,
        )
        return llmClient.completeDetailed(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = com.example.caramelai.data.remote.LlmModels.normalizeId(settings.modelId),
            messages = messages,
            params = params,
        ).getOrNull()?.takeIf { it.text.isNotBlank() }
    }

    private suspend fun invalidateSummaryIfNeeded(
        conversationId: String,
        messages: List<MessageEntity>,
        affectedIndex: Int,
    ) {
        val conversation = conversationDao.getById(conversationId) ?: return
        val cutId = conversation.summaryUpToMessageId ?: return
        val cutIndex = messages.indexOfFirst { it.id == cutId }
        if (cutIndex < 0 || affectedIndex <= cutIndex) {
            conversationDao.clearSummarySavingPrevious(
                id = conversationId,
                previousSummary = conversation.summary,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun sanitizeAssistantOutput(conversationId: String, content: String): String {
        val conversation = conversationDao.getById(conversationId) ?: return content
        val character = characterRepository.getById(conversation.characterId) ?: return content
        return promptBuilder.stripSpeakerPrefix(character.name, content)
    }

    private suspend fun deleteMessagesFrom(messages: List<MessageEntity>, fromIndex: Int) {
        if (fromIndex >= messages.size) return
        val fromCreatedAt = messages[fromIndex].createdAt
        val conversationId = messages[fromIndex].conversationId
        messageDao.deleteFrom(conversationId, fromCreatedAt)
    }

    private suspend fun resolvePersona(
        conversationPersonaId: String?,
        settings: AppSettings,
    ): PersonaEntity? {
        val id = conversationPersonaId ?: settings.defaultPersonaId ?: return null
        return personaRepository.getById(id)
    }

    private suspend fun touchConversation(conversationId: String) {
        conversationDao.touch(conversationId, System.currentTimeMillis())
    }

    companion object {
        const val MAX_SUMMARY_CHARS = SummaryText.MAX_SUMMARY_CHARS
        const val PENDING_TRANSCRIPT_CHARS = SummaryText.PENDING_TRANSCRIPT_CHARS
    }
}
