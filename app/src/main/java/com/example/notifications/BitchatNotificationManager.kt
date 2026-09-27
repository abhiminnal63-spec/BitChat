package com.example.notifications

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.MessageEntity
import com.example.data.model.buildDeterministicConversationId
import com.example.data.realtime.RealtimeManager
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

object BitchatNotificationManager {
    const val CHANNEL_ID = "bitchat_messages_channel"
    const val CHANNEL_NAME = "BITCHAT Messages"
    const val GROUP_KEY_MESSAGES = "com.example.bitchat.MESSAGE_GROUP"
    const val SUMMARY_NOTIFICATION_ID = 900000

    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_OTHER_USER_ID = "extra_other_user_id"
    const val EXTRA_SENDER_NAME = "extra_sender_name"
    const val EXTRA_FROM_NOTIFICATION = "extra_from_notification"

    private const val PREFS_NAME = "bitchat_notified_messages_prefs"
    private const val KEY_NOTIFIED_IDS = "notified_message_ids"
    private const val KEY_GROUPED_CONV_LINES = "grouped_conversation_lines_v1"

    data class QueuedNotificationLine(
        val messageId: String,
        val senderId: String,
        val senderName: String,
        val displayBody: String,
        val timestamp: Long
    ) {
        val body: String get() = displayBody
    }

    fun setAppInForeground(inForeground: Boolean) {
        RealtimeManager.setAppInForeground(inForeground)
    }

    // Tracks grouped unread messages per conversation/contact for WhatsApp-style MessagingStyle consolidation
    private val activeConversationLines = ConcurrentHashMap<String, MutableList<QueuedNotificationLine>>()
    // Maps "recipientId|senderId" -> canonical conversation key so multiple messages from the same contact always consolidate
    private val contactConversationKeys = ConcurrentHashMap<String, String>()
    private val notifiedMessageIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var lastAppContext: Context? = null

    fun ensureNotificationChannel(context: Context) {
        lastAppContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Real-time and offline message notifications for BITCHAT"
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Resolves a consistent conversation key for a contact so multiple messages from the same
     * sender to the same recipient are always consolidated into a single notification.
     */
    fun resolveConsolidatedConversationKey(
        conversationId: String,
        senderId: String = "",
        recipientId: String = ""
    ): String {
        val cleanConv = conversationId.trim()
        val cleanSender = senderId.trim()
        val cleanRecipient = recipientId.trim()

        if (cleanSender.isNotBlank() && cleanRecipient.isNotBlank()) {
            val contactPairKey = "$cleanRecipient|$cleanSender"
            val existingKey = contactConversationKeys[contactPairKey]
            if (!existingKey.isNullOrBlank()) {
                return existingKey
            }
            val resolved = cleanConv.ifBlank {
                buildDeterministicConversationId(cleanSender, cleanRecipient)
            }
            contactConversationKeys[contactPairKey] = resolved
            return resolved
        }

        return cleanConv
    }

    /**
     * Formats message body for BITCHAT notification display:
     * - Text message: "Hello"
     * - Emoji message: "❤️"
     * - Image message: "📷 Photo"
     */
    fun formatNotificationBody(type: String, content: String, hasMedia: Boolean = false): String {
        val isImage = type.equals("image", ignoreCase = true) || hasMedia
        return if (isImage) {
            "📷 Photo"
        } else {
            content.trim().ifBlank { "New message" }
        }
    }

    fun formatNotificationBody(type: String, content: String, mediaUrl: String?): String {
        return formatNotificationBody(type, content, !mediaUrl.isNullOrBlank())
    }

    /**
     * Determines whether a notification should be suppressed because the recipient is
     * currently online, BITCHAT is in the foreground, and the recipient is actively inside this conversation.
     * (CASE 1)
     */
    fun shouldSuppressNotificationForActiveConversation(
        recipientId: String,
        conversationId: String
    ): Boolean {
        if (!RealtimeManager.isAppInForeground.value) return false
        return RealtimeManager.isUserViewingConversation(recipientId, conversationId)
    }

    private fun hasAlreadyNotified(context: Context, messageId: String): Boolean {
        if (notifiedMessageIds.contains(messageId)) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_NOTIFIED_IDS, emptySet()) ?: emptySet()
        if (stored.contains(messageId)) {
            notifiedMessageIds.add(messageId)
            return true
        }
        return false
    }

    private fun markMessageNotified(context: Context, messageId: String) {
        notifiedMessageIds.add(messageId)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_NOTIFIED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(messageId)
        if (existing.size > 500) {
            val trimmed = existing.toList().takeLast(400).toSet()
            prefs.edit().putStringSet(KEY_NOTIFIED_IDS, trimmed).apply()
        } else {
            prefs.edit().putStringSet(KEY_NOTIFIED_IDS, existing).apply()
        }
    }

