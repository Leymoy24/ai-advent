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

/** Режим отправки: обычный, сравнение ограничений или сравнение по temperature */
enum class SendMode { NORMAL, COMPARISON, TEMPERATURE_COMPARISON }

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
    val comparisonInProgress: Boolean = false,
    /** Сравнение по temperature: ответ при temperature=0 */
    val responseTemp0: String? = null,
    /** Сравнение по temperature: ответ при temperature=0.7 */
    val responseTemp07: String? = null,
    /** Сравнение по temperature: ответ при temperature=1.2 */
    val responseTemp12: String? = null,
    /** Идёт ли сравнение по temperature (ждём три ответа) */
    val temperatureComparisonInProgress: Boolean = false
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
                SendMode.TEMPERATURE_COMPARISON -> sendTemperatureComparison(userMessage)
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
            responseRestricted = null,
            responseTemp0 = null,
            responseTemp07 = null,
            responseTemp12 = null
        )

        try {
            val request = buildRequest(userMessage, withRestrictions, temperature = 0.7)
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
            responseTemp0 = null,
            responseTemp07 = null,
            responseTemp12 = null,
            comparisonInProgress = true
        )

        // 1. Запрос БЕЗ ограничений
        try {
            val reqUnrestricted = buildRequest(userMessage, withRestrictions = false, temperature = 0.7)
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
            val reqRestricted = buildRequest(userMessage, withRestrictions = true, temperature = 0.7)
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

    /** Отправляет один и тот же запрос с temperature 0, 0.7 и 1.2 для сравнения ответов. */
    private suspend fun sendTemperatureComparison(userMessage: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            response = "",
            error = null,
            lastUserQuestion = userMessage,
            responseUnrestricted = null,
            responseRestricted = null,
            responseTemp0 = null,
            responseTemp07 = null,
            responseTemp12 = null,
            temperatureComparisonInProgress = true
        )

        val temps = listOf(0.0 to "responseTemp0", 0.7 to "responseTemp07", 1.2 to "responseTemp12")
        for ((temp, key) in temps) {
            try {
                val req = buildRequest(userMessage, withRestrictions = false, temperature = temp)
                val resp = ApiClient.deepSeekApi.chatCompletionStream(
                    authorization = ApiClient.getAuthHeader(),
                    request = req
                )
                if (resp.isSuccessful && resp.body() != null) {
                    val text = processStreamingResponseToText(resp.body()!!)
                    _uiState.value = when (key) {
                        "responseTemp0" -> _uiState.value.copy(responseTemp0 = text)
                        "responseTemp07" -> _uiState.value.copy(responseTemp07 = text)
                        "responseTemp12" -> _uiState.value.copy(responseTemp12 = text)
                        else -> _uiState.value
                    }
                } else {
                    val err = "Ошибка: ${resp.code()}"
                    _uiState.value = when (key) {
                        "responseTemp0" -> _uiState.value.copy(responseTemp0 = err)
                        "responseTemp07" -> _uiState.value.copy(responseTemp07 = err)
                        "responseTemp12" -> _uiState.value.copy(responseTemp12 = err)
                        else -> _uiState.value
                    }
                }
            } catch (e: Exception) {
                val err = "Ошибка: ${e.message}"
                _uiState.value = when (key) {
                    "responseTemp0" -> _uiState.value.copy(responseTemp0 = err)
                    "responseTemp07" -> _uiState.value.copy(responseTemp07 = err)
                    "responseTemp12" -> _uiState.value.copy(responseTemp12 = err)
                    else -> _uiState.value
                }
            }
        }
        _uiState.value = _uiState.value.copy(temperatureComparisonInProgress = false)
    }

    private fun buildRequest(userMessage: String, withRestrictions: Boolean, temperature: Double = 0.7): ChatRequest {
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
            temperature = temperature,
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

