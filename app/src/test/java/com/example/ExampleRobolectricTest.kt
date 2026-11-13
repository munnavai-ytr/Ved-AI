package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.VedDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.ThemeMode
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.VedTheme
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
}
