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
import com.example.data.model.buildDeterministicConversationId
import com.example.data.model.cleanDisplayUsername
import com.example.data.model.normalizeUsername
import com.example.data.realtime.RealtimeManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirestoreSyncManager(
    private val context: Context,
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val tag = "FirestoreSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var firestore: FirebaseFirestore? = null
    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    private val listeners = mutableListOf<ListenerRegistration>()
    private val messageListeners = mutableMapOf<String, ListenerRegistration>()
    private val typingListeners = mutableMapOf<String, ListenerRegistration>()
    private var globalUsersListener: ListenerRegistration? = null

    init {
        initializeFirestore()
    }

    private fun initializeFirestore() {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
                _isCloudConnected.value = true
                startGlobalUsersListener()
                Log.d(tag, "Firestore successfully initialized.")
            } else {
                Log.w(tag, "FirebaseApp is not initialized yet. Operating with GlobalRelayEngine cloud backend.")
                _isCloudConnected.value = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Firestore init error: ${e.message}")
            _isCloudConnected.value = false
        }
    }

    private fun startGlobalUsersListener() {
        val db = firestore ?: return
        if (globalUsersListener != null) return
        try {
            globalUsersListener = db.collection("users").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(tag, "Error listening to global users: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    scope.launch {
                        for (doc in snapshot.documents) {
                            parseAndUpsertUserDoc(doc)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start global users listener: ${e.message}")
        }
    }

    private suspend fun parseAndUpsertUserDoc(doc: com.google.firebase.firestore.DocumentSnapshot): UserEntity? {
        return try {
            val id = doc.getString("uid") ?: doc.getString("id") ?: doc.id
            val rawUsername = doc.getString("username") ?: return null
            val cleanUser = cleanDisplayUsername(rawUsername)
            val normUser = doc.getString("usernameNormalized")?.let { normalizeUsername(it) }
                ?.ifBlank { normalizeUsername(cleanUser) }
                ?: normalizeUsername(cleanUser)
            if (normUser.isBlank()) return null

            val displayName = doc.getString("displayName") ?: cleanUser
            val avatarSeed = doc.getString("photoURL") ?: doc.getString("avatarSeed") ?: "BRUTAL_1"
            val statusMessage = doc.getString("statusMessage") ?: "Available on Easapp"
            val lastSeenTimestamp = doc.getLong("lastSeen")
                ?: doc.getLong("lastSeenTimestamp")
                ?: System.currentTimeMillis()
            val rawOnline = doc.getBoolean("online") ?: doc.getBoolean("isOnline") ?: false
            val isOnline = rawOnline && (System.currentTimeMillis() - lastSeenTimestamp) < 300_000L
            val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()

            val user = UserEntity(
                id = id,
                username = cleanUser,
                usernameNormalized = normUser,
                displayName = displayName,
                passwordHash = "", // Never expose or read passwordHash from public profile documents
                avatarSeed = avatarSeed,
                statusMessage = statusMessage,
                isOnline = isOnline,
                lastSeenTimestamp = lastSeenTimestamp,
                createdAt = createdAt
            )
            userDao.upsertRemoteUser(user)
            user
        } catch (e: Exception) {
            Log.e(tag, "User parse error: ${e.message}")
            null
        }
    }

    fun startSync(currentUserId: String) {
        val db = firestore ?: return
        stopSync()
        startGlobalUsersListener()

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
                            parseAndUpsertUserDoc(doc)
                        }
                    }
                }
            }
            listeners.add(userReg)

            // 2. Listen to conversations where user is a participant
            val convReg = db.collection("conversations")
                .whereArrayContains("participants", currentUserId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Error listening to conversations: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        scope.launch {
                            for (doc in snapshot.documents) {
                                try {
                                    val participants = (doc.get("participants") as? List<*>)
                                        ?.mapNotNull { it as? String }
                                        ?: (doc.get("participantIds") as? List<*>)?.mapNotNull { it as? String }
                                        ?: emptyList()
                                    val p1 = doc.getString("participant1Id") ?: participants.getOrNull(0) ?: ""
                                    val p2 = doc.getString("participant2Id") ?: participants.getOrNull(1) ?: ""
                                    if (p1.isBlank() || p2.isBlank()) continue
                                    if (p1 != currentUserId && p2 != currentUserId) continue

                                    val canonicalId = buildDeterministicConversationId(p1, p2)
                                    val sorted = listOf(p1, p2).sorted()
                                    val lastText = doc.getString("lastMessage") ?: doc.getString("lastMessageText") ?: ""
                                    val lastTime = doc.getLong("lastMessageAt") ?: doc.getLong("lastMessageTimestamp") ?: 0L
                                    val lastSender = doc.getString("lastMessageSenderId") ?: ""
                                    val lastStatus = doc.getString("lastMessageStatus") ?: MessageStatus.SENT.name
                                    val unread1 = doc.getLong("unreadCountForUser1")?.toInt() ?: 0
                                    val unread2 = doc.getLong("unreadCountForUser2")?.toInt() ?: 0
                                    val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                                    val conv = ConversationEntity(
                                        id = canonicalId,
                                        participant1Id = sorted[0],
                                        participant2Id = sorted[1],
                                        lastMessageText = lastText,
                                        lastMessageTimestamp = lastTime,
                                        lastMessageSenderId = lastSender,
                                        lastMessageStatus = lastStatus,
                                        unreadCountForUser1 = unread1,
                                        unreadCountForUser2 = unread2,
                                        updatedAt = updatedAt
                                    )
                                    conversationDao.insertConversation(conv)

                                    // Check inline typing fields on conversation document if present
                                    val otherId = if (sorted[0] == currentUserId) sorted[1] else sorted[0]
                                    if (otherId.isNotBlank()) {
                                        val isOtherTypingField = doc.getBoolean("typing_$otherId")
                                        val typingTimeField = doc.getLong("typingUpdatedAt_$otherId") ?: 0L
                                        if (isOtherTypingField != null) {
                                            val isFresh = (System.currentTimeMillis() - typingTimeField) < 10_000L
                                            if (isOtherTypingField && isFresh) {
                                                RealtimeManager.onUserTyping(canonicalId, otherId)
                                            } else if (!isOtherTypingField) {
                                                RealtimeManager.stopUserTyping(canonicalId, otherId)
                                            }
                                        }
                                    }

                                    // Also listen to messages and typing states in this conversation
                                    listenToConversationMessages(canonicalId, currentUserId)
                                    listenToConversationTyping(canonicalId, currentUserId)
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

    fun subscribeToConversation(conversationId: String, currentUserId: String) {
        listenToConversationMessages(conversationId, currentUserId)
        listenToConversationTyping(conversationId, currentUserId)
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
                                    val id = doc.getString("messageId") ?: doc.getString("id") ?: doc.id
                                    val senderId = doc.getString("senderId") ?: ""
                                    val recipientId = doc.getString("receiverId") ?: doc.getString("recipientId") ?: ""
                                    if (senderId.isBlank() || recipientId.isBlank()) continue
                                    if (senderId != currentUserId && recipientId != currentUserId) continue

                                    val canonicalConvId = buildDeterministicConversationId(senderId, recipientId)
                                    val content = doc.getString("text") ?: doc.getString("content") ?: ""
                                    val timestamp = doc.getLong("createdAt") ?: doc.getLong("timestamp") ?: System.currentTimeMillis()
                                    val cloudStatus = doc.getString("status") ?: MessageStatus.SENT.name
                                    val attachmentUri = doc.getString("attachmentUri")?.ifBlank { null }
                                    val attachmentType = doc.getString("attachmentType")?.ifBlank { null }
                                    val attachmentSize = doc.getLong("attachmentSize")?.takeIf { it > 0L }
                                    val attachmentName = doc.getString("attachmentName")?.ifBlank { null }

                                    val isViewing = recipientId == currentUserId &&
                                        RealtimeManager.isUserViewingConversation(currentUserId, canonicalConvId)

                                    val effectiveStatus = when {
                                        isViewing && cloudStatus != MessageStatus.READ.name -> {
                                            updateMessageStatusInCloud(canonicalConvId, id, MessageStatus.READ.name)
                                            MessageStatus.READ.name
                                        }
                                        recipientId == currentUserId && cloudStatus == MessageStatus.SENT.name -> {
                                            updateMessageStatusInCloud(canonicalConvId, id, MessageStatus.DELIVERED.name)
                                            MessageStatus.DELIVERED.name
                                        }
                                        else -> cloudStatus
                                    }

                                    val message = MessageEntity(
                                        id = id,
                                        conversationId = canonicalConvId,
                                        senderId = senderId,
                                        recipientId = recipientId,
                                        content = content,
                                        timestamp = timestamp,
                                        status = effectiveStatus,
                                        attachmentUri = attachmentUri,
                                        attachmentType = attachmentType,
                                        attachmentSize = attachmentSize,
                                        attachmentName = attachmentName
                                    )
                                    messageDao.insertMessage(message)
                                    conversationDao.updateLastMessageStatus(canonicalConvId, effectiveStatus)
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

    suspend fun syncUserToCloud(user: UserEntity, authVerifier: String? = null) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val cleanUser = cleanDisplayUsername(user.username)
            val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
            // Public searchable profile document — never expose passwordHash or private credentials
            val userMap = hashMapOf(
                "uid" to user.id,
                "id" to user.id,
                "username" to cleanUser,
                "usernameNormalized" to normUser,
                "displayName" to user.displayName,
                "photoURL" to user.avatarSeed,
                "avatarSeed" to user.avatarSeed,
                "statusMessage" to user.statusMessage,
                "online" to user.isOnline,
                "isOnline" to user.isOnline,
                "lastSeen" to user.lastSeenTimestamp,
                "lastSeenTimestamp" to user.lastSeenTimestamp,
                "createdAt" to user.createdAt
            )
            db.collection("users").document(user.id).set(userMap, SetOptions.merge()).await()

            val usernameIndexMap = hashMapOf<String, Any>(
                "uid" to user.id,
                "username" to cleanUser,
                "usernameNormalized" to normUser,
                "createdAt" to user.createdAt
            )
            if (!authVerifier.isNullOrBlank()) {
                usernameIndexMap["authVerifier"] = authVerifier
            }
            db.collection("usernames").document(normUser).set(usernameIndexMap, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(tag, "Failed to push user to cloud: ${e.message}")
        }
    }

    suspend fun checkUsernameExistsInCloud(rawUsername: String): Result<UserEntity?>? = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext null
        val norm = normalizeUsername(rawUsername)
        if (norm.isBlank()) return@withContext Result.success(null)
        try {
            val byNorm = db.collection("users")
                .whereEqualTo("usernameNormalized", norm)
                .limit(1)
                .get()
                .await()
            if (!byNorm.isEmpty) {
                val matched = parseAndUpsertUserDoc(byNorm.documents.first())
                return@withContext Result.success(matched)
            }

            val allDocs = db.collection("users").get().await()
            for (doc in allDocs.documents) {
                val parsed = parseAndUpsertUserDoc(doc)
                if (parsed != null && parsed.usernameNormalized == norm) {
                    return@withContext Result.success(parsed)
                }
            }
            Result.success(null)
        } catch (e: Exception) {
            Log.e(tag, "Firestore username check error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun authenticateUserInCloud(rawUsername: String, expectedAuthVerifier: String): Result<UserEntity>? = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext null
        val norm = normalizeUsername(rawUsername)
        if (norm.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid username"))
        }
        try {
            val indexDoc = db.collection("usernames").document(norm).get().await()
            if (indexDoc.exists()) {
                val storedVerifier = indexDoc.getString("authVerifier")
                if (!storedVerifier.isNullOrBlank() && storedVerifier != expectedAuthVerifier) {
                    return@withContext Result.failure(IllegalArgumentException("Incorrect password for @$norm"))
                }
            }
            val check = checkUsernameExistsInCloud(norm)
            val user = check?.getOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("No registered account found for @$norm"))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchUsersInCloud(rawQuery: String, excludeUserId: String): Result<List<UserEntity>>? = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext null
        val normQuery = normalizeUsername(rawQuery)
        try {
            val snapshot = db.collection("users").get().await()
            val results = mutableListOf<UserEntity>()
            for (doc in snapshot.documents) {
                val parsed = parseAndUpsertUserDoc(doc) ?: continue
                if (parsed.id == excludeUserId) continue
                if (normQuery.isBlank() ||
                    parsed.usernameNormalized.contains(normQuery) ||
                    parsed.username.lowercase().contains(normQuery) ||
                    parsed.displayName.lowercase().contains(normQuery)
                ) {
                    results.add(parsed)
                }
            }
            val sorted = results.sortedWith(
                compareBy<UserEntity> {
                    when {
                        it.usernameNormalized == normQuery -> 0
                        it.usernameNormalized.startsWith(normQuery) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.isOnline }.thenBy { it.displayName.lowercase() }
            )
            Result.success(sorted)
        } catch (e: Exception) {
            Log.e(tag, "Firestore search error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updatePresenceInCloud(userId: String, isOnline: Boolean, lastSeenTimestamp: Long) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val map = hashMapOf<String, Any>(
                "online" to isOnline,
                "isOnline" to isOnline,
                "lastSeen" to lastSeenTimestamp,
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
            val sorted = listOf(conversation.participant1Id, conversation.participant2Id).sorted()
            val canonicalId = buildDeterministicConversationId(sorted[0], sorted[1])
            val convMap = hashMapOf(
                "id" to canonicalId,
                "participant1Id" to sorted[0],
                "participant2Id" to sorted[1],
                "participants" to sorted,
                "participantIds" to sorted,
                "lastMessage" to conversation.lastMessageText,
                "lastMessageText" to conversation.lastMessageText,
                "lastMessageAt" to conversation.lastMessageTimestamp,
                "lastMessageTimestamp" to conversation.lastMessageTimestamp,
                "lastMessageSenderId" to conversation.lastMessageSenderId,
                "lastMessageStatus" to conversation.lastMessageStatus,
                "unreadCountForUser1" to conversation.unreadCountForUser1,
                "unreadCountForUser2" to conversation.unreadCountForUser2,
                "createdAt" to conversation.updatedAt,
                "updatedAt" to conversation.updatedAt
            )
            db.collection("conversations").document(canonicalId).set(convMap, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync conversation: ${e.message}")
        }
    }

    suspend fun syncMessageToCloud(message: MessageEntity) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val canonicalConvId = buildDeterministicConversationId(message.senderId, message.recipientId)
            val msgMap = hashMapOf(
                "messageId" to message.id,
                "id" to message.id,
                "conversationId" to canonicalConvId,
                "senderId" to message.senderId,
                "receiverId" to message.recipientId,
                "recipientId" to message.recipientId,
                "text" to message.content,
                "content" to message.content,
                "createdAt" to message.timestamp,
                "timestamp" to message.timestamp,
                "status" to message.status,
                "attachmentUri" to (message.attachmentUri ?: ""),
                "attachmentType" to (message.attachmentType ?: ""),
                "attachmentSize" to (message.attachmentSize ?: 0L),
                "attachmentName" to (message.attachmentName ?: "")
            )
            db.collection("conversations")
                .document(canonicalConvId)
                .collection("messages")
                .document(message.id)
                .set(msgMap, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync message: ${e.message}")
        }
    }

    suspend fun updateMessageStatusInCloud(conversationId: String, messageId: String, status: String) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document(messageId)
                .update("status", status)
            db.collection("conversations")
                .document(conversationId)
                .update("lastMessageStatus", status)
        } catch (e: Exception) {
            Log.e(tag, "Failed to update message status in cloud: ${e.message}")
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
            db.collection("conversations")
                .document(conversationId)
                .update("lastMessageStatus", MessageStatus.READ.name)
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
