package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs conversation summaries on an app-scoped scope so chat generation
 * cancel/stop does not abort in-flight memory updates. One mutex per
 * conversation serializes folds and avoids duplicate work.
 */
class SummaryCoordinator(
    private val chatRepository: ChatRepository,
    private val promptBuilder: PromptBuilder,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun schedule(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        cutId: String?,
        dropped: List<MessageEntity>,
    ) {
        if (dropped.isEmpty() && cutId == null) return
        val mutex = locks.getOrPut(conversationId) { Mutex() }
        scope.launch {
            mutex.withLock {
                val currentCut = chatRepository.getSummaryCut(conversationId)
                val history = chatRepository.getMessagesSnapshot(conversationId)
                val visible = promptBuilder.historyAfterCut(history, currentCut)
                val visibleIds = visible.map { it.id }.toSet()
                val stillNeeded = dropped.filter { it.id in visibleIds }
                if (stillNeeded.isEmpty() && (cutId == null || cutId == currentCut)) {
                    return@withLock
                }
                chatRepository.applySummaryUpdate(
                    conversationId = conversationId,
                    settings = settings,
                    characterId = characterId,
                    newCutId = cutId,
                    dropped = stillNeeded.ifEmpty { dropped },
                )
            }
        }
    }
}
