package com.vendshop.aiadvent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vendshop.aiadvent.data.local.entity.ChatMessageEntity

@Dao
interface ChatHistoryDao {

    @Query("SELECT * FROM chat_messages ORDER BY sort_order ASC")
    suspend fun getAllOrderBySortOrder(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

