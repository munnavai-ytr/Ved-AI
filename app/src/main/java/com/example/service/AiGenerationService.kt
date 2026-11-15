package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.VedApplication

/**
 * Foreground Service keeping active AI streaming alive across background transitions and screen lock,
 * displaying a silent ongoing notification and posting a completion notification when finished.
 */
class AiGenerationService : Service() {

  private val coordinator: AiGenerationCoordinator by lazy {
    (application as VedApplication).container.aiGenerationCoordinator
  }

  private val callbacks =
    object : AiGenerationCoordinator.ServiceCallbacks {
      override fun onCompleted(
        conversationId: String,
        conversationTitle: String,
        isError: Boolean,
      ) {
        VedNotifications.notifyCompletion(
          context = applicationContext,
          conversationId = conversationId,
          conversationTitle = conversationTitle,
          isError = isError,
        )
        ServiceCompat.stopForeground(this@AiGenerationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
      }

      override fun onStoppedOrCanceled() {
        ServiceCompat.stopForeground(this@AiGenerationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }

  override fun onCreate() {
    super.onCreate()
    VedNotifications.ensureChannels(this)
    coordinator.attachServiceCallbacks(callbacks)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        coordinator.stopGeneration()
        return START_NOT_STICKY
      }
      ACTION_START,
      null -> {
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        val conversationTitle = intent?.getStringExtra(EXTRA_CONVERSATION_TITLE).orEmpty()

        val stopPendingIntent =
          PendingIntent.getService(
            this,
            99,
            Intent(this, AiGenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
        val notification =
          VedNotifications.buildOngoingNotification(
            context = this,
            conversationId = conversationId,
            conversationTitle = conversationTitle,
            stopPendingIntent = stopPendingIntent,
          )

        val fgType =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
          } else {
            0
          }
        ServiceCompat.startForeground(
          this,
          VedNotifications.NOTIFICATION_ID_ONGOING,
          notification,
          fgType,
        )

        coordinator.executeActiveRunIfNeeded()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    coordinator.attachServiceCallbacks(null)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val ACTION_START = "com.example.action.START_AI_GENERATION"
    const val ACTION_STOP = "com.example.action.STOP_AI_GENERATION"
    const val EXTRA_CONVERSATION_ID = "extra_service_conversation_id"
    const val EXTRA_CONVERSATION_TITLE = "extra_service_conversation_title"

    fun createStartIntent(
      context: Context,
      conversationId: String,
      conversationTitle: String,
    ): Intent {
      return Intent(context, AiGenerationService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
        putExtra(EXTRA_CONVERSATION_TITLE, conversationTitle)
      }
    }
  }
}
