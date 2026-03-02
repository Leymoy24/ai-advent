package com.vendshop.aiadvent.ui.screen.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vendshop.aiadvent.data.local.AppDatabase
import com.vendshop.aiadvent.data.model.Message
import com.vendshop.aiadvent.data.repository.ChatHistoryRepositoryImpl
import com.vendshop.aiadvent.domain.agent.AgentConfig
import com.vendshop.aiadvent.domain.agent.AgentResult
import com.vendshop.aiadvent.domain.agent.DeepSeekLlmAgent
import com.vendshop.aiadvent.domain.agent.LlmAgent
import com.vendshop.aiadvent.domain.repository.ChatHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Режим отправки: обычный или сравнение (без ограничений vs с ограничениями) */
enum class SendMode { NORMAL, COMPARISON }

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val messages: List<Message> = emptyList(),
    /** Текущий стриминговый ответ ассистента (ещё не сохранён в истории). */
    val streamingContent: String = "",
    /** Последний вопрос пользователя (отображается заголовком) */
    val lastUserQuestion: String = "",
    /** Результат сравнения: ответ без ограничений */
    val responseUnrestricted: String? = null,
    /** Результат сравнения: ответ с ограничениями */
    val responseRestricted: String? = null,
    /** Идёт ли режим сравнения (ждём оба ответа) */
    val comparisonInProgress: Boolean = false
)

class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val agent: LlmAgent = DeepSeekLlmAgent(),
    private val historyRepository: ChatHistoryRepository = ChatHistoryRepositoryImpl(
        AppDatabase.getInstance(application).chatHistoryDao()
    )
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val history = historyRepository.getMessages()
            _uiState.update { it.copy(messages = history) }
        }
    }

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
        val historyForRequest = _uiState.value.messages
        val messagesWithUser = historyForRequest + Message(role = "user", content = userMessage)

        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                messages = messagesWithUser,
                streamingContent = "",
                lastUserQuestion = userMessage,
                responseUnrestricted = null,
                responseRestricted = null
            )
        }

        try {
            agent.processStream(
                userMessage,
                AgentConfig(withRestrictions = withRestrictions),
                history = historyForRequest
            ).collect { chunk ->
                _uiState.update { it.copy(isLoading = true, streamingContent = it.streamingContent + chunk) }
            }

            val assistantMessage = Message(role = "assistant", content = _uiState.value.streamingContent)
            val finalMessages = _uiState.value.messages + assistantMessage
            historyRepository.replaceAll(finalMessages)

            _uiState.update { it.copy(isLoading = false, messages = finalMessages, streamingContent = "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun sendComparison(userMessage: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = null,
            lastUserQuestion = userMessage,
            responseUnrestricted = null,
            responseRestricted = null,
            comparisonInProgress = true
        )

        // 1. Запрос БЕЗ ограничений
        when (
            val result = agent.process(
                userMessage,
                AgentConfig(withRestrictions = false),
                history = _uiState.value.messages
            )
        ) {
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
        when (
            val result = agent.process(
                userMessage,
                AgentConfig(withRestrictions = true),
                history = _uiState.value.messages
            )
        ) {
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

