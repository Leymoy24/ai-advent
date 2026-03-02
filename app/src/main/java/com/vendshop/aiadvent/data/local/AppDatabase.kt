package com.vendshop.aiadvent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vendshop.aiadvent.data.local.dao.ChatHistoryDao
import com.vendshop.aiadvent.data.local.entity.ChatMessageEntity

@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_db"
                ).build().also { INSTANCE = it }
            }
    }
}

