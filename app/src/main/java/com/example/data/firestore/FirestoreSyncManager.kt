package com.example.data.firestore

import android.content.Context
import android.util.Log
import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserEntity
import com.example.data.realtime.RealtimeManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirestoreSyncManager(
    private val context: Context,
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val tag = "FirestoreSync"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var firestore: FirebaseFirestore? = null
    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    private val listeners = mutableListOf<ListenerRegistration>()
    private val messageListeners = mutableMapOf<String, ListenerRegistration>()
    private val typingListeners = mutableMapOf<String, ListenerRegistration>()

    init {
        initializeFirestore()
    }

    private fun initializeFirestore() {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
                _isCloudConnected.value = true
                Log.d(tag, "Firestore successfully initialized.")
            } else {
                Log.w(tag, "FirebaseApp is not initialized yet. Operating in local mode until configured.")
                _isCloudConnected.value = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Firestore init error: ${e.message}")
            _isCloudConnected.value = false
        }
    }

    fun startSync(currentUserId: String) {
        val db = firestore ?: return
        stopSync()

        try {
            // 1. Listen to all registered users from Firestore
            val userReg = db.collection("users").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(tag, "Error listening to users: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    scope.launch {
                        for (doc in snapshot.documents) {
                            try {
                                val id = doc.getString("id") ?: doc.id
                                val username = doc.getString("username") ?: continue
                                val displayName = doc.getString("displayName") ?: username
                                val passwordHash = doc.getString("passwordHash") ?: ""
                                val avatarSeed = doc.getString("avatarSeed") ?: "BRUTAL_1"
                                val statusMessage = doc.getString("statusMessage") ?: "Using Easapp"
                                val isOnline = doc.getBoolean("isOnline") ?: false
                                val lastSeenTimestamp = doc.getLong("lastSeenTimestamp") ?: System.currentTimeMillis()
                                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()

                                val user = UserEntity(
                                    id = id,
                                    username = username,
                                    displayName = displayName,
                                    passwordHash = passwordHash,
                                    avatarSeed = avatarSeed,
                                    statusMessage = statusMessage,
                                    isOnline = isOnline,
                                    lastSeenTimestamp = lastSeenTimestamp,
                                    createdAt = createdAt
                                )
                                userDao.insertUser(user)
                            } catch (e: Exception) {
                                Log.e(tag, "User parse error: ${e.message}")
                            }
                        }
                    }
                }
            }
            listeners.add(userReg)

            // 2. Listen to conversations where user is a participant
            val convReg = db.collection("conversations")
                .whereArrayContains("participantIds", currentUserId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Error listening to conversations: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        scope.launch {
                            for (doc in snapshot.documents) {
                                try {
                                    val id = doc.getString("id") ?: doc.id
                                    val p1 = doc.getString("participant1Id") ?: ""
                                    val p2 = doc.getString("participant2Id") ?: ""
                                    val lastText = doc.getString("lastMessageText") ?: ""
                                    val lastTime = doc.getLong("lastMessageTimestamp") ?: 0L
                                    val lastSender = doc.getString("lastMessageSenderId") ?: ""
                                    val unread1 = doc.getLong("unreadCountForUser1")?.toInt() ?: 0
                                    val unread2 = doc.getLong("unreadCountForUser2")?.toInt() ?: 0
                                    val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                                    val conv = ConversationEntity(
                                        id = id,
                                        participant1Id = p1,
                                        participant2Id = p2,
                                        lastMessageText = lastText,
                                        lastMessageTimestamp = lastTime,
                                        lastMessageSenderId = lastSender,
                                        unreadCountForUser1 = unread1,
                                        unreadCountForUser2 = unread2,
                                        updatedAt = updatedAt
                                    )
                                    conversationDao.insertConversation(conv)

                                    // Check inline typing fields on conversation document if present
                                    val otherId = if (p1 == currentUserId) p2 else p1
                                    if (otherId.isNotBlank()) {
                                        val isOtherTypingField = doc.getBoolean("typing_$otherId")
                                        val typingTimeField = doc.getLong("typingUpdatedAt_$otherId") ?: 0L
                                        if (isOtherTypingField != null) {
                                            val isFresh = (System.currentTimeMillis() - typingTimeField) < 10_000L
                                            if (isOtherTypingField && isFresh) {
                                                RealtimeManager.onUserTyping(id, otherId)
                                            } else if (!isOtherTypingField) {
                                                RealtimeManager.stopUserTyping(id, otherId)
                                            }
                                        }
                                    }

                                    // Also listen to messages and typing states in this conversation
                                    listenToConversationMessages(id, currentUserId)
                                    listenToConversationTyping(id, currentUserId)
                                } catch (e: Exception) {
                                    Log.e(tag, "Conversation parse error: ${e.message}")
                                }
                            }
                        }
                    }
                }
            listeners.add(convReg)
        } catch (e: Exception) {
            Log.e(tag, "Error starting sync: ${e.message}")
        }
    }

    fun subscribeToConversationTyping(conversationId: String, currentUserId: String) {
        listenToConversationTyping(conversationId, currentUserId)
    }

    private fun listenToConversationTyping(conversationId: String, currentUserId: String) {
        val db = firestore ?: return
        if (typingListeners.containsKey(conversationId)) return

        try {
            val reg = db.collection("conversations")
                .document(conversationId)
                .collection("typing")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Typing listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val now = System.currentTimeMillis()
                        for (doc in snapshot.documents) {
                            try {
                                val typingUserId = doc.getString("userId") ?: doc.id
                                if (typingUserId == currentUserId) continue

                                val isTyping = doc.getBoolean("isTyping") ?: false
                                val updatedAt = doc.getLong("updatedAt") ?: 0L
                                val isRecent = (now - updatedAt) < 10_000L

                                if (isTyping && isRecent) {
                                    RealtimeManager.onUserTyping(conversationId, typingUserId)
                                } else {
                                    RealtimeManager.stopUserTyping(conversationId, typingUserId)
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Typing document parse error: ${e.message}")
                            }
                        }
                    }
                }
            typingListeners[conversationId] = reg
        } catch (e: Exception) {
            Log.e(tag, "Failed to listen to typing in conversation $conversationId: ${e.message}")
        }
    }

    private fun listenToConversationMessages(conversationId: String, currentUserId: String) {
        val db = firestore ?: return
        if (messageListeners.containsKey(conversationId)) return

        try {
            val reg = db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Messages listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        scope.launch {
                            for (doc in snapshot.documents) {
                                try {
                                    val id = doc.getString("id") ?: doc.id
                                    val senderId = doc.getString("senderId") ?: ""
                                    val recipientId = doc.getString("recipientId") ?: ""
                                    val content = doc.getString("content") ?: ""
                                    val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                    val status = doc.getString("status") ?: MessageStatus.SENT.name
                                    val attachmentUri = doc.getString("attachmentUri")
                                    val attachmentType = doc.getString("attachmentType")
                                    val attachmentSize = doc.getLong("attachmentSize")
                                    val attachmentName = doc.getString("attachmentName")

                                    val message = MessageEntity(
                                        id = id,
                                        conversationId = conversationId,
                                        senderId = senderId,
                                        recipientId = recipientId,
                                        content = content,
                                        timestamp = timestamp,
                                        status = status,
                                        attachmentUri = attachmentUri,
                                        attachmentType = attachmentType,
                                        attachmentSize = attachmentSize,
                                        attachmentName = attachmentName
                                    )
                                    messageDao.insertMessage(message)

                                    // If this message was sent to me and I am currently viewing this conversation:
                                    if (recipientId == currentUserId &&
                                        RealtimeManager.isUserViewingConversation(currentUserId, conversationId) &&
                                        status != MessageStatus.READ.name
                                    ) {
                                        markMessageAsReadInCloud(conversationId, id)
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Message parse error: ${e.message}")
                                }
                            }
                        }
                    }
                }
            messageListeners[conversationId] = reg
        } catch (e: Exception) {
            Log.e(tag, "Failed to listen to conversation $conversationId: ${e.message}")
        }
    }

    suspend fun syncUserToCloud(user: UserEntity) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val userMap = hashMapOf(
                "id" to user.id,
                "username" to user.username,
                "displayName" to user.displayName,
                "passwordHash" to user.passwordHash,
                "avatarSeed" to user.avatarSeed,
                "statusMessage" to user.statusMessage,
                "isOnline" to user.isOnline,
                "lastSeenTimestamp" to user.lastSeenTimestamp,
                "createdAt" to user.createdAt
            )
            db.collection("users").document(user.id).set(userMap, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to push user to cloud: ${e.message}")
        }
    }

    suspend fun updatePresenceInCloud(userId: String, isOnline: Boolean, lastSeenTimestamp: Long) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val map = hashMapOf<String, Any>(
                "isOnline" to isOnline,
                "lastSeenTimestamp" to lastSeenTimestamp
            )
            db.collection("users").document(userId).set(map, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to update presence: ${e.message}")
        }
    }

    suspend fun syncConversationToCloud(conversation: ConversationEntity) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val convMap = hashMapOf(
                "id" to conversation.id,
                "participant1Id" to conversation.participant1Id,
                "participant2Id" to conversation.participant2Id,
                "participantIds" to listOf(conversation.participant1Id, conversation.participant2Id),
                "lastMessageText" to conversation.lastMessageText,
                "lastMessageTimestamp" to conversation.lastMessageTimestamp,
                "lastMessageSenderId" to conversation.lastMessageSenderId,
                "unreadCountForUser1" to conversation.unreadCountForUser1,
                "unreadCountForUser2" to conversation.unreadCountForUser2,
                "updatedAt" to conversation.updatedAt
            )
            db.collection("conversations").document(conversation.id).set(convMap, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync conversation: ${e.message}")
        }
    }

    suspend fun syncMessageToCloud(message: MessageEntity) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val msgMap = hashMapOf(
                "id" to message.id,
                "conversationId" to message.conversationId,
                "senderId" to message.senderId,
                "recipientId" to message.recipientId,
                "content" to message.content,
                "timestamp" to message.timestamp,
                "status" to message.status,
                "attachmentUri" to (message.attachmentUri ?: ""),
                "attachmentType" to (message.attachmentType ?: ""),
                "attachmentSize" to (message.attachmentSize ?: 0L),
                "attachmentName" to (message.attachmentName ?: "")
            )
            db.collection("conversations")
                .document(message.conversationId)
                .collection("messages")
                .document(message.id)
                .set(msgMap, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync message: ${e.message}")
        }
    }

    suspend fun markMessageAsReadInCloud(conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document(messageId)
                .update("status", MessageStatus.READ.name)
        } catch (e: Exception) {
            Log.e(tag, "Failed to mark message read in cloud: ${e.message}")
        }
    }

    suspend fun markAllAsReadInCloud(conversationId: String, recipientId: String) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .whereEqualTo("recipientId", recipientId)
                .get()
                .addOnSuccessListener { snapshot ->
                    for (doc in snapshot.documents) {
                        doc.reference.update("status", MessageStatus.READ.name)
                    }
                }
        } catch (e: Exception) {
            Log.e(tag, "Failed to mark all as read: ${e.message}")
        }
    }

    fun updateTypingStatusInCloud(conversationId: String, userId: String, isTyping: Boolean) {
        val db = firestore ?: return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val typingMap = hashMapOf<String, Any>(
                    "userId" to userId,
                    "conversationId" to conversationId,
                    "isTyping" to isTyping,
                    "updatedAt" to now
                )
                db.collection("conversations")
                    .document(conversationId)
                    .collection("typing")
                    .document(userId)
                    .set(typingMap, SetOptions.merge())

                val inlineTypingMap = hashMapOf<String, Any>(
                    "typing_$userId" to isTyping,
                    "typingUpdatedAt_$userId" to now
                )
                db.collection("conversations")
                    .document(conversationId)
                    .set(inlineTypingMap, SetOptions.merge())
            } catch (e: Exception) {
                Log.e(tag, "Failed to update typing status in cloud: ${e.message}")
            }
        }
    }

    fun stopSync() {
        for (listener in listeners) {
            listener.remove()
        }
        listeners.clear()
        for ((_, listener) in messageListeners) {
            listener.remove()
        }
        messageListeners.clear()
        for ((_, listener) in typingListeners) {
            listener.remove()
        }
        typingListeners.clear()
    }
}
