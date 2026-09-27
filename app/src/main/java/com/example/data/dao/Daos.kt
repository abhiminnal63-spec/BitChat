package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.UserConversationStateEntity
import com.example.data.model.UserDeviceEntity
import com.example.data.model.UserEntity
import com.example.data.model.mergeMessageStatus
import com.example.data.model.normalizeUsername
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    fun getUserById(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserByIdDirect(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE usernameNormalized = LOWER(TRIM(:usernameNormalized)) OR LOWER(username) = LOWER(TRIM(:usernameNormalized)) LIMIT 1")
    suspend fun getUserByUsername(usernameNormalized: String): UserEntity?

    @Query(
        """
        SELECT * FROM users 
        WHERE id != :excludeId 
        AND LOWER(TRIM(:normalizedQuery)) != ''
        AND (
            usernameNormalized LIKE '%' || LOWER(TRIM(:normalizedQuery)) || '%' 
            OR LOWER(username) LIKE '%' || LOWER(TRIM(:normalizedQuery)) || '%'
        ) 
        ORDER BY 
            CASE WHEN usernameNormalized = LOWER(TRIM(:normalizedQuery)) THEN 0
                 WHEN usernameNormalized LIKE LOWER(TRIM(:normalizedQuery)) || '%' THEN 1
                 ELSE 2 END,
            isOnline DESC,
            displayName ASC
        """
    )
    fun searchUsers(normalizedQuery: String, excludeId: String): Flow<List<UserEntity>>

    @Query(
        """
        SELECT * FROM users 
        WHERE id != :excludeId 
        AND LOWER(TRIM(:normalizedQuery)) != ''
        AND (
            usernameNormalized LIKE '%' || LOWER(TRIM(:normalizedQuery)) || '%' 
            OR LOWER(username) LIKE '%' || LOWER(TRIM(:normalizedQuery)) || '%'
        ) 
        ORDER BY 
            CASE WHEN usernameNormalized = LOWER(TRIM(:normalizedQuery)) THEN 0
                 WHEN usernameNormalized LIKE LOWER(TRIM(:normalizedQuery)) || '%' THEN 1
                 ELSE 2 END,
            isOnline DESC,
            displayName ASC
        """
    )
    suspend fun searchUsersDirect(normalizedQuery: String, excludeId: String): List<UserEntity>

    @Query("SELECT * FROM users WHERE id != :excludeId ORDER BY isOnline DESC, displayName ASC")
    fun getAllUsersExcept(excludeId: String): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    suspend fun getAllUsersDirect(): List<UserEntity>

    @Query("SELECT * FROM users")
    fun getAllUsersFlow(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUserById(id: String)

    @Transaction
    suspend fun upsertRemoteUser(remoteUser: UserEntity, allowPresenceUpdate: Boolean = true) {
        val norm = normalizeUsername(remoteUser.usernameNormalized.ifBlank { remoteUser.username })
        if (norm.isBlank()) return
        val existingById = getUserByIdDirect(remoteUser.id)
        val existingByUsername = getUserByUsername(norm)
        if (existingByUsername != null && existingByUsername.id != remoteUser.id) {
            if (remoteUser.createdAt <= existingByUsername.createdAt) {
                deleteUserById(existingByUsername.id)
            } else {
                return
            }
        }
        val preservedHash = if (remoteUser.passwordHash.isNotBlank()) {
            remoteUser.passwordHash
        } else {
            existingById?.passwordHash ?: ""
        }

        val now = System.currentTimeMillis()
        val keepExistingPresence = existingById != null &&
            existingById.lastSeenTimestamp > 0L &&
            (!allowPresenceUpdate || remoteUser.lastSeenTimestamp <= existingById.lastSeenTimestamp)

        val keepExistingProfileFields = existingById != null &&
            existingById.lastSeenTimestamp > 0L &&
            (!allowPresenceUpdate || remoteUser.lastSeenTimestamp <= existingById.lastSeenTimestamp)

        val finalDisplayName = if (keepExistingProfileFields && existingById!!.displayName.isNotBlank()) {
            existingById.displayName
        } else {
            remoteUser.displayName
        }
        val finalAvatarSeed = if (keepExistingProfileFields && existingById!!.avatarSeed.isNotBlank()) {
            existingById.avatarSeed
        } else {
            remoteUser.avatarSeed.ifBlank { existingById?.avatarSeed ?: "BRUTAL_1" }
        }
        val finalStatusMessage = if (keepExistingProfileFields && existingById!!.statusMessage.isNotBlank()) {
            existingById.statusMessage
        } else {
            remoteUser.statusMessage
        }

        val finalLastSeen = if (keepExistingPresence) {
            existingById!!.lastSeenTimestamp
        } else {
            remoteUser.lastSeenTimestamp
        }
        val rawOnline = if (keepExistingPresence) {
            existingById!!.isOnline
        } else {
            remoteUser.isOnline
        }
        val finalOnline = rawOnline && finalLastSeen > 0L && (now - finalLastSeen) < 90_000L

        insertUser(
            remoteUser.copy(
                username = remoteUser.username.trim().removePrefix("@").trim(),
                usernameNormalized = norm,
                displayName = finalDisplayName,
                avatarSeed = finalAvatarSeed,
                statusMessage = finalStatusMessage,
                passwordHash = preservedHash,
                isOnline = finalOnline,
                lastSeenTimestamp = finalLastSeen
            )
        )
    }

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET isOnline = :isOnline, lastSeenTimestamp = :lastSeen WHERE id = :userId")
    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean, lastSeen: Long)

    @Query("UPDATE users SET isOnline = :isOnline, lastSeenTimestamp = :lastSeen WHERE id = :userId AND (:lastSeen >= lastSeenTimestamp OR lastSeenTimestamp <= 0)")
    suspend fun updateRemotePresenceIfNewer(userId: String, isOnline: Boolean, lastSeen: Long)

    @Query(
        """
        UPDATE users 
        SET isOnline = CASE WHEN (:now - :activityTimestamp) < 90000 THEN 1 ELSE isOnline END,
            lastSeenTimestamp = MAX(lastSeenTimestamp, :activityTimestamp)
        WHERE id = :userId AND :activityTimestamp >= lastSeenTimestamp
        """
    )
    suspend fun recordPeerActivity(userId: String, activityTimestamp: Long, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserDevice(device: UserDeviceEntity)

    @Query("SELECT * FROM user_devices WHERE userId = :userId ORDER BY updatedAt DESC")
    suspend fun getDevicesForUserDirect(userId: String): List<UserDeviceEntity>

    @Query("DELETE FROM user_devices WHERE userId = :userId AND deviceId = :deviceId")
    suspend fun deleteUserDevice(userId: String, deviceId: String)

    @Query("DELETE FROM user_devices WHERE fcmToken = :fcmToken")
    suspend fun deleteDeviceByToken(fcmToken: String)
}

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT c.* FROM conversations c
        LEFT JOIN user_conversation_states ucs
          ON ucs.conversationId = c.id AND ucs.userId = :userId
        WHERE (c.participant1Id = :userId OR c.participant2Id = :userId)
          AND (ucs.hidden IS NULL OR ucs.hidden = 0 OR c.lastMessageTimestamp > ucs.deletedAt)
          AND (ucs.deletedAt IS NULL OR c.lastMessageTimestamp > ucs.deletedAt)
        ORDER BY c.updatedAt DESC
        """
    )
    fun getConversationsForUser(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getConversationById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationByIdDirect(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE (participant1Id = :userA AND participant2Id = :userB) OR (participant1Id = :userB AND participant2Id = :userA) LIMIT 1")
    suspend fun findConversationBetween(userA: String, userB: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCountForUser1 = 0 WHERE id = :conversationId")
    suspend fun clearUnreadForUser1(conversationId: String)

    @Query("UPDATE conversations SET unreadCountForUser2 = 0 WHERE id = :conversationId")
    suspend fun clearUnreadForUser2(conversationId: String)

    @Query("UPDATE conversations SET lastMessageStatus = :status WHERE id = :conversationId")
    suspend fun updateLastMessageStatus(conversationId: String, status: String)

    @Query("SELECT * FROM user_conversation_states WHERE userId = :userId AND conversationId = :conversationId LIMIT 1")
    suspend fun getUserConversationStateDirect(userId: String, conversationId: String): UserConversationStateEntity?

    @Query("SELECT * FROM user_conversation_states WHERE userId = :userId")
    fun getUserConversationStatesFlow(userId: String): Flow<List<UserConversationStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserConversationState(state: UserConversationStateEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.* FROM messages m
        LEFT JOIN user_conversation_states ucs
          ON ucs.conversationId = m.conversationId AND ucs.userId = :userId
        WHERE m.conversationId = :conversationId
          AND (ucs.deletedAt IS NULL OR m.timestamp > ucs.deletedAt)
        ORDER BY m.timestamp ASC, m.id ASC
        """
    )
    fun getMessagesForConversationForUser(conversationId: String, userId: String): Flow<List<MessageEntity>>

    @Query("SELECT MAX(timestamp) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMaxMessageTimestampForConversation(conversationId: String): Long?

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageByIdDirect(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Transaction
    suspend fun upsertMessageSafely(incoming: MessageEntity) {
        val existing = getMessageByIdDirect(incoming.id)
        if (existing == null) {
            insertMessage(incoming)
        } else {
            val mergedStatus = mergeMessageStatus(existing.status, incoming.status)
            val mergedMediaUrl = when {
                !incoming.mediaUrl.isNullOrBlank() && incoming.mediaUrl.startsWith("http") -> incoming.mediaUrl
                !existing.mediaUrl.isNullOrBlank() && existing.mediaUrl.startsWith("http") -> existing.mediaUrl
                !incoming.mediaUrl.isNullOrBlank() -> incoming.mediaUrl
                else -> existing.mediaUrl
            }
            val mergedAttachmentUri = when {
                !existing.attachmentUri.isNullOrBlank() && existing.attachmentUri.startsWith("file:") -> existing.attachmentUri
                !incoming.attachmentUri.isNullOrBlank() &&
                    (existing.attachmentUri.isNullOrBlank() || incoming.attachmentUri.length >= existing.attachmentUri.length) -> incoming.attachmentUri
                else -> existing.attachmentUri ?: incoming.attachmentUri
            }
            val isImage = incoming.type == "image" ||
                existing.type == "image" ||
                !mergedMediaUrl.isNullOrBlank() ||
                !mergedAttachmentUri.isNullOrBlank()

            insertMessage(
                existing.copy(
                    conversationId = incoming.conversationId.ifBlank { existing.conversationId },
                    content = if (incoming.content.isNotBlank()) incoming.content else existing.content,
                    timestamp = if (existing.timestamp > 0L) existing.timestamp else incoming.timestamp,
                    status = mergedStatus,
                    type = if (isImage) "image" else "text",
                    mediaUrl = mergedMediaUrl ?: mergedAttachmentUri,
                    attachmentUri = mergedAttachmentUri ?: mergedMediaUrl,
                    attachmentType = incoming.attachmentType ?: existing.attachmentType,
                    attachmentSize = incoming.attachmentSize ?: existing.attachmentSize,
                    attachmentName = incoming.attachmentName ?: existing.attachmentName
                )
            )
        }
    }

    @Transaction
    suspend fun advanceMessageStatus(messageId: String, newStatus: String) {
        val existing = getMessageByIdDirect(messageId) ?: return
        val merged = mergeMessageStatus(existing.status, newStatus)
        if (merged != existing.status) {
            updateSingleMessageStatus(messageId, merged)
        }
    }

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE conversationId = :conversationId AND recipientId = :recipientId AND status != 'READ'")
    suspend fun updateStatusForConversation(conversationId: String, recipientId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateSingleMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE conversationId = :conversationId AND status != 'READ'")
    suspend fun markAllInConversationAsRead(conversationId: String, status: String = "READ")

    @Query(
        """
        SELECT COUNT(*) FROM messages m
        LEFT JOIN user_conversation_states ucs
          ON ucs.conversationId = m.conversationId AND ucs.userId = :recipientId
        WHERE m.conversationId = :conversationId
          AND m.recipientId = :recipientId
          AND m.status != 'READ'
          AND (ucs.deletedAt IS NULL OR m.timestamp > ucs.deletedAt)
        """
    )
    suspend fun getUnreadCount(conversationId: String, recipientId: String): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForConversationDirect(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE recipientId = :recipientId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForRecipientDirect(recipientId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE recipientId = :recipientId AND status != 'READ' ORDER BY timestamp ASC, id ASC")
    suspend fun getUnreadMessagesForRecipient(recipientId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE senderId = :senderId AND (status = 'SENDING' OR status = 'FAILED') ORDER BY timestamp ASC, id ASC")
    suspend fun getPendingOutgoingMessages(senderId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 'SENDING' OR status = 'FAILED' ORDER BY timestamp ASC, id ASC")
    suspend fun getAllPendingOutgoingMessages(): List<MessageEntity>
}
