package com.example.data.repository

import android.content.Context
import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserEntity
import com.example.data.model.buildDeterministicConversationId
import com.example.data.realtime.RealtimeManager
import com.example.data.relay.GlobalRelayEngine
import com.example.util.ImageUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ConversationItemModel(
    val conversation: ConversationEntity,
    val otherUser: UserEntity,
    val unreadCount: Int,
    val isOtherUserTyping: Boolean
)

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    val firestoreSyncManager: FirestoreSyncManager? = null,
    val relayEngine: GlobalRelayEngine? = null,
    private val appContext: Context? = null
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastCloudTypingSync = ConcurrentHashMap<String, Long>()
    private val conversationSendLocks = ConcurrentHashMap<String, Mutex>()
    private val activeDispatchJobs = ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()
    private val timestampLock = Any()
    private var lastIssuedTimestamp = 0L

    private fun nextMonotonicTimestamp(): Long = synchronized(timestampLock) {
        val now = System.currentTimeMillis()
        val next = if (now <= lastIssuedTimestamp) lastIssuedTimestamp + 1L else now
        lastIssuedTimestamp = next
        next
    }

    private fun mutexForConversation(conversationId: String): Mutex {
        return conversationSendLocks.getOrPut(conversationId) { Mutex() }
    }

    suspend fun flushPendingMessages(conversationId: String) {
        val snapshot = activeDispatchJobs.toList()
        for (job in snapshot) {
            job.join()
        }
        val mutex = mutexForConversation(conversationId)
        mutex.withLock { }
    }

    fun getConversationsForUser(userId: String): Flow<List<ConversationEntity>> {
        return conversationDao.getConversationsForUser(userId)
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    fun getConversationById(conversationId: String): Flow<ConversationEntity?> {
        return conversationDao.getConversationById(conversationId)
    }

    fun enterConversationScreen(conversationId: String, currentUserId: String? = null) {
        relayEngine?.subscribeToConversation(conversationId)
        if (currentUserId != null) {
            firestoreSyncManager?.subscribeToConversation(conversationId, currentUserId)
        }
    }

    fun exitConversationScreen(conversationId: String) {
        relayEngine?.unsubscribeFromConversation(conversationId)
    }

    suspend fun getOrCreateConversation(currentUserId: String, otherUserId: String): String = withContext(Dispatchers.IO) {
        val canonicalId = buildDeterministicConversationId(currentUserId, otherUserId)
        val existingById = conversationDao.getConversationByIdDirect(canonicalId)
        if (existingById != null) {
            return@withContext existingById.id
        }

        val sortedIds = listOf(currentUserId.trim(), otherUserId.trim()).sorted()
        val existingBetween = conversationDao.findConversationBetween(sortedIds[0], sortedIds[1])
        if (existingBetween != null && existingBetween.id == canonicalId) {
            return@withContext existingBetween.id
        }

        val now = System.currentTimeMillis()
        val newConv = ConversationEntity(
            id = canonicalId,
            participant1Id = sortedIds[0],
            participant2Id = sortedIds[1],
            lastMessageText = existingBetween?.lastMessageText ?: "",
            lastMessageTimestamp = existingBetween?.lastMessageTimestamp ?: now,
            lastMessageSenderId = existingBetween?.lastMessageSenderId ?: "",
            lastMessageStatus = existingBetween?.lastMessageStatus ?: MessageStatus.SENT.name,
            unreadCountForUser1 = existingBetween?.unreadCountForUser1 ?: 0,
            unreadCountForUser2 = existingBetween?.unreadCountForUser2 ?: 0,
            updatedAt = now
        )
        conversationDao.insertConversation(newConv)
        firestoreSyncManager?.syncConversationToCloud(newConv)

        val p1Profile = userDao.getUserByIdDirect(sortedIds[0])
        val p2Profile = userDao.getUserByIdDirect(sortedIds[1])
        relayEngine?.syncConversationToCloud(newConv, p1Profile, p2Profile)

        canonicalId
    }

    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        recipientId: String,
        content: String,
        attachmentUri: String? = null,
        attachmentType: String? = null,
        attachmentSize: Long? = null,
        attachmentName: String? = null
    ): MessageEntity = withContext(Dispatchers.IO) {
        val now = nextMonotonicTimestamp()
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
        val canonicalConvId = buildDeterministicConversationId(senderId, recipientId)
        val isImage = !attachmentUri.isNullOrBlank() || attachmentType != null
        val msgType = if (isImage) "image" else "text"

        // Start in SENDING state; only transition to SENT after the shared backend confirms the write
        val pendingMessage = MessageEntity(
            id = messageId,
            conversationId = canonicalConvId,
            senderId = senderId,
            recipientId = recipientId,
            content = content.trim(),
            timestamp = now,
            status = MessageStatus.SENDING.name,
            type = msgType,
            mediaUrl = attachmentUri,
            attachmentUri = attachmentUri,
            attachmentType = attachmentType ?: if (isImage) "IMAGE" else null,
            attachmentSize = attachmentSize,
            attachmentName = attachmentName
        )

        // Immediately insert into local DB so rapid consecutive messages appear in exact order without UI delay
        messageDao.insertMessage(pendingMessage)

        // Stop typing immediately once message is queued
        stopTyping(canonicalConvId, senderId, recipientId)

        // Update conversation summary immediately
        val sortedIds = listOf(senderId.trim(), recipientId.trim()).sorted()
        val existingConv = conversationDao.getConversationByIdDirect(canonicalConvId)
        val previewText = when {
            isImage && content.isNotBlank() -> "📷 ${content.trim()}"
            isImage -> "📷 Photo"
            else -> content.trim()
        }

        val updatedConv = ConversationEntity(
            id = canonicalConvId,
            participant1Id = sortedIds[0],
            participant2Id = sortedIds[1],
            lastMessageText = previewText,
            lastMessageTimestamp = now,
            lastMessageSenderId = senderId,
            lastMessageStatus = MessageStatus.SENDING.name,
            unreadCountForUser1 = existingConv?.unreadCountForUser1 ?: 0,
            unreadCountForUser2 = existingConv?.unreadCountForUser2 ?: 0,
            updatedAt = now
        )
        conversationDao.insertConversation(updatedConv)

        // Dispatch to shared cloud backend sequentially per conversation so rapid messages (1..100+) never race or drop
        val job = repoScope.launch {
            dispatchMessageToBackend(pendingMessage, updatedConv)
        }
        activeDispatchJobs.add(job)
        job.invokeOnCompletion {
            activeDispatchJobs.remove(job)
        }

        pendingMessage
    }

    private suspend fun dispatchMessageToBackend(
        message: MessageEntity,
        conversation: ConversationEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val convMutex = mutexForConversation(message.conversationId)
        convMutex.withLock {
            if (!RealtimeManager.isNetworkConnected.value) {
                messageDao.advanceMessageStatus(message.id, MessageStatus.FAILED.name)
                conversationDao.updateLastMessageStatus(message.conversationId, MessageStatus.FAILED.name)
                return@withLock false
            }

            val now = System.currentTimeMillis()
            userDao.updateOnlineStatus(message.senderId, true, now)

            var messageToPublish = message
            var compactInlinePreview: String? = null

            val isImage = message.type.equals("image", ignoreCase = true) || !message.attachmentUri.isNullOrBlank()
            if (isImage) {
                val sourceUri = message.attachmentUri ?: message.mediaUrl
                val jpegBytes = if (appContext != null && !sourceUri.isNullOrBlank()) {
                    ImageUtils.extractJpegBytes(appContext, sourceUri, maxDimension = 720, quality = 76)
                } else if (!sourceUri.isNullOrBlank() && sourceUri.startsWith("data:image")) {
                    try {
                        val b64 = sourceUri.substringAfter("base64,", "")
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    compactInlinePreview = ImageUtils.createCompactRelayDataUri(jpegBytes)
                    val uploadedUrl = relayEngine?.uploadImageToCloudMedia(message.id, jpegBytes)
                    if (!uploadedUrl.isNullOrBlank()) {
                        messageToPublish = message.copy(
                            type = "image",
                            mediaUrl = uploadedUrl
                        )
                        messageDao.upsertMessageSafely(messageToPublish)
                    } else if (!compactInlinePreview.isNullOrBlank()) {
                        messageToPublish = message.copy(
                            type = "image",
                            mediaUrl = message.mediaUrl ?: compactInlinePreview
                        )
                    }
                }
            }

            val senderProfile = userDao.getUserByIdDirect(message.senderId)?.copy(
                isOnline = true,
                lastSeenTimestamp = now
            )
            val receiverProfile = userDao.getUserByIdDirect(message.recipientId)

            val fsOk = firestoreSyncManager?.syncMessageToCloud(messageToPublish) ?: false
            firestoreSyncManager?.syncConversationToCloud(
                conversation.copy(lastMessageStatus = MessageStatus.SENT.name)
            )
            val relayOk = relayEngine?.publishMessageToCloud(
                message = messageToPublish,
                senderProfile = senderProfile,
                receiverProfile = receiverProfile,
                compactInlineImageUri = compactInlinePreview
            ) ?: false

            val backendConfirmed = fsOk || relayOk
            if (backendConfirmed) {
                messageDao.advanceMessageStatus(message.id, MessageStatus.SENT.name)
                val latestMsg = messageDao.getMessageByIdDirect(message.id)
                val finalStatus = latestMsg?.status ?: MessageStatus.SENT.name
                conversationDao.updateLastMessageStatus(message.conversationId, finalStatus)
            } else {
                messageDao.advanceMessageStatus(message.id, MessageStatus.FAILED.name)
                conversationDao.updateLastMessageStatus(message.conversationId, MessageStatus.FAILED.name)
            }
            backendConfirmed
        }
    }

    suspend fun retryMessage(messageId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = messageDao.getMessageByIdDirect(messageId) ?: return@withContext false
        messageDao.advanceMessageStatus(messageId, MessageStatus.SENDING.name)
        conversationDao.updateLastMessageStatus(existing.conversationId, MessageStatus.SENDING.name)

        val sortedIds = listOf(existing.senderId.trim(), existing.recipientId.trim()).sorted()
        val conv = conversationDao.getConversationByIdDirect(existing.conversationId) ?: ConversationEntity(
            id = existing.conversationId,
            participant1Id = sortedIds[0],
            participant2Id = sortedIds[1],
            lastMessageText = existing.content.ifBlank { "📷 Photo" },
            lastMessageTimestamp = existing.timestamp,
            lastMessageSenderId = existing.senderId,
            lastMessageStatus = MessageStatus.SENDING.name,
            updatedAt = System.currentTimeMillis()
        )
        dispatchMessageToBackend(existing.copy(status = MessageStatus.SENDING.name), conv)
    }

    suspend fun markConversationAsRead(conversationId: String, readerId: String) = withContext(Dispatchers.IO) {
        val unreadCount = messageDao.getUnreadCount(conversationId, readerId)
        messageDao.updateStatusForConversation(
            conversationId = conversationId,
            recipientId = readerId,
            status = MessageStatus.READ.name
        )

        val conv = conversationDao.getConversationByIdDirect(conversationId)
        if (conv != null) {
            val otherUserId = if (conv.participant1Id == readerId) conv.participant2Id else conv.participant1Id
            if (conv.participant1Id == readerId) {
                conversationDao.clearUnreadForUser1(conversationId)
            } else {
                conversationDao.clearUnreadForUser2(conversationId)
            }

            if (conv.lastMessageSenderId == otherUserId && (unreadCount > 0 || conv.lastMessageStatus != MessageStatus.READ.name)) {
                conversationDao.updateLastMessageStatus(conversationId, MessageStatus.READ.name)
                firestoreSyncManager?.markAllAsReadInCloud(conversationId, readerId)
                relayEngine?.broadcastReadReceipt(conversationId, null, readerId, otherUserId)
            }
        }
    }

    fun notifyTyping(conversationId: String, userId: String, recipientId: String? = null) {
        val key = "${conversationId}_$userId"
        val now = System.currentTimeMillis()
        val lastSync = lastCloudTypingSync[key] ?: 0L
        if (now - lastSync >= 1400L) {
            lastCloudTypingSync[key] = now
            firestoreSyncManager?.updateTypingStatusInCloud(conversationId, userId, true)
            repoScope.launch {
                val targetRecipient = recipientId ?: resolveOtherParticipant(conversationId, userId)
                relayEngine?.broadcastTyping(conversationId, userId, targetRecipient, true)
            }
        }
    }

    fun stopTyping(conversationId: String, userId: String, recipientId: String? = null) {
        val key = "${conversationId}_$userId"
        val hadActiveTyping = lastCloudTypingSync.remove(key) != null
        if (!hadActiveTyping) return
        firestoreSyncManager?.updateTypingStatusInCloud(conversationId, userId, false)
        repoScope.launch {
            val targetRecipient = recipientId ?: resolveOtherParticipant(conversationId, userId)
            relayEngine?.broadcastTyping(conversationId, userId, targetRecipient, false)
        }
    }

    private suspend fun resolveOtherParticipant(conversationId: String, userId: String): String? {
        val conv = conversationDao.getConversationByIdDirect(conversationId) ?: return null
        return if (conv.participant1Id == userId) conv.participant2Id else conv.participant1Id
    }
}
