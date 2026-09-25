package com.example.data.repository

import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserEntity
import com.example.data.realtime.RealtimeManager
import com.example.data.relay.GlobalRelayEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

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

    fun getConversationsForUser(userId: String): Flow<List<ConversationEntity>> {
        return conversationDao.getConversationsForUser(userId)
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    fun getConversationById(conversationId: String): Flow<ConversationEntity?> {
        return conversationDao.getConversationById(conversationId)
    }

    fun enterConversationScreen(conversationId: String) {
        relayEngine?.subscribeToConversation(conversationId)
    }

    fun exitConversationScreen(conversationId: String) {
        relayEngine?.unsubscribeFromConversation(conversationId)
    }

    suspend fun getOrCreateConversation(currentUserId: String, otherUserId: String): String = withContext(Dispatchers.IO) {
        val existing = conversationDao.findConversationBetween(currentUserId, otherUserId)
        if (existing != null) {
            return@withContext existing.id
        }

        val newId = UUID.randomUUID().toString()
        val newConv = ConversationEntity(
            id = newId,
            participant1Id = currentUserId,
            participant2Id = otherUserId,
            lastMessageText = "",
            lastMessageTimestamp = System.currentTimeMillis(),
            lastMessageSenderId = "",
            unreadCountForUser1 = 0,
            unreadCountForUser2 = 0,
            updatedAt = System.currentTimeMillis()
        )
        conversationDao.insertConversation(newConv)
        firestoreSyncManager?.syncConversationToCloud(newConv)
        newId
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

        // Check if recipient is viewing conversation right now
        val isRecipientViewing = RealtimeManager.isUserViewingConversation(recipientId, conversationId)

        val recipientUser = userDao.getUserByIdDirect(recipientId)
        val isRecipientOnline = recipientUser?.isOnline == true

        val initialStatus = when {
            isRecipientViewing -> MessageStatus.READ.name
            isRecipientOnline -> MessageStatus.DELIVERED.name
            else -> MessageStatus.SENT.name
        }

        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
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

        // Sync to cloud (Firestore & Global Relay)
        firestoreSyncManager?.syncMessageToCloud(message)
        relayEngine?.broadcastMessage(message)

        // Stop typing immediately once message is sent
        RealtimeManager.stopUserTyping(conversationId, senderId)
        relayEngine?.broadcastTyping(conversationId, senderId, false)

        // Update conversation summary
        val conv = conversationDao.getConversationByIdDirect(conversationId)
        if (conv != null) {
            val previewText = when {
                attachmentType != null && content.isNotBlank() -> "📷 $content"
                attachmentType != null -> "📷 Photo attachment"
                else -> content.trim()
            }

            val isSenderUser1 = conv.participant1Id == senderId
            val newUnreadUser1 = if (!isSenderUser1 && !isRecipientViewing) conv.unreadCountForUser1 + 1 else if (isSenderUser1) conv.unreadCountForUser1 else 0
            val newUnreadUser2 = if (isSenderUser1 && !isRecipientViewing) conv.unreadCountForUser2 + 1 else if (!isSenderUser1) conv.unreadCountForUser2 else 0

            val updatedConv = conv.copy(
                lastMessageText = previewText,
                lastMessageTimestamp = now,
                lastMessageSenderId = senderId,
                lastMessageStatus = initialStatus,
                unreadCountForUser1 = newUnreadUser1,
                unreadCountForUser2 = newUnreadUser2,
                updatedAt = now
            )
            conversationDao.updateConversation(updatedConv)
            firestoreSyncManager?.syncConversationToCloud(updatedConv)
        }

        message
    }

    suspend fun markConversationAsRead(conversationId: String, readerId: String) = withContext(Dispatchers.IO) {
        // Update all messages directed to readerId in this conversation to READ
        messageDao.updateStatusForConversation(
            conversationId = conversationId,
            recipientId = readerId,
            status = MessageStatus.READ.name
        )
        messageDao.markAllInConversationAsRead(conversationId, MessageStatus.READ.name)
        conversationDao.updateLastMessageStatus(conversationId, MessageStatus.READ.name)
        firestoreSyncManager?.markAllAsReadInCloud(conversationId, readerId)

        val conv = conversationDao.getConversationByIdDirect(conversationId)
        if (conv != null) {
            val senderId = if (conv.participant1Id == readerId) conv.participant2Id else conv.participant1Id
            relayEngine?.broadcastReadReceipt(conversationId, null, readerId, senderId)

            if (conv.participant1Id == readerId) {
                conversationDao.clearUnreadForUser1(conversationId)
            } else {
                conversationDao.clearUnreadForUser2(conversationId)
            }
        }
    }

    fun notifyTyping(conversationId: String, userId: String) {
        RealtimeManager.onUserTyping(conversationId, userId)
        relayEngine?.broadcastTyping(conversationId, userId, true)
    }

    fun stopTyping(conversationId: String, userId: String) {
        RealtimeManager.stopUserTyping(conversationId, userId)
        relayEngine?.broadcastTyping(conversationId, userId, false)
    }
}
