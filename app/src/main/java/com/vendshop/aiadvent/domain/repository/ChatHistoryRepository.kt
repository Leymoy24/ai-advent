package com.vendshop.aiadvent.domain.repository

import com.vendshop.aiadvent.data.model.Message

interface ChatHistoryRepository {
    suspend fun getMessages(): List<Message>

    /**
     * Сохраняет полный список сообщений как единственный диалог.
     * Реализация может делать deleteAll + insertAll для простоты.
     */
    suspend fun replaceAll(messages: List<Message>)

    suspend fun clear()
}