    private fun persistGroupedLines(context: Context) {
        try {
            val root = JSONObject()
            synchronized(activeConversationLines) {
                for ((convId, lines) in activeConversationLines) {
                    val arr = JSONArray()
                    for (item in lines.takeLast(20)) {
                        arr.put(
                            JSONObject().apply {
                                put("messageId", item.messageId)
                                put("senderId", item.senderId)
                                put("senderName", item.senderName)
                                put("displayBody", item.displayBody)
                                put("timestamp", item.timestamp)
                            }
                        )
                    }
                    root.put(convId, arr)
                }
            }
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GROUPED_CONV_LINES, root.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreGroupedLinesIfNeeded(context: Context, conversationId: String) {
        synchronized(activeConversationLines) {
            if (activeConversationLines.containsKey(conversationId)) return
            try {
                val raw = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_GROUPED_CONV_LINES, null) ?: return
                val root = JSONObject(raw)
                val arr = root.optJSONArray(conversationId) ?: return
                val restored = mutableListOf<QueuedNotificationLine>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val msgId = obj.optString("messageId", "")
                    if (msgId.isBlank()) continue
                    restored.add(
                        QueuedNotificationLine(
                            messageId = msgId,
                            senderId = obj.optString("senderId", ""),
                            senderName = obj.optString("senderName", "Contact"),
                            displayBody = obj.optString("displayBody", ""),
                            timestamp = obj.optLong("timestamp", 0L)
                        )
                    )
                }
                if (restored.isNotEmpty()) {
                    activeConversationLines[conversationId] = restored
                }
            } catch (_: Exception) {
            }
        }
    }

