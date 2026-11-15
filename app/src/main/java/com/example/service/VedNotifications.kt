package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Notification channels and builders for ongoing foreground generation (silent low priority) and
 * completion alerts (normal priority with conversation deep-link PendingIntent).
 */
object VedNotifications {

  private const val TAG = "VedNotifications"

  const val CHANNEL_ONGOING = "ved_generation_ongoing"
  const val CHANNEL_COMPLETION = "ved_generation_complete"

  const val NOTIFICATION_ID_ONGOING = 4001
  const val EXTRA_CONVERSATION_ID = "extra_conversation_id"

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return

    if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
      val ongoing =
        NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.notification_channel_ongoing_name),
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            description = context.getString(R.string.notification_channel_ongoing_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
          }
      manager.createNotificationChannel(ongoing)
    }

    if (manager.getNotificationChannel(CHANNEL_COMPLETION) == null) {
      val completion =
        NotificationChannel(
            CHANNEL_COMPLETION,
            context.getString(R.string.notification_channel_complete_name),
            NotificationManager.IMPORTANCE_DEFAULT,
          )
          .apply {
            description = context.getString(R.string.notification_channel_complete_desc)
          }
      manager.createNotificationChannel(completion)
    }
  }

  fun buildOngoingNotification(
    context: Context,
    conversationId: String?,
    conversationTitle: String,
    stopPendingIntent: PendingIntent,
  ): Notification {
    ensureChannels(context)
    val contentIntent = buildOpenConversationPendingIntent(context, conversationId, requestCode = 1)
    val safeTitle = conversationTitle.trim().take(48).ifBlank { context.getString(R.string.app_name) }

    return NotificationCompat.Builder(context, CHANNEL_ONGOING)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(context.getString(R.string.notification_generating_title))
      .setContentText(safeTitle)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setProgress(0, 0, true)
      .setContentIntent(contentIntent)
      .addAction(
        android.R.drawable.ic_media_pause,
        context.getString(R.string.cd_stop_generation),
        stopPendingIntent,
      )
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  fun notifyCompletion(
    context: Context,
    conversationId: String,
    conversationTitle: String,
    isError: Boolean,
  ) {
    ensureChannels(context)
    if (!canPostNotifications(context)) return

    val truncatedTitle =
      conversationTitle.trim().let { raw ->
        when {
          raw.isEmpty() -> "your chat"
          raw.length <= 42 -> raw
          else -> raw.take(40).trimEnd() + "…"
        }
      }

    val bodyText =
      if (isError) {
        context.getString(R.string.notification_error_body, truncatedTitle)
      } else {
        context.getString(R.string.notification_ready_body, truncatedTitle)
      }

    val openIntent =
      buildOpenConversationPendingIntent(
        context = context,
        conversationId = conversationId,
        requestCode = conversationId.hashCode(),
      )

    val notification =
      NotificationCompat.Builder(context, CHANNEL_COMPLETION)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(bodyText)
        .setAutoCancel(true)
        .setContentIntent(openIntent)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    try {
      val manager = context.getSystemService(NotificationManager::class.java)
      manager?.notify(completionNotificationId(conversationId), notification)
    } catch (se: SecurityException) {
      Log.w(TAG, "Unable to post completion notification due to missing permission", se)
    }
  }

  fun canPostNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun buildOpenConversationPendingIntent(
    context: Context,
    conversationId: String?,
    requestCode: Int,
  ): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (!conversationId.isNullOrBlank()) {
          putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
      }
    return PendingIntent.getActivity(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun completionNotificationId(conversationId: String): Int {
    // Keep ID strictly distinct from NOTIFICATION_ID_ONGOING
    return 5000 + (conversationId.hashCode() and 0x7FFF)
  }
}
