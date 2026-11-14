package com.example.caramelai.domain

import com.example.caramelai.data.remote.LlmClient
import com.example.caramelai.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

sealed interface ConnectionResult {
    data class Ok(val detail: String) : ConnectionResult
    data class Failed(val message: String) : ConnectionResult
}

class ConnectionChecker(
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient,
) {
    suspend fun check(): ConnectionResult {
        val settings = settingsRepository.settings.first()
        return llmClient.health(settings.baseUrl, settings.apiKey).fold(
            onSuccess = { detail ->
                llmClient.fetchServerContext(settings.baseUrl, settings.apiKey)
                    .onSuccess { nCtx ->
                        settingsRepository.update { it.copy(serverContextSize = nCtx) }
                    }
                ConnectionResult.Ok(detail)
            },
            onFailure = { ConnectionResult.Failed(it.message ?: "Connection failed") },
        )
    }
}
