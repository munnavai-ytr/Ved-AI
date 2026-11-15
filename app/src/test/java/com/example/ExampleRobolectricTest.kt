package com.example

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.VedDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.ThemeMode
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.IncrementalMarkdownParser
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.StreamingChatMessageItem
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.VedTheme
import com.example.ui.viewmodel.ChatUiState
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.data.local.entity.MemoryEntity
import com.example.data.model.ChatBackup
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.OpenRouterApiService
import com.example.data.remote.StreamingAiClient
import com.example.data.repository.ChatRepository
import com.example.data.security.ApiKeyStore
import com.example.data.util.MemoryPromptHelper
import com.example.ui.screens.MemoryScreen
import com.example.ui.viewmodel.MemoryViewModel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `app name resolves to Ved`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Ved", appName)
  }

  @Test
  fun `room dao inserts updates and cascades delete`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, VedDatabase::class.java).build()
    try {
      val dao = db.chatDao()
      val conv =
        ConversationEntity(
          id = "conv-1",
          title = "Initial title",
          provider = "gemini",
          modelId = "gemini-2.5-flash",
          createdAt = 100L,
          updatedAt = 100L,
        )
      dao.insertConversation(conv)
      dao.insertMessage(
        MessageEntity(
          id = "msg-1",
          conversationId = "conv-1",
          role = MessageEntity.ROLE_USER,
          content = "Hello Ved",
          createdAt = 101L,
        )
      )

      dao.updateConversationTitle("conv-1", "Kotlin Flows", 200L)
      val conversations = dao.observeConversations().first()
      assertEquals(1, conversations.size)
      assertEquals("Kotlin Flows", conversations.first().title)

      val messages = dao.observeMessages("conv-1").first()
      assertEquals(1, messages.size)

      dao.deleteConversationById("conv-1")
      assertTrue(dao.observeConversations().first().isEmpty())
      assertTrue(dao.observeMessages("conv-1").first().isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun `room migration 1 to 2 preserves messages and adds image and token columns`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_test_ved.db"
    context.deleteDatabase(dbName)

    val helper =
      FrameworkSQLiteOpenHelperFactory()
        .create(
          SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
              object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                  db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                      `id` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `provider` TEXT NOT NULL,
                      `modelId` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                  )
                  db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                      `id` TEXT NOT NULL,
                      `conversationId` TEXT NOT NULL,
                      `role` TEXT NOT NULL,
                      `content` TEXT NOT NULL,
                      `modelId` TEXT,
                      `isError` INTEGER NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      PRIMARY KEY(`id`),
                      FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                  )
                  db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)"
                  )
                  db.execSQL(
                    "INSERT INTO conversations VALUES ('c1', 'Legacy Chat', 'gemini', 'gemini-2.5-flash', 100, 100)"
                  )
                  db.execSQL(
                    "INSERT INTO messages VALUES ('m1', 'c1', 'user', 'Saved prompt', NULL, 0, 101)"
                  )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
              }
            )
            .build()
        )
    helper.writableDatabase.close()
    helper.close()

    val migratedDb =
      Room.databaseBuilder(context, VedDatabase::class.java, dbName)
        .addMigrations(VedDatabase.MIGRATION_1_2, VedDatabase.MIGRATION_2_3)
        .build()
    try {
      val dao = migratedDb.chatDao()
      val msgs = dao.getMessagesSync("c1")
      assertEquals(1, msgs.size)
      assertEquals("Saved prompt", msgs.first().content)
      assertNull(msgs.first().imageUri)
      assertNull(msgs.first().completionTokens)

      dao.insertMessage(
        MessageEntity(
          id = "m2",
          conversationId = "c1",
          role = MessageEntity.ROLE_ASSISTANT,
          content = "Reply",
          modelId = "gemini-2.5-flash",
          createdAt = 102L,
          imageUri = "file:///tmp/test.jpg",
          promptTokens = 12,
          completionTokens = 34,
        )
      )
      val after = dao.getMessagesSync("c1")
      assertEquals(2, after.size)
      assertEquals("file:///tmp/test.jpg", after[1].imageUri)
      assertEquals(12, after[1].promptTokens)
      assertEquals(34, after[1].completionTokens)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `searchConversations matches titles and message contents and deleteMessagesFrom trims tail`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db = Room.inMemoryDatabaseBuilder(context, VedDatabase::class.java).build()
      try {
        val dao = db.chatDao()
        dao.insertConversation(
          ConversationEntity("c1", "Kotlin Tips", "gemini", "gemini-2.5-flash", 10L, 100L)
        )
        dao.insertConversation(
          ConversationEntity("c2", "Weekend Recipe", "gemini", "gemini-2.5-flash", 20L, 200L)
        )
        dao.insertMessage(MessageEntity("m1", "c1", MessageEntity.ROLE_USER, "Explain flows", createdAt = 1L))
        dao.insertMessage(MessageEntity("m2", "c1", MessageEntity.ROLE_ASSISTANT, "Flows emit sequentially", createdAt = 2L))
        dao.insertMessage(MessageEntity("m3", "c1", MessageEntity.ROLE_USER, "Second turn", createdAt = 3L))
        dao.insertMessage(MessageEntity("m4", "c2", MessageEntity.ROLE_USER, "Use room temperature butter", createdAt = 4L))

        val titleHits = dao.searchConversations("Kotlin").first()
        assertEquals(listOf("c1"), titleHits.map { it.id })

        val contentHits = dao.searchConversations("butter").first()
        assertEquals(listOf("c2"), contentHits.map { it.id })

        dao.deleteMessagesFrom("c1", fromTimestamp = 2L)
        val remaining = dao.getMessagesSync("c1")
        assertEquals(listOf("m1"), remaining.map { it.id })
      } finally {
        db.close()
      }
    }

  @Test
  fun `backup serialization round trip and importBackup merges without wiping existing chats`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db = Room.inMemoryDatabaseBuilder(context, VedDatabase::class.java).build()
      try {
        val dao = db.chatDao()
        dao.insertConversation(
          ConversationEntity("existing", "Existing Chat", "gemini", "gemini-2.5-flash", 10L, 10L)
        )

        val backup =
          ChatBackup(
            version = ChatBackup.CURRENT_VERSION,
            exportedAt = 500L,
            conversations =
              listOf(
                ConversationEntity(
                  "imported",
                  "Imported Chat",
                  "openrouter",
                  "openai/gpt-4o",
                  20L,
                  200L,
                )
              ),
            messages =
              listOf(
                MessageEntity(
                  id = "imp-m1",
                  conversationId = "imported",
                  role = MessageEntity.ROLE_ASSISTANT,
                  content = "Restored reply",
                  createdAt = 21L,
                  promptTokens = 10,
                  completionTokens = 42,
                )
              ),
          )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(ChatBackup.serializer(), backup)
        val decoded = json.decodeFromString(ChatBackup.serializer(), encoded)

        dao.importBackup(decoded.conversations, decoded.messages)

        val allConvs = dao.getAllConversationsSync()
        assertEquals(2, allConvs.size)
        assertEquals(setOf("existing", "imported"), allConvs.map { it.id }.toSet())
        val importedMsgs = dao.getMessagesSync("imported")
        assertEquals(42, importedMsgs.first().completionTokens)
      } finally {
        db.close()
      }
    }

  @Test
  fun `chat message item displays edit on user message and token count on assistant message`() {
    val userMsg =
      MessageEntity(
        id = "u1",
        conversationId = "c1",
        role = MessageEntity.ROLE_USER,
        content = "Hello Ved",
        createdAt = 1L,
      )
    val assistantMsg =
      MessageEntity(
        id = "a1",
        conversationId = "c1",
        role = MessageEntity.ROLE_ASSISTANT,
        content = "Hi there!",
        modelId = "gemini-2.5-flash",
        createdAt = 2L,
        completionTokens = 42,
      )

    composeTestRule.setContent {
      VedTheme(themeMode = ThemeMode.LIGHT) {
        LazyColumn {
          item {
            ChatMessageItem(
              message = userMsg,
              displayContent = userMsg.content,
              isStreaming = false,
              isLastAssistantMessage = false,
              onRegenerate = {},
              onEditMessage = {},
            )
          }
          item {
            ChatMessageItem(
              message = assistantMsg,
              displayContent = assistantMsg.content,
              isStreaming = false,
              isLastAssistantMessage = true,
              onRegenerate = {},
            )
          }
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("edit_message_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("message_token_count").assertIsDisplayed()
    composeTestRule.onNodeWithText("42 tokens").assertIsDisplayed()
  }

  @Test
  fun `room migration 2 to 3 creates memories table and preserves v2 chats`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_v2_v3_ved.db"
    context.deleteDatabase(dbName)

    val helper =
      FrameworkSQLiteOpenHelperFactory()
        .create(
          SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
              object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                  db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                      `id` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `provider` TEXT NOT NULL,
                      `modelId` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                  )
                  db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                      `id` TEXT NOT NULL,
                      `conversationId` TEXT NOT NULL,
                      `role` TEXT NOT NULL,
                      `content` TEXT NOT NULL,
                      `modelId` TEXT,
                      `isError` INTEGER NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `imageUri` TEXT,
                      `promptTokens` INTEGER,
                      `completionTokens` INTEGER,
                      PRIMARY KEY(`id`),
                      FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                  )
                  db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)"
                  )
                  db.execSQL(
                    "INSERT INTO conversations VALUES ('c2', 'Existing v2 Chat', 'gemini', 'gemini-2.5-flash', 200, 200)"
                  )
                  db.execSQL(
                    "INSERT INTO messages VALUES ('m2', 'c2', 'user', 'Remember this chat', NULL, 0, 201, NULL, NULL, NULL)"
                  )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
              }
            )
            .build()
        )
    helper.writableDatabase.close()
    helper.close()

    val migratedDb =
      Room.databaseBuilder(context, VedDatabase::class.java, dbName)
        .addMigrations(VedDatabase.MIGRATION_1_2, VedDatabase.MIGRATION_2_3)
        .build()
    try {
      val msgs = migratedDb.chatDao().getMessagesSync("c2")
      assertEquals(1, msgs.size)
      assertEquals("Remember this chat", msgs.first().content)

      val memoryDao = migratedDb.memoryDao()
      memoryDao.insertMemory(
        MemoryEntity(
          id = "mem-1",
          content = "User builds Android apps in Kotlin",
          category = "profile",
          source = MemoryEntity.SOURCE_AI,
          createdAt = 300L,
          updatedAt = 300L,
        )
      )
      val allMemories = memoryDao.getAllMemoriesSync()
      assertEquals(1, allMemories.size)
      assertEquals("User builds Android apps in Kotlin", allMemories.first().content)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `memory dao supports CRUD search update and clear all`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, VedDatabase::class.java).build()
    try {
      val dao = db.memoryDao()
      dao.insertMemory(
        MemoryEntity("1", "Name is M", "profile", MemoryEntity.SOURCE_AI, 10L, 100L)
      )
      dao.insertMemory(
        MemoryEntity("2", "Prefers concise replies", "preference", MemoryEntity.SOURCE_USER, 20L, 200L)
      )

      val ordered = dao.observeAllMemories().first()
      assertEquals(listOf("2", "1"), ordered.map { it.id })

      val hits = dao.searchMemories("concise").first()
      assertEquals(listOf("2"), hits.map { it.id })

      dao.updateMemoryContent("1", "User name is M", updatedAt = 300L)
      val afterUpdate = dao.getAllMemoriesSync()
      assertEquals("User name is M", afterUpdate.first().content)

      dao.deleteMemoryById("2")
      assertEquals(1, dao.getAllMemoriesSync().size)

      dao.deleteAllMemories()
      assertTrue(dao.getAllMemoriesSync().isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun `memory prompt helper strips tags caps budget and builds combined system prompt`() {
    val raw =
      "Nice to meet you, M!\n" +
        "[MEMORY_SAVE:profile] User's name is M [/MEMORY_SAVE]\n\n" +
        "I'll keep answers short.\n" +
        "[MEMORY_SAVE:preference] Prefers concise replies [/MEMORY_SAVE]"

    val parsed = MemoryPromptHelper.extractAndStripMemories(raw)
    assertEquals(2, parsed.extractedMemories.size)
    assertEquals("profile", parsed.extractedMemories[0].category)
    assertEquals("User's name is M", parsed.extractedMemories[0].content)
    assertEquals("preference", parsed.extractedMemories[1].category)
    assertEquals("Prefers concise replies", parsed.extractedMemories[1].content)
    assertTrue(!parsed.cleanText.contains("MEMORY_SAVE"))
    assertEquals("Nice to meet you, M!\n\nI'll keep answers short.", parsed.cleanText)

    // Partial streaming tag hiding
    val partialStream = "Got it! [MEMORY_SAVE:project] Building Ved"
    assertEquals("Got it!", MemoryPromptHelper.stripStreamingTags(partialStream))

    // Empty memories -> empty context block
    assertEquals("", MemoryPromptHelper.formatMemoryContextBlock(emptyList()))

    // Budget cap enforcement (4000 chars)
    val hugeMemories =
      (1..60).map { i ->
        MemoryEntity(
          id = "m$i",
          content = "Memory item $i " + "x".repeat(120),
          category = "fact",
          source = MemoryEntity.SOURCE_AI,
          createdAt = i.toLong(),
          updatedAt = i.toLong(),
        )
      }
    val block = MemoryPromptHelper.formatMemoryContextBlock(hugeMemories)
    assertTrue(block.length <= 4000)
    assertTrue(block.startsWith("## What you remember about this user"))

    // Combined prompt ordering: custom -> memory instructions -> memory context
    val combined =
      MemoryPromptHelper.buildCombinedSystemInstruction(
        customSystemPrompt = "Be friendly.",
        memoryContextBlock = "## What you remember about this user (long-term memory):\n- [profile] Name is M",
      )
    assertTrue(combined.indexOf("Be friendly.") < combined.indexOf("[MEMORY_SAVE:category]"))
    assertTrue(
      combined.indexOf("[MEMORY_SAVE:category]") < combined.indexOf("What you remember about this user")
    )
  }

  @Test
  fun `memory screen renders empty state and saved memories`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, VedDatabase::class.java).build()
    try {
      val fakeRetrofit =
        Retrofit.Builder().baseUrl("https://openrouter.ai/").client(OkHttpClient()).build()
      val fakeApi = fakeRetrofit.create(OpenRouterApiService::class.java)
      val repo =
        ChatRepository(
          appContext = context,
          chatDao = db.chatDao(),
          memoryDao = db.memoryDao(),
          streamingClient = StreamingAiClient(OkHttpClient()),
          openRouterApi = fakeApi,
          apiKeyStore = ApiKeyStore(context),
          preferencesRepository = UserPreferencesRepository(context),
        )
      val vm = MemoryViewModel(repo)

      composeTestRule.setContent {
        VedTheme(themeMode = ThemeMode.LIGHT) {
          MemoryScreen(viewModel = vm, onNavigateBack = {})
        }
      }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("add_memory_button").assertIsDisplayed()
      composeTestRule.onNodeWithText("No memories yet").assertIsDisplayed()

      repo.addMemory("Building Play Nexa", "project", MemoryEntity.SOURCE_USER)
      composeTestRule.waitUntil(timeoutMillis = 5_000L) {
        composeTestRule.onAllNodesWithText("Building Play Nexa").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithText("Building Play Nexa").assertIsDisplayed()
      composeTestRule.onNodeWithTag("clear_all_memories_button").assertIsDisplayed()
    } finally {
      db.close()
    }
  }

  @Test
  fun `onboarding screen renders skip and next controls`() {
    composeTestRule.setContent {
      VedTheme(themeMode = ThemeMode.LIGHT) { OnboardingScreen(onFinish = {}) }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("onboarding_skip_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("onboarding_next_button").assertIsDisplayed()
  }

  @Test
  fun `incremental markdown parser settles earlier blocks and scales with delta`() {
    val parser = IncrementalMarkdownParser()

    val chunk1 = "# Title\n\nFirst paragraph with **bold** text.\n\n```kotlin\nval a = 1\n```\n\n"
    val res1 = parser.parse(chunk1)
    assertEquals(3, res1.blocks.size)
    assertEquals(3, res1.settledCount)
    assertTrue(res1.blocks[0] is MarkdownBlock.Heading)
    assertTrue(res1.blocks[1] is MarkdownBlock.Paragraph)
    assertTrue(res1.blocks[2] is MarkdownBlock.CodeBlock)

    // Append 1000+ words across multiple paragraphs while streaming token by token
    val longTail = buildString {
      append(chunk1)
      repeat(20) { p ->
        append("## Section $p\n\n")
        repeat(60) { w -> append("word$w ") }
        append("\n\n")
      }
    }
    val step1 = longTail.dropLast(40)
    val res2 = parser.parse(step1)
    assertTrue("Expected settled blocks to advance past prefix", res2.settledCount > res1.settledCount)

    // Final token append only scans from settledLength (trailing paragraph), not offset 0
    val res3 = parser.parse(longTail)
    assertTrue(
      "Expected final append to resume near end (${parser.lastScannedOffset}) instead of 0",
      parser.lastScannedOffset > longTail.length - 500,
    )
    assertEquals(res3.blocks, IncrementalMarkdownParser().parse(longTail).blocks)
  }

  @Test
  fun `streaming tokens recompose only the active streaming message item`() {
    val settledUser =
      MessageEntity(
        id = "msg-user",
        conversationId = "conv-1",
        role = MessageEntity.ROLE_USER,
        content = "Write a long answer",
        createdAt = 1L,
      )
    val settledAssistant =
      MessageEntity(
        id = "msg-old-assistant",
        conversationId = "conv-1",
        role = MessageEntity.ROLE_ASSISTANT,
        content = "Earlier settled answer with **markdown**.",
        modelId = "gemini-2.5-flash",
        createdAt = 2L,
      )
    val streamingAssistant =
      MessageEntity(
        id = "msg-streaming",
        conversationId = "conv-1",
        role = MessageEntity.ROLE_ASSISTANT,
        content = "",
        modelId = "gemini-2.5-flash",
        createdAt = 3L,
      )

    val uiStateFlow =
      MutableStateFlow(
        ChatUiState(
          messages = listOf(settledUser, settledAssistant, streamingAssistant),
          streamingMessageId = streamingAssistant.id,
          isGenerating = true,
        )
      )
    val streamingTextFlow = MutableStateFlow("")

    var settledUserRecompositions = 0
    var settledAssistantRecompositions = 0
    var streamingItemRecompositions = 0
    val stableRegenerate = {}

    composeTestRule.setContent {
      VedTheme(themeMode = ThemeMode.LIGHT) {
        val state by uiStateFlow.collectAsState()
        LazyColumn {
          itemsIndexed(
            items = state.messages,
            key = { _, m -> m.id },
            contentType = { _, m -> m.role },
          ) { _, message ->
            if (message.id == state.streamingMessageId) {
              SideEffect { streamingItemRecompositions++ }
              StreamingChatMessageItem(
                message = message,
                streamingTextFlow = streamingTextFlow,
                isLastAssistantMessage = true,
                onRegenerate = stableRegenerate,
              )
            } else {
              SideEffect {
                if (message.id == settledUser.id) settledUserRecompositions++
                if (message.id == settledAssistant.id) settledAssistantRecompositions++
              }
              ChatMessageItem(
                message = message,
                displayContent = message.content,
                isStreaming = false,
                isLastAssistantMessage = false,
                onRegenerate = stableRegenerate,
              )
            }
          }
        }
      }
    }

    composeTestRule.waitForIdle()
    val initialUserCount = settledUserRecompositions
    val initialOldAssistantCount = settledAssistantRecompositions
    val initialStreamingCount = streamingItemRecompositions

    // Stream 25 incremental token bursts up to 1000+ words
    val sb = StringBuilder()
    repeat(25) { burst ->
      repeat(45) { w -> sb.append("token${burst}_$w ") }
      if (burst % 5 == 4) sb.append("\n\n")
      streamingTextFlow.value = sb.toString()
      composeTestRule.waitForIdle()
    }

    assertEquals(
      "Settled user bubble must not recompose during streaming",
      initialUserCount,
      settledUserRecompositions,
    )
    assertEquals(
      "Earlier settled assistant message must not recompose during streaming",
      initialOldAssistantCount,
      settledAssistantRecompositions,
    )
    assertTrue(
      "Active streaming item should have updated (initial=$initialStreamingCount)",
      streamingItemRecompositions >= initialStreamingCount,
    )
  }
}
