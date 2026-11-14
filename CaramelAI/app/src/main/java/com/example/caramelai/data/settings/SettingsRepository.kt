package com.example.caramelai.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.caramelai.data.remote.LlmModels
import com.example.caramelai.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "caramel_settings",
)

enum class BotListLayout {
    GRID,
    LIST,
}

data class AppSettings(
    val baseUrl: String = "",
    val apiKey: String = "caramel-local-key",
    /** GGUF id sent as OpenAI `model` (must exist under llama-server --models-dir). */
    val modelId: String = LlmModels.defaultId,
    /** App UI language; English is the default. */
    val language: AppLanguage = AppLanguage.English,
    val userName: String = "User",
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val topK: Int = 0,
    val minP: Float = 0.08f,
    val repeatPenalty: Float = 1.02f,
    val dryMultiplier: Float = 0.8f,
    val dryBase: Float = 1.75f,
    val dryAllowedLength: Int = 2,
    val dryPenaltyLastN: Int = -1,
    val xtcProbability: Float = 0.5f,
    val xtcThreshold: Float = 0.1f,
    val repeatLastN: Int = 64,
    val maxTokens: Int = 512,
    val contextSize: Int = 8192,
    /** Last known server context size from GET /props; null if unknown. */
    val serverContextSize: Int? = null,
    val globalBehavior: String = "",
    val theme: AppTheme = AppTheme.Dark,
    val botListLayout: BotListLayout = BotListLayout.GRID,
    val defaultPersonaId: String? = null,
) {
    /** Effective context used for prompt budgeting. */
    fun effectiveContextSize(): Int {
        val server = serverContextSize
        return if (server != null && server > 0) minOf(contextSize, server) else contextSize
    }

    companion object {
        fun defaults() = AppSettings()
    }
}

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        prefs.toSettings()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.BASE_URL] = next.baseUrl
            prefs[Keys.API_KEY] = next.apiKey
            prefs[Keys.MODEL_ID] = LlmModels.normalizeId(next.modelId)
            prefs[Keys.LANGUAGE] = next.language.tag
            prefs[Keys.USER_NAME] = next.userName
            prefs[Keys.TEMPERATURE] = next.temperature
            prefs[Keys.TOP_P] = next.topP
            prefs[Keys.TOP_K] = next.topK
            prefs[Keys.MIN_P] = next.minP
            prefs[Keys.REPEAT_PENALTY] = next.repeatPenalty
            prefs[Keys.DRY_MULTIPLIER] = next.dryMultiplier
            prefs[Keys.DRY_BASE] = next.dryBase
            prefs[Keys.DRY_ALLOWED_LENGTH] = next.dryAllowedLength
            prefs[Keys.DRY_PENALTY_LAST_N] = next.dryPenaltyLastN
            prefs[Keys.XTC_PROBABILITY] = next.xtcProbability
            prefs[Keys.XTC_THRESHOLD] = next.xtcThreshold
            prefs[Keys.REPEAT_LAST_N] = next.repeatLastN
            prefs[Keys.MAX_TOKENS] = next.maxTokens
            prefs[Keys.CONTEXT_SIZE] = next.contextSize
            if (next.serverContextSize == null) {
                prefs.remove(Keys.SERVER_CONTEXT_SIZE)
            } else {
                prefs[Keys.SERVER_CONTEXT_SIZE] = next.serverContextSize
            }
            prefs[Keys.GLOBAL_BEHAVIOR] = next.globalBehavior
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.BOT_LIST_LAYOUT] = next.botListLayout.name
            if (next.defaultPersonaId == null) {
                prefs.remove(Keys.DEFAULT_PERSONA_ID)
            } else {
                prefs[Keys.DEFAULT_PERSONA_ID] = next.defaultPersonaId
            }
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            baseUrl = this[Keys.BASE_URL] ?: defaults.baseUrl,
            apiKey = this[Keys.API_KEY] ?: defaults.apiKey,
            modelId = LlmModels.normalizeId(this[Keys.MODEL_ID] ?: defaults.modelId),
            language = AppLanguage.fromTag(this[Keys.LANGUAGE] ?: defaults.language.tag),
            userName = this[Keys.USER_NAME] ?: defaults.userName,
            temperature = this[Keys.TEMPERATURE] ?: defaults.temperature,
            topP = this[Keys.TOP_P] ?: defaults.topP,
            topK = this[Keys.TOP_K] ?: defaults.topK,
            minP = this[Keys.MIN_P] ?: defaults.minP,
            repeatPenalty = this[Keys.REPEAT_PENALTY] ?: defaults.repeatPenalty,
            dryMultiplier = this[Keys.DRY_MULTIPLIER] ?: defaults.dryMultiplier,
            dryBase = this[Keys.DRY_BASE] ?: defaults.dryBase,
            dryAllowedLength = this[Keys.DRY_ALLOWED_LENGTH] ?: defaults.dryAllowedLength,
            dryPenaltyLastN = this[Keys.DRY_PENALTY_LAST_N] ?: defaults.dryPenaltyLastN,
            xtcProbability = this[Keys.XTC_PROBABILITY] ?: defaults.xtcProbability,
            xtcThreshold = this[Keys.XTC_THRESHOLD] ?: defaults.xtcThreshold,
            repeatLastN = this[Keys.REPEAT_LAST_N] ?: defaults.repeatLastN,
            maxTokens = this[Keys.MAX_TOKENS] ?: defaults.maxTokens,
            contextSize = this[Keys.CONTEXT_SIZE] ?: defaults.contextSize,
            serverContextSize = this[Keys.SERVER_CONTEXT_SIZE],
            globalBehavior = this[Keys.GLOBAL_BEHAVIOR] ?: defaults.globalBehavior,
            theme = this[Keys.THEME]?.let {
                runCatching { AppTheme.valueOf(it) }.getOrDefault(AppTheme.Dark)
            } ?: AppTheme.Dark,
            botListLayout = this[Keys.BOT_LIST_LAYOUT]?.let {
                runCatching { BotListLayout.valueOf(it) }.getOrDefault(BotListLayout.GRID)
            } ?: BotListLayout.GRID,
            defaultPersonaId = this[Keys.DEFAULT_PERSONA_ID],
        )
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL_ID = stringPreferencesKey("model_id")
        val LANGUAGE = stringPreferencesKey("language")
        val USER_NAME = stringPreferencesKey("user_name")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val MIN_P = floatPreferencesKey("min_p")
        val REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        val DRY_MULTIPLIER = floatPreferencesKey("dry_multiplier")
        val DRY_BASE = floatPreferencesKey("dry_base")
        val DRY_ALLOWED_LENGTH = intPreferencesKey("dry_allowed_length")
        val DRY_PENALTY_LAST_N = intPreferencesKey("dry_penalty_last_n")
        val XTC_PROBABILITY = floatPreferencesKey("xtc_probability")
        val XTC_THRESHOLD = floatPreferencesKey("xtc_threshold")
        val REPEAT_LAST_N = intPreferencesKey("repeat_last_n")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val CONTEXT_SIZE = intPreferencesKey("context_size")
        val SERVER_CONTEXT_SIZE = intPreferencesKey("server_context_size")
        val GLOBAL_BEHAVIOR = stringPreferencesKey("global_behavior")
        val THEME = stringPreferencesKey("theme")
        val BOT_LIST_LAYOUT = stringPreferencesKey("bot_list_layout")
        val DEFAULT_PERSONA_ID = stringPreferencesKey("default_persona_id")
    }
}
