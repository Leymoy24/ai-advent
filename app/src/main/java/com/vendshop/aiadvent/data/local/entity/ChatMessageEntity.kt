package com.vendshop.aiadvent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)

