package com.vendshop.aiadvent.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Конфигурация агента для запроса к LLM.
 * @param withRestrictions добавлять ли system prompt с форматированием, max_tokens, stop
 */
data class AgentConfig(
    val withRestrictions: Boolean = false
)

/**
 * Результат выполнения запроса агентом.
 */
sealed class AgentResult {
    data class Success(val text: String) : AgentResult()
    data class Error(val message: String) : AgentResult()
}

/**
 * Интерфейс агента — отдельная сущность, инкапсулирующая логику запроса и ответа к LLM.
 */
interface LlmAgent {

    /**
     * Отправляет запрос в LLM и возвращает полный ответ (non-streaming).
     */
    suspend fun process(userRequest: String, config: AgentConfig = AgentConfig()): AgentResult

    /**
     * Отправляет запрос в LLM и эмитит куски текста по мере поступления (streaming).
     */
    fun processStream(userRequest: String, config: AgentConfig = AgentConfig()): Flow<String>
}
