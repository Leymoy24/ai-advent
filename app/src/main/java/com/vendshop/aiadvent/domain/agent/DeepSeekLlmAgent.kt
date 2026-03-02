package com.vendshop.aiadvent.domain.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vendshop.aiadvent.data.api.ApiClient
import com.vendshop.aiadvent.data.model.ChatRequest
import com.vendshop.aiadvent.data.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.fold
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Реализация LlmAgent для DeepSeek API.
 * Инкапсулирует сборку запроса, вызов LLM и парсинг ответа.
 */
class DeepSeekLlmAgent : LlmAgent {

    private val gson = Gson()

    private val formatInstruction = """
        Отвечай кратко. Максимум 2–3 предложения.
        Формат ответа: только текст, без заголовков и списков.
        Завершай ответ маркером ---
    """.trimIndent()

    override suspend fun process(userRequest: String, config: AgentConfig): AgentResult {
        return try {
            val text = processStream(userRequest, config)
                .fold("") { acc, chunk -> acc + chunk }
            AgentResult.Success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun processStream(userRequest: String, config: AgentConfig): Flow<String> = flow {
        val request = buildRequest(userRequest, config.withRestrictions)
        val response = ApiClient.deepSeekApi.chatCompletionStream(
            authorization = ApiClient.getAuthHeader(),
            request = request
        )
        if (!response.isSuccessful) {
            throw Exception("Ошибка: ${response.code()} - ${response.message()}")
        }
        val body = response.body() ?: throw Exception("Empty response body")
        try {
            val reader = BufferedReader(InputStreamReader(body.byteStream(), "UTF-8"))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val content = parseSseLine(line)
                if (content != null) emit(content)
            }
            reader.close()
        } finally {
            body.close()
        }
    }.flowOn(Dispatchers.IO)

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
        } catch (e: Exception) {
            null
        }
    }
}
