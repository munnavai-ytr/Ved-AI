package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.ChatDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.entity.MessageEntity

/**
 * Local Room database storing chat sessions, messages, and long-term user memories on-device.
 */
@Database(
  entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class],
  version = 3,
  exportSchema = false,
)
abstract class VedDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao

  abstract fun memoryDao(): MemoryDao

  companion object {
    private const val DB_NAME = "ved_chat.db"

    val MIGRATION_1_2: Migration =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE messages ADD COLUMN imageUri TEXT")
          db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER")
          db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER")
        }
      }

    val MIGRATION_2_3: Migration =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memories` (
              `id` TEXT NOT NULL,
              `content` TEXT NOT NULL,
              `category` TEXT NOT NULL,
              `source` TEXT NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)"
          )
        }
      }

    fun create(context: Context): VedDatabase =
      Room.databaseBuilder(context.applicationContext, VedDatabase::class.java, DB_NAME)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
  }
}
