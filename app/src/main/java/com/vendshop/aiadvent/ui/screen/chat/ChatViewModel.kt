package com.vendshop.aiadvent.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vendshop.aiadvent.domain.agent.AgentConfig
import com.vendshop.aiadvent.domain.agent.AgentResult
import com.vendshop.aiadvent.domain.agent.DeepSeekLlmAgent
import com.vendshop.aiadvent.domain.agent.LlmAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

class ChatViewModel(
    private val agent: LlmAgent = DeepSeekLlmAgent()
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

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
            agent.processStream(userMessage, AgentConfig(withRestrictions = withRestrictions))
                .collect { chunk ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = true,
                        response = _uiState.value.response + chunk
                    )
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
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
        when (val result = agent.process(userMessage, AgentConfig(withRestrictions = false))) {
            is AgentResult.Success ->
                _uiState.value = _uiState.value.copy(responseUnrestricted = result.text)
            is AgentResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    responseUnrestricted = "Ошибка: ${result.message}",
                    comparisonInProgress = false
                )
                return
            }
        }

        // 2. Запрос С ограничениями
        when (val result = agent.process(userMessage, AgentConfig(withRestrictions = true))) {
            is AgentResult.Success ->
                _uiState.value = _uiState.value.copy(
                    responseRestricted = result.text,
                    comparisonInProgress = false
                )
            is AgentResult.Error ->
                _uiState.value = _uiState.value.copy(
                    responseRestricted = "Ошибка: ${result.message}",
                    comparisonInProgress = false
                )
        }
    }
}

