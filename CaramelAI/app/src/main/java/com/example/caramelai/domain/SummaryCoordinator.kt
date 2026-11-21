package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs conversation summaries on an app-scoped scope so chat generation
 * cancel/stop does not abort in-flight memory updates. One mutex per
 * conversation serializes folds and avoids duplicate work.
 *
 * While a fold is queued or running, a shadow cut hides already-pending drops
 * from the next prompt build so chatting mid-update does not re-schedule the
 * same overflow in a loop.
 */
class SummaryCoordinator(
    private val chatRepository: ChatRepository,
    private val promptBuilder: PromptBuilder,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    /** Farthest cut id we intend to apply; used as effective cut before DB lands. */
    private val shadowCuts = ConcurrentHashMap<String, String>()

    /** Messages waiting to be folded, keyed by conversation. */
    private val queued = ConcurrentHashMap<String, QueuedFold>()

    /** Work currently inside applySummaryUpdate (removed from [queued]). */
    private val inFlight = ConcurrentHashMap<String, QueuedFold>()

    /** Ensures only one drain loop runs per conversation. */
    private val draining = ConcurrentHashMap<String, Boolean>()

    private data class QueuedFold(
        val settings: AppSettings,
        val characterId: String,
        val cutId: String?,
        val dropped: List<MessageEntity>,
    )

    /**
     * Cut that prompt building should honor: DB cut, or a farther in-flight
     * shadow cut so mid-update chats do not rediscover the same overflow.
     */
    suspend fun effectiveCut(
        conversationId: String,
        history: List<MessageEntity>,
    ): String? {
        val dbCut = chatRepository.getSummaryCut(conversationId)
        val shadow = shadowCuts[conversationId] ?: return dbCut
        return if (promptBuilder.isCutAhead(history, dbCut, shadow)) shadow else dbCut
    }

    /**
     * Drops currently queued or being folded for [conversationId] (for provisional
     * summary text while the fold has not landed in DB yet).
     */
    fun pendingDropped(conversationId: String): List<MessageEntity> {
        val merged = LinkedHashMap<String, MessageEntity>()
        for (message in inFlight[conversationId]?.dropped.orEmpty()) {
            merged[message.id] = message
        }
        for (message in queued[conversationId]?.dropped.orEmpty()) {
            merged[message.id] = message
        }
        return merged.values.toList()
    }

    /**
     * Fold overflow into the summary now, under the per-conversation mutex.
     * Runs on the app scope so cancelling the caller's job (Stop) does not
     * abort the fold — only the await is cancelled.
     */
    suspend fun foldNow(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        cutId: String?,
        dropped: List<MessageEntity>,
    ): Boolean {
        if (dropped.isEmpty() && cutId == null) return true
        val mutex = locks.getOrPut(conversationId) { Mutex() }
        return scope.async {
            mutex.withLock {
                foldUnderLock(
                    conversationId = conversationId,
                    settings = settings,
                    characterId = characterId,
                    cutId = cutId,
                    dropped = dropped,
                ).also { ok ->
                    if (ok) clearShadowIfCovered(conversationId)
                }
            }
        }.await()
    }

    /**
     * Runs [block] under the same per-conversation mutex used by folds,
     * so dialog saves cannot race background summarization.
     */
    suspend fun <T> withConversationLock(
        conversationId: String,
        block: suspend () -> T,
    ): T {
        val mutex = locks.getOrPut(conversationId) { Mutex() }
        return scope.async {
            mutex.withLock { block() }
        }.await()
    }

    /**
     * Queue a background fold. Registers the shadow cut immediately and
     * coalesces with any already-queued work so chatting mid-update does not
     * stack duplicate LLM summary calls.
     */
    fun schedule(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        cutId: String?,
        dropped: List<MessageEntity>,
    ) {
        if (dropped.isEmpty() && cutId == null) return
        enqueue(conversationId, settings, characterId, cutId, dropped)
        startDrainIfNeeded(conversationId)
    }

    private fun enqueue(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        cutId: String?,
        dropped: List<MessageEntity>,
    ) {
        queued.compute(conversationId) { _, existing ->
            if (existing == null) {
                QueuedFold(settings, characterId, cutId, dropped)
            } else {
                val mergedById = LinkedHashMap<String, MessageEntity>()
                for (message in existing.dropped) mergedById[message.id] = message
                for (message in dropped) mergedById[message.id] = message
                QueuedFold(
                    settings = settings,
                    characterId = characterId,
                    // Prefer the newest proposed cut; pendingSummaryWork validates it.
                    cutId = cutId ?: existing.cutId,
                    dropped = mergedById.values.toList(),
                )
            }
        }
        val shadow = cutId ?: queued[conversationId]?.cutId
        if (shadow != null) {
            shadowCuts[conversationId] = shadow
        }
    }

    private fun startDrainIfNeeded(conversationId: String) {
        if (draining.putIfAbsent(conversationId, true) != null) return
        val mutex = locks.getOrPut(conversationId) { Mutex() }
        scope.launch {
            try {
                var attempt = 0
                while (true) {
                    val outcome = mutex.withLock {
                        val work = queued.remove(conversationId)
                            ?: return@withLock DrainOutcome.Idle
                        inFlight[conversationId] = work
                        val ok = try {
                            foldUnderLock(
                                conversationId = conversationId,
                                settings = work.settings,
                                characterId = work.characterId,
                                cutId = work.cutId,
                                dropped = work.dropped,
                            )
                        } finally {
                            inFlight.remove(conversationId)
                        }
                        if (ok) {
                            clearShadowIfCovered(conversationId)
                            DrainOutcome.Continue
                        } else if (attempt < MAX_RETRIES) {
                            enqueue(
                                conversationId,
                                work.settings,
                                work.characterId,
                                work.cutId,
                                work.dropped,
                            )
                            DrainOutcome.Retry
                        } else {
                            shadowCuts.remove(conversationId)
                            queued.remove(conversationId)
                            DrainOutcome.Idle
                        }
                    }
                    when (outcome) {
                        DrainOutcome.Idle -> break
                        DrainOutcome.Continue -> {
                            attempt = 0
                            // Loop: another enqueue may have landed during the fold.
                        }
                        DrainOutcome.Retry -> {
                            attempt++
                            delay(RETRY_BASE_DELAY_MS * attempt)
                        }
                    }
                }
            } finally {
                draining.remove(conversationId)
                // Race: schedule() after we decided Idle but before clearing draining.
                if (queued.containsKey(conversationId)) {
                    startDrainIfNeeded(conversationId)
                }
            }
        }
    }

    private enum class DrainOutcome { Idle, Continue, Retry }

    private suspend fun clearShadowIfCovered(conversationId: String) {
        val shadow = shadowCuts[conversationId] ?: return
        val history = chatRepository.getMessagesSnapshot(conversationId)
        val dbCut = chatRepository.getSummaryCut(conversationId)
        if (!promptBuilder.isCutAhead(history, dbCut, shadow)) {
            shadowCuts.remove(conversationId)
        }
    }

    private suspend fun foldUnderLock(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        cutId: String?,
        dropped: List<MessageEntity>,
    ): Boolean {
        val currentCut = chatRepository.getSummaryCut(conversationId)
        val history = chatRepository.getMessagesSnapshot(conversationId)
        val pending = promptBuilder.pendingSummaryWork(
            history = history,
            currentCut = currentCut,
            proposedCut = cutId,
            dropped = dropped,
        )
        if (!pending.hasWork) {
            // Already folded / nothing left — do not flash the busy indicator.
            return true
        }
        val foldCut = pending.cutId ?: pending.dropped.lastOrNull()?.id
        _active.update { it + conversationId }
        try {
            return chatRepository.applySummaryUpdate(
                conversationId = conversationId,
                settings = settings,
                characterId = characterId,
                newCutId = foldCut,
                dropped = pending.dropped,
            )
        } finally {
            _active.update { it - conversationId }
        }
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_BASE_DELAY_MS = 2_500L
    }
}
