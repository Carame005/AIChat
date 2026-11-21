package com.example.caramelai.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class GenerationParams(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float,
    val maxTokens: Int,
    val repeatLastN: Int = 64,
    val dryMultiplier: Float = 0.8f,
    val dryBase: Float = 1.75f,
    val dryAllowedLength: Int = 2,
    val dryPenaltyLastN: Int = -1,
    val xtcProbability: Float = 0.5f,
    val xtcThreshold: Float = 0.1f,
    val cachePrompt: Boolean = true,
    val idSlot: Int? = null,
) {
    fun toRequest(
        model: String,
        messages: List<ChatMessageDto>,
        stream: Boolean,
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = model,
        messages = messages,
        stream = stream,
        temperature = temperature.toDouble(),
        topP = topP.toDouble(),
        topK = topK,
        minP = minP.toDouble(),
        repeatPenalty = repeatPenalty.toDouble(),
        repeatLastN = repeatLastN.takeIf { it >= 0 },
        dryMultiplier = dryMultiplier.toDouble(),
        dryBase = dryBase.toDouble(),
        dryAllowedLength = dryAllowedLength,
        // llama-server OpenAI schema rejects -1 (0..INT_MAX only). Omit so the
        // server keeps its internal default (whole context).
        dryPenaltyLastN = dryPenaltyLastN.takeIf { it >= 0 },
        xtcProbability = xtcProbability.toDouble(),
        xtcThreshold = xtcThreshold.toDouble(),
        cachePrompt = cachePrompt,
        maxTokens = maxTokens,
        idSlot = idSlot,
    )
}

sealed interface ChatStreamEvent {
    data class Chunk(val text: String) : ChatStreamEvent
    data class Done(val finishReason: String?) : ChatStreamEvent
}

data class CompletionResult(
    val text: String,
    val finishReason: String?,
)

class LlmClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val healthClient: OkHttpClient = client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Completions may wait for router model load + generation. */
    private val completeClient: OkHttpClient = client.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun fetchServerContext(baseUrl: String, apiKey: String): Result<Int> {
        val base = normalizeBaseUrl(baseUrl).getOrElse { return Result.failure(it) }
        val url = "$base/props"
        return getJson(url, apiKey, healthClient).mapCatching { body ->
            json.decodeFromString(ServerPropsResponse.serializer(), body)
                .defaultGenerationSettings?.nCtx
                ?: error("n_ctx missing in /props")
        }
    }

    suspend fun health(baseUrl: String, apiKey: String): Result<String> {
        val base = normalizeBaseUrl(baseUrl).getOrElse { return Result.failure(it) }
        val url = "$base/health"
        return getJson(url, apiKey, healthClient).fold(
            onSuccess = { body -> Result.success(body.ifBlank { "ok" }) },
            onFailure = { err ->
                val msg = err.message.orEmpty()
                if (msg.startsWith("HTTP ")) {
                    Result.failure(err)
                } else {
                    val detail = msg.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                    Result.failure(
                        IOException(
                            "Cannot reach $url ($detail). Use the PC LAN IP and port, e.g. http://192.168.x.x:8081",
                            err,
                        ),
                    )
                }
            },
        )
    }

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> {
        val base = normalizeBaseUrl(baseUrl).getOrElse { return Result.failure(it) }
        val url = "$base/v1/models"
        return getJson(url, apiKey, healthClient).mapCatching { body ->
            json.decodeFromString(ModelsListResponse.serializer(), body)
                .data.map { model -> model.id }
        }
    }

    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        params: GenerationParams,
    ): Result<String> = completeDetailed(baseUrl, apiKey, model, messages, params)
        .map { it.text }

    suspend fun completeDetailed(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        params: GenerationParams,
    ): Result<CompletionResult> {
        val base = normalizeBaseUrl(baseUrl).getOrElse { return Result.failure(it) }
        val requestBody = params.toRequest(model = model, messages = messages, stream = false)
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)
        // autoload=true: llama-server router loads the model on first request if needed
        val url = "$base/v1/chat/completions?autoload=true"
        return suspendCancellableCoroutine { cont ->
            val builder = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            val call = completeClient.newCall(builder.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resume(
                                Result.failure(IOException("HTTP ${it.code}: $body")),
                            )
                            return
                        }
                        runCatching {
                            val parsed = json.decodeFromString(
                                ChatCompletionResponse.serializer(),
                                body,
                            )
                            val choice = parsed.choices.firstOrNull()
                            val text = choice?.message?.content?.trim().orEmpty()
                            CompletionResult(
                                text = text,
                                finishReason = choice?.finishReason,
                            )
                        }.fold(
                            onSuccess = { result ->
                                if (result.text.isBlank()) {
                                    cont.resume(Result.failure(IOException("Empty completion")))
                                } else {
                                    cont.resume(Result.success(result))
                                }
                            },
                            onFailure = { err ->
                                cont.resume(Result.failure(IOException(err.message, err)))
                            },
                        )
                    }
                }
            })
        }
    }

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        params: GenerationParams,
    ): Flow<ChatStreamEvent> = callbackFlow {
        val base = normalizeBaseUrl(baseUrl).getOrElse {
            close(it)
            return@callbackFlow
        }
        val requestBody = params.toRequest(model = model, messages = messages, stream = true)
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)
        val url = "$base/v1/chat/completions?autoload=true"
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val request = builder.build()

        var finishReason: String? = null
        var completed = false
        fun completeDone() {
            if (completed) return
            completed = true
            trySend(ChatStreamEvent.Done(finishReason))
            close()
        }
        val factory = EventSources.createFactory(client)
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (data == "[DONE]") {
                    completeDone()
                    return
                }
                try {
                    val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), data)
                    val choice = chunk.choices.firstOrNull()
                    val reason = choice?.finishReason
                    if (!reason.isNullOrBlank()) {
                        finishReason = reason
                    }
                    val content = choice?.delta?.content
                    if (!content.isNullOrEmpty()) {
                        trySend(ChatStreamEvent.Chunk(content))
                    }
                } catch (_: Exception) {
                    // Ignore malformed intermediate chunks
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                val body = response?.let { resp ->
                    runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                }.orEmpty()
                val message = when {
                    response != null && body.isNotBlank() ->
                        "HTTP ${response.code}: $body"
                    response != null ->
                        "HTTP ${response.code}"
                    !t?.message.isNullOrBlank() ->
                        t!!.message!!
                    else ->
                        "Streaming failed"
                }
                close(IOException(message, t))
            }

            override fun onClosed(eventSource: EventSource) {
                completeDone()
            }
        }

        val eventSource = factory.newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun normalizeBaseUrl(baseUrl: String): Result<String> {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return Result.failure(IOException("Base URL is empty"))
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return Result.failure(
                IOException("Base URL must start with http:// (got: $trimmed)"),
            )
        }
        return Result.success(trimmed)
    }

    private suspend fun getJson(
        url: String,
        apiKey: String,
        httpClient: OkHttpClient,
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val call = httpClient.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                cont.resume(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        cont.resume(Result.success(body))
                    } else {
                        cont.resume(
                            Result.failure(IOException("HTTP ${it.code} from $url: $body")),
                        )
                    }
                }
            }
        })
    }

    companion object {
        const val CHAT_SLOT = 0
        const val SUMMARY_SLOT = 1
    }
}
