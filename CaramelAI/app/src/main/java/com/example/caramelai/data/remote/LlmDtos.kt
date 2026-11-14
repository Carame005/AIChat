package com.example.caramelai.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("min_p") val minP: Double? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
    @SerialName("repeat_last_n") val repeatLastN: Int? = null,
    @SerialName("dry_multiplier") val dryMultiplier: Double? = null,
    @SerialName("dry_base") val dryBase: Double? = null,
    @SerialName("dry_allowed_length") val dryAllowedLength: Int? = null,
    @SerialName("dry_penalty_last_n") val dryPenaltyLastN: Int? = null,
    @SerialName("xtc_probability") val xtcProbability: Double? = null,
    @SerialName("xtc_threshold") val xtcThreshold: Double? = null,
    @SerialName("cache_prompt") val cachePrompt: Boolean? = true,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** Prefer a specific llama-server parallel slot (chat vs summary). */
    @SerialName("id_slot") val idSlot: Int? = null,
)

@Serializable
data class ServerPropsResponse(
    @SerialName("default_generation_settings")
    val defaultGenerationSettings: DefaultGenerationSettings? = null,
)

@Serializable
data class DefaultGenerationSettings(
    @SerialName("n_ctx") val nCtx: Int? = null,
)

@Serializable
data class ModelsListResponse(
    val data: List<RemoteModelInfo> = emptyList(),
)

@Serializable
data class RemoteModelInfo(
    val id: String,
)

@Serializable
data class ChatCompletionChunk(
    val choices: List<ChatChoiceDelta> = emptyList(),
)

@Serializable
data class ChatChoiceDelta(
    val delta: ChatDelta = ChatDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatDelta(
    val content: String? = null,
    val role: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatCompletionChoice> = emptyList(),
)

@Serializable
data class ChatCompletionChoice(
    val message: ChatMessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
