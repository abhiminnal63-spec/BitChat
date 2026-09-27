package com.example.notifications

import android.content.Context
import android.util.Log
import com.example.data.database.EasappDatabase
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.buildDeterministicConversationId
import com.example.data.realtime.RealtimeManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BitchatMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM onNewToken received")
        DeviceTokenManager.saveFcmTokenLocally(applicationContext, token)

        val sessionPrefs = applicationContext.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)
        val currentUserId = sessionPrefs.getString("logged_in_user_id", null) ?: return

        serviceScope.launch {
            val db = EasappDatabase.getInstance(applicationContext)
            val fs = FirestoreSyncManager(
                context = applicationContext,
                userDao = db.userDao(),
                conversationDao = db.conversationDao(),
                messageDao = db.messageDao()
            )
            DeviceTokenManager.registerDeviceForUser(
                context = applicationContext,
                userId = currentUserId,
                fcmTokenOverride = token,
                userDao = db.userDao(),
                firestoreSyncManager = fs
            )
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        if (data.isEmpty() && remoteMessage.notification == null) return

        serviceScope.launch {
            handleIncomingFcmData(
                context = applicationContext,
                data = data,
                fallbackTitle = remoteMessage.notification?.title,
                fallbackBody = remoteMessage.notification?.body
            )
        }
    }

    companion object {
        private const val TAG = "BitchatFCM"

        /**
         * Processes an incoming FCM payload (whether from FirebaseMessagingService or background push delivery):
         * 1. Verifies recipient UID matches logged-in user.
         * 2. Persists message in local Room DB with status = DELIVERED (never READ/SEEN just because push arrived).
         * 3. Updates backend message status to DELIVERED.
         * 4. Displays grouped BITCHAT Android notification (unless CASE 1: recipient is actively inside the conversation).
         */
        suspend fun handleIncomingFcmData(
            context: Context,
            data: Map<String, String>,
            fallbackTitle: String? = null,
            fallbackBody: String? = null
        ): Boolean {
            val appContext = context.applicationContext
            val sessionPrefs = appContext.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)
            val loggedInUid = sessionPrefs.getString("logged_in_user_id", null)?.trim() ?: return false

            val senderId = (data["senderId"] ?: "").trim()
            val receiverId = (data["receiverId"] ?: data["recipientId"] ?: loggedInUid).trim()
            if (senderId.isBlank() || receiverId != loggedInUid || senderId == loggedInUid) {
                return false
            }

            val messageId = (data["messageId"] ?: data["id"] ?: "msg_${System.currentTimeMillis()}").trim()
            val conversationId = (data["conversationId"] ?: buildDeterministicConversationId(senderId, receiverId)).trim()
            val rawType = (data["type"] ?: "text").trim()
            val mediaUrl = data["mediaUrl"]?.takeIf { it.isNotBlank() }
            val isImage = rawType.equals("image", ignoreCase = true) || !mediaUrl.isNullOrBlank()
            val textContent = data["text"] ?: data["content"] ?: fallbackBody ?: ""
            val createdAt = data["createdAt"]?.toLongOrNull()
                ?: data["timestamp"]?.toLongOrNull()
                ?: System.currentTimeMillis()

            val database = EasappDatabase.getInstance(appContext)
            val userDao = database.userDao()
            val convDao = database.conversationDao()
            val msgDao = database.messageDao()

            // Check if user is actively viewing this conversation in foreground (CASE 1)
            val isActivelyViewing = RealtimeManager.isAppInForeground.value &&
                RealtimeManager.isUserViewingConversation(loggedInUid, conversationId)

            // IMPORTANT: Do NOT mark a message as SEEN/READ merely because the push notification was delivered!
            val statusToPersist = if (isActivelyViewing) {
                MessageStatus.READ.name
            } else {
                MessageStatus.DELIVERED.name
            }

            val incomingMsg = MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                recipientId = receiverId,
                content = textContent,
                timestamp = createdAt,
                status = statusToPersist,
                type = if (isImage) "image" else "text",
                mediaUrl = mediaUrl,
                attachmentUri = mediaUrl,
                attachmentType = if (isImage) "IMAGE" else null
            )

            val existingMsg = msgDao.getMessageByIdDirect(messageId)
            msgDao.upsertMessageSafely(incomingMsg)

            val sorted = listOf(senderId, receiverId).sorted()
            val previewText = when {
                isImage && textContent.isNotBlank() -> "📷 $textContent"
                isImage -> "📷 Photo"
                else -> textContent
            }
            val existingConv = convDao.getConversationByIdDirect(conversationId)
            val isBrandNew = existingMsg == null
            if (existingConv != null) {
                val isMeP1 = existingConv.participant1Id == loggedInUid
                val unread1 = if (isMeP1 && !isActivelyViewing && isBrandNew) {
                    existingConv.unreadCountForUser1 + 1
                } else if (isMeP1 && isActivelyViewing) {
                    0
                } else {
                    existingConv.unreadCountForUser1
                }
                val unread2 = if (!isMeP1 && !isActivelyViewing && isBrandNew) {
                    existingConv.unreadCountForUser2 + 1
                } else if (!isMeP1 && isActivelyViewing) {
                    0
                } else {
                    existingConv.unreadCountForUser2
                }
                convDao.insertConversation(
                    existingConv.copy(
                        lastMessageText = previewText,
                        lastMessageTimestamp = maxOf(createdAt, existingConv.lastMessageTimestamp),
                        lastMessageSenderId = senderId,
                        lastMessageStatus = statusToPersist,
                        unreadCountForUser1 = unread1,
                        unreadCountForUser2 = unread2,
                        updatedAt = maxOf(createdAt, existingConv.updatedAt)
                    )
                )
            } else {
                val isMeP1 = sorted[0] == loggedInUid
                convDao.insertConversation(
                    ConversationEntity(
                        id = conversationId,
                        participant1Id = sorted[0],
                        participant2Id = sorted[1],
                        lastMessageText = previewText,
                        lastMessageTimestamp = createdAt,
                        lastMessageSenderId = senderId,
                        lastMessageStatus = statusToPersist,
                        unreadCountForUser1 = if (isMeP1 && !isActivelyViewing) 1 else 0,
                        unreadCountForUser2 = if (!isMeP1 && !isActivelyViewing) 1 else 0,
                        updatedAt = createdAt
                    )
                )
            }

            val senderUser = userDao.getUserByIdDirect(senderId)
            val senderName = data["senderName"]?.takeIf { it.isNotBlank() }
                ?: senderUser?.displayName?.takeIf { it.isNotBlank() }
                ?: fallbackTitle?.takeIf { it.isNotBlank() && it != "BITCHAT" }
                ?: senderUser?.username?.takeIf { it.isNotBlank() }
                ?: "Contact"

            return BitchatNotificationManager.showIncomingMessageNotification(
                context = appContext,
                recipientId = loggedInUid,
                message = incomingMsg,
                senderDisplayName = senderName
            )
        }
    }
}