    fun buildConversationTapIntent(
        context: Context,
        conversationId: String,
        otherUserId: String,
        senderName: String
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_OTHER_USER_ID, otherUserId)
            putExtra(EXTRA_SENDER_NAME, senderName)
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }
    }

    fun showIncomingMessageNotification(
        context: Context,
        recipientUid: String,
        senderUid: String,
        senderDisplayName: String,
        senderUsername: String = "",
        conversationId: String,
        messageId: String,
        messageType: String,
        messageText: String,
        mediaUrl: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val resolvedConvId = resolveConsolidatedConversationKey(
            conversationId = conversationId,
            senderId = senderUid,
            recipientId = recipientUid
        )
        val msg = MessageEntity(
            id = messageId,
            conversationId = resolvedConvId,
            senderId = senderUid,
            recipientId = recipientUid,
            content = messageText,
            timestamp = timestamp,
            type = messageType,
            mediaUrl = mediaUrl
        )
        val display = senderDisplayName.ifBlank { senderUsername.removePrefix("@") }
        return showIncomingMessageNotification(
            context = context,
            recipientId = recipientUid,
            message = msg,
            senderDisplayName = display
        )
    }

    /**
     * Posts or updates a consolidated conversation notification in the Android notification shade.
     * Multiple messages from the same contact share the same notificationId and are consolidated
     * into a single expandable MessagingStyle conversation notification.
     * Returns true if a notification was posted, false if suppressed (e.g. CASE 1 active chat or duplicate).
     */
    @SuppressLint("MissingPermission")
    fun showIncomingMessageNotification(
        context: Context,
        recipientId: String,
        message: MessageEntity,
        senderDisplayName: String
    ): Boolean {
        lastAppContext = context.applicationContext
        val cleanRecipient = recipientId.trim()
        val cleanSender = message.senderId.trim()
        val cleanConvId = resolveConsolidatedConversationKey(
            conversationId = message.conversationId,
            senderId = cleanSender,
            recipientId = cleanRecipient
        )
        val cleanMsgId = message.id.trim()
        if (cleanRecipient.isBlank() || cleanConvId.isBlank() || cleanMsgId.isBlank()) return false
        if (cleanSender == cleanRecipient) return false

        // CASE 1: @brutt is online and actively inside the conversation -> do not create duplicate notification
        if (shouldSuppressNotificationForActiveConversation(cleanRecipient, cleanConvId) ||
            shouldSuppressNotificationForActiveConversation(cleanRecipient, message.conversationId.trim())
        ) {
            return false
        }

        // Prevent duplicate notification for the same messageId
        if (hasAlreadyNotified(context, cleanMsgId)) {
            return false
        }
        markMessageNotified(context, cleanMsgId)

        ensureNotificationChannel(context)
        restoreGroupedLinesIfNeeded(context, cleanConvId)

        val resolvedSenderName = senderDisplayName.trim().removePrefix("@").ifBlank { "Contact" }
        val isImage = message.type.equals("image", ignoreCase = true) ||
            !message.mediaUrl.isNullOrBlank() ||
            !message.attachmentUri.isNullOrBlank()
        val bodyText = formatNotificationBody(
            type = if (isImage) "image" else message.type,
            content = message.content,
            hasMedia = isImage
        )

        val line = QueuedNotificationLine(
            messageId = cleanMsgId,
            senderId = cleanSender,
            senderName = resolvedSenderName,
            displayBody = bodyText,
            timestamp = if (message.timestamp > 0L) message.timestamp else System.currentTimeMillis()
        )

        val linesForConv = synchronized(activeConversationLines) {
            val list = activeConversationLines.getOrPut(cleanConvId) { mutableListOf() }
            if (list.none { it.messageId == cleanMsgId }) {
                list.add(line)
                list.sortBy { it.timestamp }
            }
            list.toList()
        }
        persistGroupedLines(context)

        val latestLine = linesForConv.lastOrNull() ?: line

        val tapIntent = buildConversationTapIntent(
            context = context,
            conversationId = cleanConvId,
            otherUserId = cleanSender,
            senderName = resolvedSenderName
        )

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val notificationId = notificationIdForConversation(cleanConvId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            pendingFlags
        )

        val senderPerson = Person.Builder()
            .setName(resolvedSenderName)
            .setKey(cleanSender)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(senderPerson)
            .setConversationTitle("BITCHAT")
            .setGroupConversation(false)

        for (queued in linesForConv.takeLast(10)) {
            val msgPerson = Person.Builder()
                .setName(queued.senderName)
                .setKey(queued.senderId)
                .build()
            messagingStyle.addMessage(queued.displayBody, queued.timestamp, msgPerson)
        }

        val summaryLinesArray = linesForConv.map { it.displayBody }.toTypedArray<CharSequence>()
        val summaryLinesText = linesForConv.joinToString("\n") { it.displayBody }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setSubText("BITCHAT")
            .setContentTitle(resolvedSenderName)
            .setContentText(latestLine.displayBody)
            .setNumber(linesForConv.size)
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setWhen(latestLine.timestamp)
            .setShowWhen(true)
            .addExtras(
                Bundle().apply {
                    putString(EXTRA_CONVERSATION_ID, cleanConvId)
                    putString(EXTRA_OTHER_USER_ID, cleanSender)
                    putString(EXTRA_SENDER_NAME, resolvedSenderName)
                    putString("bitchat_app_header", "BITCHAT")
                    putString("bitchat_grouped_lines", summaryLinesText)
                    putInt("bitchat_message_count", linesForConv.size)
                    putCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES, summaryLinesArray)
                    putCharSequence(NotificationCompat.EXTRA_BIG_TEXT, summaryLinesText)
                }
            )

        try {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(notificationId, builder.build())
            updateOrCancelGroupSummaryNotification(context, nm)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun updateOrCancelGroupSummaryNotification(
        context: Context,
        nm: NotificationManagerCompat
    ) {
        val snapshot = synchronized(activeConversationLines) {
            activeConversationLines.entries
                .filter { it.value.isNotEmpty() }
                .associate { it.key to it.value.toList() }
        }

        if (snapshot.size <= 1) {
            nm.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }

        val totalMessages = snapshot.values.sumOf { it.size }
        val convCount = snapshot.size
        val summaryText = "$totalMessages messages from $convCount chats"

        val summaryInboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle("BITCHAT")
            .setSummaryText(summaryText)

        snapshot.values
            .mapNotNull { it.lastOrNull() }
            .sortedBy { it.timestamp }
            .takeLast(7)
            .forEach { latest ->
                summaryInboxStyle.addLine("${latest.senderName}: ${latest.displayBody}")
            }

        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setSubText("BITCHAT")
            .setContentTitle("BITCHAT")
            .setContentText(summaryText)
            .setNumber(totalMessages)
            .setStyle(summaryInboxStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)

        nm.notify(SUMMARY_NOTIFICATION_ID, summaryBuilder.build())
    }

    fun cancelNotificationForConversation(context: Context, conversationId: String) {
        cancelConversationNotification(context, conversationId)
    }

    fun cancelConversationNotification(context: Context, conversationId: String) {
        lastAppContext = context.applicationContext
        val cleanConvId = conversationId.trim()
        if (cleanConvId.isBlank()) return
        synchronized(activeConversationLines) {
            activeConversationLines.remove(cleanConvId)
        }
        persistGroupedLines(context)
        try {
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(notificationIdForConversation(cleanConvId))
            updateOrCancelGroupSummaryNotification(context, nm)
        } catch (_: Exception) {
        }
    }

    fun getQueuedMessagesForConversation(conversationId: String): List<QueuedNotificationLine> {
        val cleanConv = conversationId.trim()
        return synchronized(activeConversationLines) {
            activeConversationLines[cleanConv]?.toList() ?: emptyList()
        }
    }

    fun getConversationMessagesForTest(conversationId: String): List<QueuedNotificationLine> {
        return getQueuedMessagesForConversation(conversationId)
    }

    fun clearAllTrackingForTest() {
        synchronized(activeConversationLines) {
            activeConversationLines.clear()
        }
        contactConversationKeys.clear()
        notifiedMessageIds.clear()
        lastAppContext?.let { ctx ->
            try {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                NotificationManagerCompat.from(ctx).cancelAll()
            } catch (_: Exception) {
            }
        }
    }

    fun getActiveConversationCount(): Int = synchronized(activeConversationLines) {
        activeConversationLines.count { it.value.isNotEmpty() }
    }

    fun clearAllNotifications(context: Context) {
        lastAppContext = context.applicationContext
        synchronized(activeConversationLines) {
            activeConversationLines.clear()
        }
        contactConversationKeys.clear()
        notifiedMessageIds.clear()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Exception) {
        }
    }

    fun notificationIdForConversation(conversationId: String): Int {
        return (conversationId.trim().hashCode() and 0x7FFFFFFF).coerceAtLeast(1001)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

