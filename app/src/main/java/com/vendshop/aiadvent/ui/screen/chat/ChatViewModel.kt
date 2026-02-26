package com.vendshop.aiadvent.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vendshop.aiadvent.data.api.ApiClient
import com.vendshop.aiadvent.data.model.ChatRequest
import com.vendshop.aiadvent.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader

/** Режим отправки: обычный или сравнение (без ограничений vs с ограничениями) */
enum class SendMode { NORMAL, COMPARISON }

data class ChatUiState(
    val isLoading: Boolean = false,
    val response: String = "",
    val error: String? = null,
    /** Последний вопрос пользователя (отображается заголовком) */
    val lastUserQuestion: String = "",
    /** Результат сравнения: ответ без ограничений */
    val responseUnrestricted: String? = null,
    /** Результат сравнения: ответ с ограничениями */
    val responseRestricted: String? = null,
    /** Идёт ли режим сравнения (ждём оба ответа) */
    val comparisonInProgress: Boolean = false
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private val gson = Gson()

    private val formatInstruction = """
        Отвечай кратко. Максимум 2–3 предложения.
        Формат ответа: только текст, без заголовков и списков.
        Завершай ответ маркером ---
    """.trimIndent()

    fun sendMessage(userMessage: String, mode: SendMode = SendMode.NORMAL) {
        if (userMessage.isBlank()) return

        viewModelScope.launch {
            when (mode) {
                SendMode.NORMAL -> sendSingle(userMessage, withRestrictions = false)
                SendMode.COMPARISON -> sendComparison(userMessage)
            }
        }
    }

    private suspend fun sendSingle(userMessage: String, withRestrictions: Boolean) {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            response = "",
            lastUserQuestion = userMessage,
            responseUnrestricted = null,
            responseRestricted = null
        )

        try {
            val request = buildRequest(userMessage, withRestrictions)
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

