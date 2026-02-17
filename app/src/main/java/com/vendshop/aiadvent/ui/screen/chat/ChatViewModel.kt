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

data class ChatUiState(
    val isLoading: Boolean = false,
    val response: String = "",
    val error: String? = null
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private val gson = Gson()

    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                response = ""
            )

            try {
                val request = ChatRequest(
                    messages = listOf(
                        Message(role = "user", content = userMessage)
                    ),
                    stream = true
                )

                val response = ApiClient.deepSeekApi.chatCompletionStream(
                    authorization = ApiClient.getAuthHeader(),
                    request = request
                )

                if (response.isSuccessful && response.body() != null) {
                    processStreamingResponse(response.body()!!)
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
    }
    
    private suspend fun processStreamingResponse(responseBody: ResponseBody) {
        var accumulatedText = ""
        
        try {
            withContext(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), "UTF-8"))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) continue
                    
                    // Server-Sent Events format: "data: {...}"
                    if (line!!.startsWith("data: ")) {
                        val jsonData = line.substring(6).trim() // Remove "data: " prefix
                        
                        // Check for [DONE] marker
                        if (jsonData == "[DONE]") {
                            break
                        }
                        
                        if (jsonData.isNotEmpty()) {
                            try {
                                val jsonObject = gson.fromJson(jsonData, JsonObject::class.java)
                                val choices = jsonObject.getAsJsonArray("choices")
                                
                                if (choices != null && choices.size() > 0) {
                                    val choice = choices[0].asJsonObject
                                    val delta = choice.getAsJsonObject("delta")
                                    
                                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull) {
                                        val content = delta.get("content").asString
                                        if (content.isNotEmpty()) {
                                            accumulatedText += content
                                            
                                            // Update UI with accumulated text (StateFlow is thread-safe)
                                            withContext(Dispatchers.Main) {
                                                _uiState.value = _uiState.value.copy(
                                                    isLoading = true,
                                                    response = accumulatedText
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip malformed JSON lines
                                continue
                            }
                        }
                    }
                }
                
                reader.close()
            }
            
            // Mark as complete
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                response = accumulatedText
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Ошибка обработки потока: ${e.message}"
            )
        } finally {
            responseBody.close()
        }
    }
}

