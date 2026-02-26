package com.vendshop.aiadvent.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vendshop.aiadvent.data.api.ApiClient
import com.vendshop.aiadvent.data.model.ChatRequest
import com.vendshop.aiadvent.data.model.Message
import com.vendshop.aiadvent.data.model.ModelOption
import com.vendshop.aiadvent.data.model.ModelOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader

/** Режим отправки: обычный, сравнение ограничений или сравнение 2 моделей */
enum class SendMode { NORMAL, COMPARISON, MODEL_COMPARISON }

/** Результат одного запроса к модели при сравнении */
data class ModelComparisonResult(
    val modelOption: ModelOption,
    val response: String,
    val elapsedMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double,
    val error: String? = null
)

data class ChatUiState(
    val isLoading: Boolean = false,
    val response: String = "",
    val error: String? = null,
    val lastUserQuestion: String = "",
    val responseUnrestricted: String? = null,
    val responseRestricted: String? = null,
    val comparisonInProgress: Boolean = false,
    /** Выбранная модель для обычного чата */
    val selectedModel: ModelOption = ModelOptions.all.first(),
    /** Результаты сравнения 2 моделей */
    val modelComparisonResults: List<ModelComparisonResult>? = null,
    val modelComparisonInProgress: Boolean = false,
    /** ID моделей, полученные через API list при старте */
    val availableModelIds: List<String> = emptyList()
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private val gson = Gson()

    init {
        viewModelScope.launch { loadModelsList() }
    }

    private suspend fun loadModelsList() {
        try {
            val response = ApiClient.deepSeekApi.listModels(
                authorization = ApiClient.getAuthHeader()
            )
            if (response.isSuccessful && response.body() != null) {
                val models = response.body()!!.data
                _uiState.value = _uiState.value.copy(availableModelIds = models.map { it.id })
            }
        } catch (_: Exception) { /* игнорируем ошибки при старте */ }
    }

    private val formatInstruction = """
        Отвечай кратко. Максимум 2–3 предложения.
        Формат ответа: только текст, без заголовков и списков.
        Завершай ответ маркером ---
    """.trimIndent()

    fun selectModel(model: ModelOption) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun sendMessage(userMessage: String, mode: SendMode = SendMode.NORMAL) {
        if (userMessage.isBlank()) return

        viewModelScope.launch {
            when (mode) {
                SendMode.NORMAL -> sendSingle(userMessage, _uiState.value.selectedModel)
                SendMode.COMPARISON -> sendComparison(userMessage)
                SendMode.MODEL_COMPARISON -> sendModelComparison(userMessage)
            }
        }
    }

    private suspend fun sendSingle(userMessage: String, modelOption: ModelOption) {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            response = "",
            lastUserQuestion = userMessage,
            responseUnrestricted = null,
            responseRestricted = null,
            modelComparisonResults = null
        )

        try {
            val request = buildRequest(userMessage, modelOption)
            val response = ApiClient.deepSeekApi.chatCompletionStream(
                authorization = ApiClient.getAuthHeader(),
                request = request
            )
            if (response.isSuccessful && response.body() != null) {
                processStreamingResponse(response.body()!!)
                _uiState.value = _uiState.value.copy(isLoading = false)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Ошибка: ${response.code()} - ${response.message()}"
                )
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Ошибка: ${e.message}"
            )
        }
    }

    private suspend fun sendModelComparison(userMessage: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            response = "",
            error = null,
            lastUserQuestion = userMessage,
            responseUnrestricted = null,
            responseRestricted = null,
            modelComparisonResults = null,
            modelComparisonInProgress = true
        )

        val results = coroutineScope {
            ModelOptions.all.map { modelOption ->
                async { runSingleModelRequest(userMessage, modelOption) }
            }.awaitAll()
        }

        _uiState.value = _uiState.value.copy(
            modelComparisonResults = results,
            modelComparisonInProgress = false
        )
    }

    private suspend fun runSingleModelRequest(userMessage: String, modelOption: ModelOption): ModelComparisonResult {
        val startMs = System.currentTimeMillis()
        val request = buildRequest(userMessage, modelOption, stream = false)

        return try {
            val response = ApiClient.deepSeekApi.chatCompletion(
                authorization = ApiClient.getAuthHeader(),
                request = request
            )
            val elapsedMs = System.currentTimeMillis() - startMs

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val text = body.choices.firstOrNull()?.message?.content ?: ""
                val usage = body.usage
                val promptTokens = usage?.promptTokens ?: 0
                val completionTokens = usage?.completionTokens ?: 0
                val cost = ModelOptions.calculateCost(promptTokens, completionTokens)
                ModelComparisonResult(
                    modelOption = modelOption,
                    response = text,
                    elapsedMs = elapsedMs,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    costUsd = cost
                )
            } else {
                ModelComparisonResult(
                    modelOption = modelOption,
                    response = "",
                    elapsedMs = elapsedMs,
                    promptTokens = 0,
                    completionTokens = 0,
                    costUsd = 0.0,
                    error = "Ошибка: ${response.code()}"
                )
            }
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startMs
            ModelComparisonResult(
                modelOption = modelOption,
                response = "",
                elapsedMs = elapsedMs,
                promptTokens = 0,
                completionTokens = 0,
                costUsd = 0.0,
                error = e.message ?: "Ошибка"
            )
        }
    }

    private suspend fun sendComparison(userMessage: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            response = "",
            error = null,
            lastUserQuestion = userMessage,
            responseUnrestricted = null,
            responseRestricted = null,
            comparisonInProgress = true
        )

        // 1. Запрос БЕЗ ограничений
        try {
            val reqUnrestricted = buildRequest(userMessage, withRestrictions = false)
            val resp = ApiClient.deepSeekApi.chatCompletionStream(
                authorization = ApiClient.getAuthHeader(),
                request = reqUnrestricted
            )
            if (resp.isSuccessful && resp.body() != null) {
                val text = processStreamingResponseToText(resp.body()!!)
                _uiState.value = _uiState.value.copy(responseUnrestricted = text)
            } else {
                _uiState.value = _uiState.value.copy(
                    responseUnrestricted = "Ошибка: ${resp.code()}",
                    comparisonInProgress = false
                )
                return
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                responseUnrestricted = "Ошибка: ${e.message}",
                comparisonInProgress = false
            )
            return
        }

        // 2. Запрос С ограничениями
        try {
            val reqRestricted = buildRequest(userMessage, withRestrictions = true)
            val resp = ApiClient.deepSeekApi.chatCompletionStream(
                authorization = ApiClient.getAuthHeader(),
                request = reqRestricted
            )
            if (resp.isSuccessful && resp.body() != null) {
                val text = processStreamingResponseToText(resp.body()!!)
                _uiState.value = _uiState.value.copy(
                    responseRestricted = text,
                    comparisonInProgress = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    responseRestricted = "Ошибка: ${resp.code()}",
                    comparisonInProgress = false
                )
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                responseRestricted = "Ошибка: ${e.message}",
                comparisonInProgress = false
            )
        }
    }

    private fun buildRequest(
        userMessage: String,
        modelOption: ModelOption,
        stream: Boolean = true
    ): ChatRequest = ChatRequest(
        model = modelOption.id,
        messages = listOf(Message(role = "user", content = userMessage)),
        stream = stream,
        maxTokens = modelOption.maxTokens,
        temperature = modelOption.temperature
    )

    private fun buildRequest(userMessage: String, withRestrictions: Boolean): ChatRequest {
        val messages = if (withRestrictions) {
            listOf(
                Message(role = "system", content = formatInstruction),
                Message(role = "user", content = userMessage)
            )
        } else {
            listOf(Message(role = "user", content = userMessage))
        }

        return ChatRequest(
            model = "deepseek-chat",
            messages = messages,
            stream = true,
            maxTokens = if (withRestrictions) 150 else null,
            stop = if (withRestrictions) listOf("---") else null
        )
    }
    
    /** Обрабатывает поток и обновляет UI по мере поступления. Используется в обычном режиме. */
    private suspend fun processStreamingResponse(responseBody: ResponseBody): String {
        var accumulatedText = ""
        try {
            withContext(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), "UTF-8"))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val content = parseSseLine(line)
                    if (content != null) {
                        accumulatedText += content
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(isLoading = true, response = accumulatedText)
                        }
                    }
                }
                reader.close()
            }
        } finally {
            responseBody.close()
        }
        return accumulatedText
    }

    /** Обрабатывает поток и возвращает полный текст. Для режима сравнения (без обновления UI). */
    private suspend fun processStreamingResponseToText(responseBody: ResponseBody): String {
        var accumulatedText = ""
        try {
            withContext(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), "UTF-8"))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val content = parseSseLine(line)
                    if (content != null) accumulatedText += content
                }
                reader.close()
            }
        } finally {
            responseBody.close()
        }
        return accumulatedText
    }

    private fun parseSseLine(line: String?): String? {
        if (line.isNullOrBlank() || !line.startsWith("data: ")) return null
        val jsonData = line.substring(6).trim()
        if (jsonData == "[DONE]") return null
        if (jsonData.isEmpty()) return null
        return try {
            val jsonObject = gson.fromJson(jsonData, JsonObject::class.java)
            val choices = jsonObject.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: return null
            if (!delta.has("content") || delta.get("content").isJsonNull) return null
            delta.get("content").asString
        } catch (e: Exception) { null }
    }
}

