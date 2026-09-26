package com.example.data.repository

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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
    val relayEngine: GlobalRelayEngine? = null
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastCloudTypingSync = mutableMapOf<String, Long>()

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
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val canonicalConvId = buildDeterministicConversationId(senderId, recipientId)

        // Messages always start as SENT on the sender device; DELIVERED and READ come only from the recipient's backend confirmation
        val initialStatus = MessageStatus.SENT.name

        val message = MessageEntity(
            id = messageId,
            conversationId = canonicalConvId,
            senderId = senderId,
            recipientId = recipientId,
            content = content.trim(),
            timestamp = now,
            status = initialStatus,
            attachmentUri = attachmentUri,
            attachmentType = attachmentType,
            attachmentSize = attachmentSize,
            attachmentName = attachmentName
        )

        messageDao.insertMessage(message)

        val senderProfile = userDao.getUserByIdDirect(senderId)
        val receiverProfile = userDao.getUserByIdDirect(recipientId)

        // Stop typing immediately once message is sent
        stopTyping(canonicalConvId, senderId, recipientId)

        // Update conversation summary using deterministic ID
        val sortedIds = listOf(senderId.trim(), recipientId.trim()).sorted()
        val existingConv = conversationDao.getConversationByIdDirect(canonicalConvId)
        val previewText = when {
            attachmentType != null && content.isNotBlank() -> "📷 $content"
            attachmentType != null -> "📷 Photo attachment"
            else -> content.trim()
        }

        val updatedConv = ConversationEntity(
            id = canonicalConvId,
            participant1Id = sortedIds[0],
            participant2Id = sortedIds[1],
            lastMessageText = previewText,
            lastMessageTimestamp = now,
            lastMessageSenderId = senderId,
            lastMessageStatus = initialStatus,
            unreadCountForUser1 = existingConv?.unreadCountForUser1 ?: 0,
            unreadCountForUser2 = existingConv?.unreadCountForUser2 ?: 0,
            updatedAt = now
        )
        conversationDao.insertConversation(updatedConv)

        // Sync to shared cloud backend (Firestore & GlobalRelayEngine)
        firestoreSyncManager?.syncMessageToCloud(message)
        firestoreSyncManager?.syncConversationToCloud(updatedConv)
        relayEngine?.publishMessageToCloud(message, senderProfile, receiverProfile)

        message
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
        if (now - lastSync >= 900L) {
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
        lastCloudTypingSync.remove(key)
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
