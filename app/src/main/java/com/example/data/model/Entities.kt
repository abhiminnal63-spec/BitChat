package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

fun normalizeUsername(raw: String): String {
    return raw.replace("@", "").trim().lowercase()
}

fun cleanDisplayUsername(raw: String): String {
    return raw.replace("@", "").trim()
}

fun buildDeterministicConversationId(uidA: String, uidB: String): String {
    val sorted = listOf(uidA.trim(), uidB.trim()).sorted()
    return "${sorted[0]}_${sorted[1]}"
}

@Entity(
    tableName = "users",
    indices = [
        Index(value = ["usernameNormalized"], unique = true)
    ]
)
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val usernameNormalized: String = normalizeUsername(username),
    val displayName: String,
    val passwordHash: String = "",
    val avatarSeed: String = "BRUTAL_1",
    val statusMessage: String = "Using BITCHAT",
    val isOnline: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_devices",
    primaryKeys = ["userId", "deviceId"],
    indices = [
        Index(value = ["userId"]),
        Index(value = ["fcmToken"])
    ]
)
data class UserDeviceEntity(
    val userId: String,
    val deviceId: String,
    val fcmToken: String,
    val platform: String = "android",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["participant1Id", "participant2Id"], unique = true),
        Index(value = ["updatedAt"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val participant1Id: String,
    val participant2Id: String,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = "SENT",
    val unreadCountForUser1: Int = 0,
    val unreadCountForUser2: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_conversation_states",
    primaryKeys = ["userId", "conversationId"],
    indices = [
        Index(value = ["userId"]),
        Index(value = ["conversationId"])
    ]
)
data class UserConversationStateEntity(
    val userId: String,
    val conversationId: String,
    val otherUserId: String,
    val hidden: Boolean = false,
    val deletedAt: Long = 0L,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

fun messageStatusRank(status: String): Int = when (status.uppercase()) {
    MessageStatus.FAILED.name -> -1
    MessageStatus.SENDING.name -> 0
    MessageStatus.SENT.name -> 1
    MessageStatus.DELIVERED.name -> 2
    MessageStatus.READ.name -> 3
    else -> 0
}

fun mergeMessageStatus(existingStatus: String, incomingStatus: String): String {
    val existingUpper = existingStatus.uppercase()
    val incomingUpper = incomingStatus.uppercase()
    // Explicit transition between SENDING and FAILED
    if (existingUpper == MessageStatus.SENDING.name && incomingUpper == MessageStatus.FAILED.name) {
        return MessageStatus.FAILED.name
    }
    if (existingUpper == MessageStatus.FAILED.name && incomingUpper == MessageStatus.SENDING.name) {
        return MessageStatus.SENDING.name
    }
    return if (messageStatusRank(incomingUpper) >= messageStatusRank(existingUpper)) {
        incomingUpper
    } else {
        existingUpper
    }
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"]),
        Index(value = ["senderId"]),
        Index(value = ["recipientId"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = MessageStatus.SENDING.name,
    val attachmentUri: String? = null,
    val attachmentType: String? = null,
    val attachmentSize: Long? = null,
    val attachmentName: String? = null,
    val type: String = if (!attachmentUri.isNullOrBlank()) "image" else "text",
    val mediaUrl: String? = attachmentUri
) {
    val messageId: String get() = id
    val receiverId: String get() = recipientId
    val text: String get() = content
    val createdAt: Long get() = timestamp
}
