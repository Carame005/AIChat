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
)

sealed interface ChatStreamEvent {
    data class Chunk(val text: String) : ChatStreamEvent
    data class Done(val finishReason: String?) : ChatStreamEvent
}

class LlmClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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

    suspend fun health(baseUrl: String, apiKey: String): Result<String> {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return Result.failure(IOException("Base URL is empty"))
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return Result.failure(
                IOException("Base URL must start with http:// (got: $trimmed)"),
            )
        }
        val url = "$trimmed/health"
        return suspendCancellableCoroutine { cont ->
            val builder = Request.Builder().url(url).get()
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            val call = healthClient.newCall(builder.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    cont.resume(
                        Result.failure(
                            IOException(
                                "Cannot reach $url ($detail). Use the PC LAN IP and port, e.g. http://192.168.x.x:8081",
                                e,
                            ),
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (it.isSuccessful) {
                            cont.resume(Result.success(body.ifBlank { "ok" }))
                        } else {
                            cont.resume(
                                Result.failure(
                                    IOException("HTTP ${it.code} from $url: $body"),
                                ),
                            )
                        }
                    }
                }
            })
        }
    }

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return Result.failure(IOException("Base URL is empty"))
        }
        val url = "$trimmed/v1/models"
        return suspendCancellableCoroutine { cont ->
            val builder = Request.Builder().url(url).get()
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            val call = healthClient.newCall(builder.build())
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
                                Result.failure(IOException("HTTP ${it.code} from $url: $body")),
                            )
                            return
                        }
                        runCatching {
                            json.decodeFromString(ModelsListResponse.serializer(), body)
                                .data.map { model -> model.id }
                        }.fold(
                            onSuccess = { ids -> cont.resume(Result.success(ids)) },
                            onFailure = { err ->
                                cont.resume(Result.failure(IOException(err.message, err)))
                            },
                        )
                    }
                }
            })
        }
    }

    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        params: GenerationParams,
    ): Result<String> {
        val requestBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = false,
            temperature = params.temperature.toDouble(),
            topP = params.topP.toDouble(),
            topK = params.topK,
            minP = params.minP.toDouble(),
            repeatPenalty = params.repeatPenalty.toDouble(),
            maxTokens = params.maxTokens,
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)
        // autoload=true: llama-server router loads the model on first request if needed
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions?autoload=true"
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
                            json.decodeFromString(ChatCompletionResponse.serializer(), body)
                                .choices.firstOrNull()?.message?.content?.trim().orEmpty()
                        }.fold(
                            onSuccess = { text ->
                                if (text.isBlank()) {
                                    cont.resume(Result.failure(IOException("Empty completion")))
                                } else {
                                    cont.resume(Result.success(text))
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
        val requestBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = true,
            temperature = params.temperature.toDouble(),
            topP = params.topP.toDouble(),
            topK = params.topK,
            minP = params.minP.toDouble(),
            repeatPenalty = params.repeatPenalty.toDouble(),
            maxTokens = params.maxTokens,
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions?autoload=true"
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
                val message = t?.message
                    ?: response?.let { "HTTP ${it.code}" }
                    ?: "Streaming failed"
                close(IOException(message, t))
            }

            override fun onClosed(eventSource: EventSource) {
                completeDone()
            }
        }

        val eventSource = factory.newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }
}
