package com.example.caramelai.di

import android.app.Application
import android.content.Context
import com.example.caramelai.data.db.AppDatabase
import com.example.caramelai.data.remote.LlmClient
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.CharacterRepository
import com.example.caramelai.domain.ChatRepository
import com.example.caramelai.domain.ConnectionChecker
import com.example.caramelai.domain.PersonaRepository
import com.example.caramelai.domain.PromptBuilder
import com.example.caramelai.domain.SummaryCoordinator

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val application: Application = appContext as Application

    val database: AppDatabase = AppDatabase.create(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val llmClient: LlmClient = LlmClient()
    val promptBuilder: PromptBuilder = PromptBuilder()

    val characterRepository: CharacterRepository = CharacterRepository(
        characterDao = database.characterDao(),
    )

    val personaRepository: PersonaRepository = PersonaRepository(
        personaDao = database.personaDao(),
    )

    val chatRepository: ChatRepository = ChatRepository(
        characterRepository = characterRepository,
        personaRepository = personaRepository,
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        llmClient = llmClient,
        promptBuilder = promptBuilder,
        database = database,
    )

    val summaryCoordinator: SummaryCoordinator = SummaryCoordinator(
        chatRepository = chatRepository,
        promptBuilder = promptBuilder,
    )

    val connectionChecker: ConnectionChecker = ConnectionChecker(
        settingsRepository = settingsRepository,
        llmClient = llmClient,
    )
}
