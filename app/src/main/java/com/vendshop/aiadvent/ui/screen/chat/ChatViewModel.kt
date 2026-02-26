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

/** Режим отправки: обычный, сравнение или эксперимент «4 способа» */
enum class SendMode { NORMAL, COMPARISON, FOUR_WAYS }

/** Одна задача, решённая четырьмя способами + сравнение */
data class FourWayResult(
    val direct: String,
    val stepByStep: String,
    val viaMetaPrompt: String,
    val experts: String,
    val comparison: String
)

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
    /** Задача для эксперимента «4 способа» */
    val fourWayTask: String = "",
    /** Результаты эксперимента «4 способа» (null = не запускали или сброс) */
    val fourWayResult: FourWayResult? = null,
    /** Идёт ли эксперимент «4 способа» */
    val fourWayInProgress: Boolean = false,
    /** Текущий шаг эксперимента для индикатора (1..5) */
    val fourWayStep: Int = 0
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

    /** Задача для эксперимента «4 способа» (логическая) */
    private val taskFourWays = """
        В комнате 3 лампы, снаружи 3 выключателя. Как одним заходом в комнату определить, какой выключатель какой лампе соответствует?
    """.trimIndent()

    fun sendMessage(userMessage: String, mode: SendMode = SendMode.NORMAL) {
        if (userMessage.isBlank() && mode != SendMode.FOUR_WAYS) return

        viewModelScope.launch {
            when (mode) {
                SendMode.NORMAL -> sendSingle(userMessage, withRestrictions = false)
                SendMode.COMPARISON -> sendComparison(userMessage)
                SendMode.FOUR_WAYS -> runFourWaysExperiment()
            }
        }
    }

    /** Эксперимент: решить одну задачу 4 способами и сравнить ответы */
    private suspend fun runFourWaysExperiment() {
        _uiState.value = _uiState.value.copy(
            fourWayTask = taskFourWays,
            fourWayResult = FourWayResult(
                direct = "",
                stepByStep = "",
                viaMetaPrompt = "",
                experts = "",
                comparison = ""
            ),
            fourWayInProgress = true,
            fourWayStep = 0,
            error = null,
            lastUserQuestion = taskFourWays
        )

        var direct = ""
        var stepByStep = ""
        var viaMetaPrompt = ""
        var experts = ""

        try {
            // 1. Прямой ответ — печатается постепенно
            _uiState.value = _uiState.value.copy(fourWayStep = 1)
            direct = requestAndStreamTask(
                buildRequestMessages(listOf(Message("user", taskFourWays)))
            ) { text ->
                _uiState.value = _uiState.value.copy(
                    fourWayResult = _uiState.value.fourWayResult!!.copy(direct = text)
                )
            }
            if (direct.isEmpty()) direct = "(нет ответа)"
            _uiState.value = _uiState.value.copy(
                fourWayResult = _uiState.value.fourWayResult!!.copy(direct = direct)
            )

            // 2. С инструкцией «решай пошагово» — печатается постепенно
            _uiState.value = _uiState.value.copy(fourWayStep = 2)
            val taskStepByStep = "$taskFourWays\n\nРешай пошагово."
            stepByStep = requestAndStreamTask(
                buildRequestMessages(listOf(Message("user", taskStepByStep)))
            ) { text ->
                _uiState.value = _uiState.value.copy(
                    fourWayResult = _uiState.value.fourWayResult!!.copy(stepByStep = text)
                )
            }
            if (stepByStep.isEmpty()) stepByStep = "(нет ответа)"
            _uiState.value = _uiState.value.copy(
                fourWayResult = _uiState.value.fourWayResult!!.copy(stepByStep = stepByStep)
            )

            // 3. Сначала промпт, затем решение по нему — печатается постепенно
            _uiState.value = _uiState.value.copy(fourWayStep = 3)
            val metaAsk = "Составь краткий промпт для решения следующей задачи. Напиши только сам промпт, без решения. Задача:\n\n$taskFourWays"
            val customPrompt = requestAndStreamTask(
                buildRequestMessages(listOf(Message("user", metaAsk)))
            ) { text ->
                _uiState.value = _uiState.value.copy(
                    fourWayResult = _uiState.value.fourWayResult!!.copy(viaMetaPrompt = "Промпт: $text")
                )
            }
            val promptToUse = if (customPrompt.isNotBlank()) customPrompt.trim() else "Реши задачу пошагово."
            viaMetaPrompt = requestAndStreamTask(
                buildRequestMessages(listOf(Message("user", "$promptToUse\n\nЗадача: $taskFourWays")))
            ) { text ->
                val full = if (customPrompt.isNotBlank()) "Промпт: $customPrompt\n\nОтвет: $text" else text
                _uiState.value = _uiState.value.copy(
                    fourWayResult = _uiState.value.fourWayResult!!.copy(viaMetaPrompt = full)
                )
            }
            if (viaMetaPrompt.isEmpty()) viaMetaPrompt = "(нет ответа)"
            _uiState.value = _uiState.value.copy(
                fourWayResult = _uiState.value.fourWayResult!!.copy(
                    viaMetaPrompt = if (customPrompt.isNotBlank()) "Промпт: $customPrompt\n\nОтвет: $viaMetaPrompt" else viaMetaPrompt
                )
            )

            // 4. Группа экспертов — печатается постепенно
            _uiState.value = _uiState.value.copy(fourWayStep = 4)
            val expertsPrompt = """
                Ты — группа экспертов: аналитик, инженер и критик. Задача: $taskFourWays
                Дай по очереди ответ каждого эксперта: сначала «Аналитик:», затем «Инженер:», затем «Критик:». Каждый даёт своё решение или комментарий.
            """.trimIndent()
            experts = requestAndStreamTask(
                buildRequestMessages(listOf(Message("user", expertsPrompt)))
            ) { text ->
                _uiState.value = _uiState.value.copy(
                    fourWayResult = _uiState.value.fourWayResult!!.copy(experts = text)
                )
            }
            if (experts.isEmpty()) experts = "(нет ответа)"
            _uiState.value = _uiState.value.copy(
                fourWayResult = _uiState.value.fourWayResult!!.copy(experts = experts)
            )

            // 5. Сравнение — печатается постепенно
            _uiState.value = _uiState.value.copy(fourWayStep = 5)
            val comparisonPrompt = """
                Задача: $taskFourWays

                Решение 1 (прямой ответ): $direct

                Решение 2 (пошагово): $stepByStep

                Решение 3 (через составленный промпт): $viaMetaPrompt

                Решение 4 (эксперты): $experts

                Кратко ответь: отличаются ли ответы по сути? Какое решение наиболее точное и почему (1–2 предложения)?
            """.trimIndent()
            val comparison = requestAndStreamTask(
                buildRequestMessages(listOf(Message("user", comparisonPrompt)))
            ) { text ->
                _uiState.value = _uiState.value.copy(
                    fourWayResult = _uiState.value.fourWayResult!!.copy(comparison = text)
                )
            }
            _uiState.value = _uiState.value.copy(
                fourWayResult = _uiState.value.fourWayResult!!.copy(
                    comparison = if (comparison.isEmpty()) "Сравнение не получено." else comparison
                )
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                fourWayInProgress = false,
                fourWayStep = 0,
                error = "Ошибка эксперимента: ${e.message}"
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            fourWayInProgress = false,
            fourWayStep = 0
        )
    }

    private fun buildRequestMessages(messages: List<Message>): ChatRequest =
        ChatRequest(messages = messages, stream = true, maxTokens = null, stop = null)

    /** Стримит ответ и обновляет UI по мере поступления. Возвращает полный текст. */
    private suspend fun requestAndStreamTask(
        request: ChatRequest,
        onChunk: suspend (String) -> Unit
    ): String {
        val response = ApiClient.deepSeekApi.chatCompletionStream(
            authorization = ApiClient.getAuthHeader(),
            request = request
        )
        if (!response.isSuccessful || response.body() == null) return ""
        return processStreamingToState(response.body()!!, onChunk)
    }

    /** Обрабатывает SSE-поток и вызывает onChunk с накопленным текстом по мере поступления. */
    private suspend fun processStreamingToState(
        responseBody: ResponseBody,
        onChunk: suspend (String) -> Unit
    ): String {
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
                            onChunk(accumulatedText)
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

    private suspend fun requestSingleTask(request: ChatRequest): String {
        val response = ApiClient.deepSeekApi.chatCompletionStream(
            authorization = ApiClient.getAuthHeader(),
            request = request
        )
        if (!response.isSuccessful || response.body() == null) return ""
        return processStreamingResponseToText(response.body()!!)
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

