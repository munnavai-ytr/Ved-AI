package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.ChatDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity

/**
 * Local Room database storing chat sessions and messages on-device.
 */
@Database(
  entities = [ConversationEntity::class, MessageEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class VedDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao

  companion object {
    private const val DB_NAME = "ved_chat.db"

    fun create(context: Context): VedDatabase =
      Room.databaseBuilder(context.applicationContext, VedDatabase::class.java, DB_NAME)
        .fallbackToDestructiveMigration(dropAllTables = false)
        .build()
  }
}
