package com.vendshop.aiadvent.data.repository

import com.vendshop.aiadvent.data.local.dao.ChatHistoryDao
import com.vendshop.aiadvent.data.local.entity.ChatMessageEntity
import com.vendshop.aiadvent.data.model.Message
import com.vendshop.aiadvent.domain.repository.ChatHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatHistoryRepositoryImpl(
    private val dao: ChatHistoryDao
) : ChatHistoryRepository {

    override suspend fun getMessages(): List<Message> = withContext(Dispatchers.IO) {
        dao.getAllOrderBySortOrder()
            .map { Message(role = it.role, content = it.content) }
    }

    override suspend fun replaceAll(messages: List<Message>) = withContext(Dispatchers.IO) {
        dao.deleteAll()
        if (messages.isEmpty()) return@withContext
        val entities = messages.mapIndexed { index, msg ->
            ChatMessageEntity(
                role = msg.role,
                content = msg.content,
                sortOrder = index
            )
        }
        dao.insertAll(entities)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }
}

