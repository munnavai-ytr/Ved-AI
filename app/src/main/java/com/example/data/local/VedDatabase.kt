package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.ChatDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.dao.SkillDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.FolderEntity
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.entity.MessageAttachmentEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.SkillEntity
import com.example.data.local.entity.SkillFileEntity

/**
 * Local Room database storing chat sessions, folders, messages, file attachments,
 * long-term memories, and custom Claude-style skills on-device.
 */
@Database(
  entities = [
    ConversationEntity::class,
    FolderEntity::class,
    MessageEntity::class,
    MemoryEntity::class,
    MessageAttachmentEntity::class,
    SkillEntity::class,
    SkillFileEntity::class,
  ],
  version = 5,
  exportSchema = false,
)
abstract class VedDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao

  abstract fun memoryDao(): MemoryDao

  abstract fun skillDao(): SkillDao

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

    /**
     * Combined schema migration for multi-file attachments (`message_attachments`) and the
     * custom skills system (`skills`, `skill_files`, `messages.activeSkillName`).
     */
    val MIGRATION_3_4: Migration =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE messages ADD COLUMN activeSkillName TEXT")

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_attachments` (
              `id` TEXT NOT NULL,
              `messageId` TEXT NOT NULL,
              `type` TEXT NOT NULL,
              `displayName` TEXT NOT NULL,
              `mimeType` TEXT NOT NULL,
              `localPath` TEXT NOT NULL,
              `sizeBytes` INTEGER NOT NULL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_attachments_messageId` ON `message_attachments` (`messageId`)"
          )

          // Migrate legacy single-image column entries into the new multi-attachment table.
          db.execSQL(
            """
            INSERT INTO `message_attachments` (`id`, `messageId`, `type`, `displayName`, `mimeType`, `localPath`, `sizeBytes`)
            SELECT
              'legacy_' || `id`,
              `id`,
              'image',
              'image.jpg',
              'image/jpeg',
              `imageUri`,
              0
            FROM `messages`
            WHERE `imageUri` IS NOT NULL AND TRIM(`imageUri`) != ''
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `skills` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `description` TEXT NOT NULL,
              `skillMarkdown` TEXT NOT NULL,
              `isEnabled` INTEGER NOT NULL,
              `source` TEXT NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_skills_updatedAt` ON `skills` (`updatedAt`)"
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `skill_files` (
              `id` TEXT NOT NULL,
              `skillId` TEXT NOT NULL,
              `fileName` TEXT NOT NULL,
              `content` TEXT NOT NULL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`skillId`) REFERENCES `skills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_skill_files_skillId` ON `skill_files` (`skillId`)"
          )
        }
      }

    /**
     * Combined schema migration for pinned conversations (`isPinned`), conversation folders
     * (`folders` table + `folderId` FK with `ON DELETE SET NULL`), and conversation branching
     * (`branchedFromConversationId`).
     */
    val MIGRATION_4_5: Migration =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `folders` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `colorTag` TEXT,
              `createdAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )
          db.execSQL("ALTER TABLE `conversations` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
          db.execSQL(
            "ALTER TABLE `conversations` ADD COLUMN `folderId` TEXT REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"
          )
          db.execSQL(
            "ALTER TABLE `conversations` ADD COLUMN `branchedFromConversationId` TEXT"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversations_folderId` ON `conversations` (`folderId`)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversations_isPinned_updatedAt` ON `conversations` (`isPinned`, `updatedAt`)"
          )
        }
      }

    fun create(context: Context): VedDatabase =
      Room.databaseBuilder(context.applicationContext, VedDatabase::class.java, DB_NAME)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()
  }
}
