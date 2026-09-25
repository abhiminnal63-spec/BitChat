package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    fun getUserById(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserByIdDirect(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE LOWER(username) = LOWER(:username) LIMIT 1")
    suspend fun getUserByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id != :excludeId AND (LOWER(username) LIKE '%' || LOWER(:query) || '%' OR LOWER(displayName) LIKE '%' || LOWER(:query) || '%') ORDER BY displayName ASC")
    fun searchUsers(query: String, excludeId: String): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id != :excludeId ORDER BY isOnline DESC, displayName ASC")
    fun getAllUsersExcept(excludeId: String): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    suspend fun getAllUsersDirect(): List<UserEntity>

    @Query("SELECT * FROM users")
    fun getAllUsersFlow(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET isOnline = :isOnline, lastSeenTimestamp = :lastSeen WHERE id = :userId")
    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean, lastSeen: Long)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE participant1Id = :userId OR participant2Id = :userId ORDER BY updatedAt DESC")
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
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE conversationId = :conversationId AND recipientId = :recipientId AND status != 'READ'")
    suspend fun updateStatusForConversation(conversationId: String, recipientId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateSingleMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE conversationId = :conversationId AND status != 'READ'")
    suspend fun markAllInConversationAsRead(conversationId: String, status: String = "READ")

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND recipientId = :recipientId AND status != 'READ'")
    suspend fun getUnreadCount(conversationId: String, recipientId: String): Int
}
