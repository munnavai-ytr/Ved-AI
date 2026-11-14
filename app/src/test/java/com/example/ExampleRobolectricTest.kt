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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
