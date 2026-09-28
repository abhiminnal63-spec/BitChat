package com.example.data.firestore

import android.content.Context
import android.util.Log
import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserConversationStateEntity
import com.example.data.model.UserDeviceEntity
import com.example.data.model.UserEntity
import com.example.data.model.buildDeterministicConversationId
import com.example.data.model.cleanDisplayUsername
import com.example.data.model.normalizeUsername
import com.example.data.realtime.RealtimeManager
import com.example.data.relay.GlobalRelayEngine
import com.example.notifications.BitchatNotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class FirestoreSyncState {
    ONLINE_SYNC_ACTIVE,
    OFFLINE_CACHE_ACTIVE,
    ERROR_RECONNECTING,
    CONNECTING
}

class FirestoreSyncManager(
    private val context: Context,
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val tag = "FirestoreSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var firestore: FirebaseFirestore? = null
    private val _isCloudConnected = MutableStateFlow(RealtimeManager.isNetworkConnected.value)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    private val _syncState = MutableStateFlow(
        if (RealtimeManager.isNetworkConnected.value) FirestoreSyncState.ONLINE_SYNC_ACTIVE else FirestoreSyncState.OFFLINE_CACHE_ACTIVE
    )
    val syncState: StateFlow<FirestoreSyncState> = _syncState.asStateFlow()

    private val listeners = mutableListOf<ListenerRegistration>()
    private val messageListeners = mutableMapOf<String, ListenerRegistration>()
    private val typingListeners = mutableMapOf<String, ListenerRegistration>()
    private val profileListeners = java.util.concurrent.ConcurrentHashMap<String, ListenerRegistration>()
    private val broadcastedMessageStatuses = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var globalUsersListener: ListenerRegistration? = null
    private var connectionHeartbeatListener: ListenerRegistration? = null
    private var currentActiveUserId: String? = null

    init {
        initializeFirestore()
        observeNetworkState()
    }

    private fun observeNetworkState() {
        scope.launch(Dispatchers.Unconfined) {
            RealtimeManager.isNetworkConnected.collect { connected ->
                if (!connected) {
                    _syncState.value = FirestoreSyncState.OFFLINE_CACHE_ACTIVE
                    _isCloudConnected.value = false
                } else {
                    _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                    _isCloudConnected.value = true
                }
            }
        }
        scope.launch {
            val relayFlow = try {
                GlobalRelayEngine.getInstance(context).isConnected
            } catch (_: Exception) {
                MutableStateFlow(false)
            }
            combine(RealtimeManager.isNetworkConnected, relayFlow) { connected, relayConnected ->
                connected to relayConnected
            }.collect { (connected, relayConnected) ->
                if (!connected) {
                    _syncState.value = FirestoreSyncState.OFFLINE_CACHE_ACTIVE
                    _isCloudConnected.value = false
                    return@collect
                }

                val db = firestore
                if (db != null) {
                    _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                    _isCloudConnected.value = true
                    try {
                        db.enableNetwork().addOnSuccessListener {
                            if (RealtimeManager.isNetworkConnected.value) {
                                _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                                _isCloudConnected.value = true
                            }
                        }
                        val uid = currentActiveUserId
                        if (!uid.isNullOrBlank()) {
                            ensureSyncListeners(uid)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to enable network on reconnect: ${e.message}")
                    }
                } else {
                    _isCloudConnected.value = true
                    _syncState.value = if (relayConnected || RealtimeManager.isNetworkConnected.value) {
                        FirestoreSyncState.ONLINE_SYNC_ACTIVE
                    } else {
                        FirestoreSyncState.CONNECTING
                    }
                }
            }
        }
    }

    private fun initializeFirestore() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                val db = FirebaseFirestore.getInstance()
                try {
                    val settings = FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                        .build()
                    db.firestoreSettings = settings
                } catch (e: Exception) {
                    Log.w(tag, "Firestore settings already applied or using defaults: ${e.message}")
                }
                db.enableNetwork().addOnSuccessListener {
                    if (RealtimeManager.isNetworkConnected.value) {
                        _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                        _isCloudConnected.value = true
                    }
                }
                firestore = db
                _isCloudConnected.value = RealtimeManager.isNetworkConnected.value
                _syncState.value = if (RealtimeManager.isNetworkConnected.value) {
                    FirestoreSyncState.ONLINE_SYNC_ACTIVE
                } else {
                    FirestoreSyncState.OFFLINE_CACHE_ACTIVE
                }
                Log.d(tag, "Firestore successfully initialized with persistent cache & live network enabled.")
                startGlobalUsersListener()
            } else {
                Log.w(tag, "FirebaseApp is not initialized with google-services.json. Operating with live cloud sync relay and persistent cache.")
                val online = RealtimeManager.isNetworkConnected.value
                _isCloudConnected.value = online
                _syncState.value = if (online) {
                    FirestoreSyncState.ONLINE_SYNC_ACTIVE
                } else {
                    FirestoreSyncState.OFFLINE_CACHE_ACTIVE
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Firestore init error: ${e.message}")
            _isCloudConnected.value = false
            _syncState.value = if (RealtimeManager.isNetworkConnected.value) FirestoreSyncState.ERROR_RECONNECTING else FirestoreSyncState.OFFLINE_CACHE_ACTIVE
        }
    }

    fun onAppForegrounded() {
        try {
            if (RealtimeManager.isNetworkConnected.value) {
                firestore?.enableNetwork()?.addOnSuccessListener {
                    if (RealtimeManager.isNetworkConnected.value) {
                        _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                        _isCloudConnected.value = true
                    }
                }
                if (firestore == null) {
                    _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                    _isCloudConnected.value = true
                }
            } else {
                _syncState.value = FirestoreSyncState.OFFLINE_CACHE_ACTIVE
                _isCloudConnected.value = false
            }
            val uid = currentActiveUserId
            if (!uid.isNullOrBlank()) {
                ensureSyncListeners(uid)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in onAppForegrounded: ${e.message}")
        }
    }

    private fun updateSyncStatusFromSnapshot(isFromCache: Boolean, hasError: Boolean) {
        if (!RealtimeManager.isNetworkConnected.value) {
            _syncState.value = FirestoreSyncState.OFFLINE_CACHE_ACTIVE
            _isCloudConnected.value = false
            return
        }

        if (hasError) {
            // If a specific listener hit a rule/query error while network is online and relay is active,
            // verify network enablement rather than leaving status stuck
            val relayActive = try {
                GlobalRelayEngine.getInstance(context).isConnected.value
            } catch (_: Exception) {
                false
            }
            if (relayActive) {
                _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                _isCloudConnected.value = true
            } else {
                _syncState.value = FirestoreSyncState.ERROR_RECONNECTING
                _isCloudConnected.value = false
            }
            return
        }

        if (!isFromCache) {
            _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
            _isCloudConnected.value = true
        } else {
            // Initial emission came from local cache; enableNetwork callback or MetadataChanges.INCLUDE
            // server emission will transition to ONLINE_SYNC_ACTIVE.
            firestore?.enableNetwork()?.addOnSuccessListener {
                if (RealtimeManager.isNetworkConnected.value) {
                    _syncState.value = FirestoreSyncState.ONLINE_SYNC_ACTIVE
                    _isCloudConnected.value = true
                }
            }
        }
    }

    private fun startGlobalUsersListener() {
        val db = firestore ?: return
        if (globalUsersListener != null) return
        try {
            globalUsersListener = db.collection("users")
                .limit(200)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Error listening to global users: ${error.message}")
                        updateSyncStatusFromSnapshot(isFromCache = true, hasError = true)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        updateSyncStatusFromSnapshot(isFromCache = snapshot.metadata.isFromCache, hasError = false)
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
            val statusMessage = doc.getString("statusMessage") ?: "Available on BITCHAT"
            val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            val lastSeenTimestamp = doc.getLong("lastSeen")
                ?: doc.getLong("lastSeenTimestamp")
                ?: createdAt
            val rawOnline = doc.getBoolean("online") ?: doc.getBoolean("isOnline") ?: false
            val isOnline = rawOnline && lastSeenTimestamp > 0L && (System.currentTimeMillis() - lastSeenTimestamp) < 90_000L

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
            userDao.upsertRemoteUser(user, allowPresenceUpdate = true)
            user
        } catch (e: Exception) {
            Log.e(tag, "User parse error: ${e.message}")
            null
        }
    }

    fun startSync(currentUserId: String) {
        val cleanUid = currentUserId.trim()
        if (cleanUid.isBlank()) return
        // Avoid tearing down active listeners (which would drop messageListeners for open chats)
        // unless the authenticated user actually changed.
        if (currentActiveUserId != null && currentActiveUserId != cleanUid) {
            stopSync()
        }
        currentActiveUserId = cleanUid
        ensureSyncListeners(cleanUid)
    }

    fun ensureSyncListeners(currentUserId: String) {
        currentActiveUserId = currentUserId
        val db = firestore ?: return

        try {
            startGlobalUsersListener()

            if (listeners.isEmpty()) {
                // 1. Listen to per-user conversation states: users/{uid}/conversations/{conversationId}
                val userConvStatesReg = db.collection("users")
                    .document(currentUserId)
                    .collection("conversations")
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null) {
                            Log.e(tag, "Error listening to user conversation states: ${error.message}")
                            updateSyncStatusFromSnapshot(isFromCache = true, hasError = true)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            updateSyncStatusFromSnapshot(isFromCache = snapshot.metadata.isFromCache, hasError = false)
                            scope.launch {
                                for (doc in snapshot.documents) {
                                    try {
                                        val convId = doc.getString("conversationId") ?: doc.id
                                        if (convId.isBlank()) continue
                                        val otherUserId = doc.getString("otherUserId") ?: ""
                                        val hidden = doc.getBoolean("hidden") ?: false
                                        val deletedAt = doc.getLong("deletedAt") ?: 0L
                                        val lastMessage = doc.getString("lastMessage") ?: ""
                                        val lastMessageAt = doc.getLong("lastMessageAt") ?: 0L
                                        val updatedAt = doc.getLong("updatedAt") ?: deletedAt

                                        val existingState = conversationDao.getUserConversationStateDirect(currentUserId, convId)
                                        if (existingState == null || updatedAt >= existingState.updatedAt || deletedAt >= existingState.deletedAt) {
                                            conversationDao.upsertUserConversationState(
                                                UserConversationStateEntity(
                                                    userId = currentUserId,
                                                    conversationId = convId,
                                                    otherUserId = otherUserId,
                                                    hidden = hidden,
                                                    deletedAt = maxOf(deletedAt, existingState?.deletedAt ?: 0L),
                                                    lastMessage = lastMessage,
                                                    lastMessageAt = lastMessageAt,
                                                    updatedAt = maxOf(updatedAt, existingState?.updatedAt ?: 0L)
                                                )
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(tag, "User conversation state parse error: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                listeners.add(userConvStatesReg)

                // 2. Listen to conversations where user is a participant
                val convReg = db.collection("conversations")
                    .whereArrayContains("participants", currentUserId)
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null) {
                            Log.e(tag, "Error listening to conversations: ${error.message}")
                            updateSyncStatusFromSnapshot(isFromCache = true, hasError = true)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            updateSyncStatusFromSnapshot(isFromCache = snapshot.metadata.isFromCache, hasError = false)
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

                // 3. Listen to FCM push notification queue for this recipient device
                listenToFcmPushQueueForUser(currentUserId)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting sync: ${e.message}")
        }
    }

    private fun listenToFcmPushQueueForUser(currentUserId: String) {
        val db = firestore ?: return
        try {
            val pushReg = db.collection("push_notifications")
                .whereEqualTo("receiverId", currentUserId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Push queue listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        scope.launch {
                            for (doc in snapshot.documents) {
                                try {
                                    val senderId = doc.getString("senderId") ?: ""
                                    val receiverId = doc.getString("receiverId") ?: ""
                                    if (senderId.isBlank() || receiverId != currentUserId || senderId == currentUserId) continue
                                    val msgId = doc.getString("messageId") ?: doc.id
                                    val convId = doc.getString("conversationId")
                                        ?: buildDeterministicConversationId(senderId, receiverId)
                                    val senderName = doc.getString("senderName") ?: "Contact"
                                    val type = doc.getString("type") ?: "text"
                                    val text = doc.getString("text") ?: ""
                                    val mediaUrl = doc.getString("mediaUrl") ?: ""
                                    val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    val notifBody = doc.getString("notificationBody") ?: text

                                    val dataMap = mapOf(
                                        "messageId" to msgId,
                                        "conversationId" to convId,
                                        "senderId" to senderId,
                                        "receiverId" to receiverId,
                                        "senderName" to senderName,
                                        "type" to type,
                                        "text" to text,
                                        "mediaUrl" to mediaUrl,
                                        "createdAt" to createdAt.toString()
                                    )
                                    com.example.notifications.BitchatMessagingService.handleIncomingFcmData(
                                        context = context.applicationContext,
                                        data = dataMap,
                                        fallbackTitle = senderName,
                                        fallbackBody = notifBody
                                    )
                                } catch (e: Exception) {
                                    Log.e(tag, "FCM push doc parse error: ${e.message}")
                                }
                            }
                        }
                    }
                }
            listeners.add(pushReg)
        } catch (e: Exception) {
            Log.e(tag, "Failed to listen to FCM push queue: ${e.message}")
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
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Messages listener error: ${error.message}")
                        updateSyncStatusFromSnapshot(isFromCache = true, hasError = true)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val isSnapshotFromCache = snapshot.metadata.isFromCache
                        updateSyncStatusFromSnapshot(isFromCache = isSnapshotFromCache, hasError = false)
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
                                    val hasPendingWrites = doc.metadata.hasPendingWrites()
                                    val mediaUrl = doc.getString("mediaUrl")?.ifBlank { null }
                                    val attachmentUri = doc.getString("attachmentUri")?.ifBlank { null }
                                    val attachmentType = doc.getString("attachmentType")?.ifBlank { null }
                                    val attachmentSize = doc.getLong("attachmentSize")?.takeIf { it > 0L }
                                    val attachmentName = doc.getString("attachmentName")?.ifBlank { null }
                                    val rawType = doc.getString("type") ?: ""
                                    val isImage = rawType.equals("image", ignoreCase = true) ||
                                        !mediaUrl.isNullOrBlank() ||
                                        !attachmentUri.isNullOrBlank() ||
                                        attachmentType != null

                                    if (senderId != currentUserId && timestamp > 0L) {
                                        userDao.recordPeerActivity(senderId, timestamp)
                                    }

                                    val existingMsg = messageDao.getMessageByIdDirect(id)
                                    val isAppForeground = BitchatNotificationManager.isAppEffectivelyInForeground(context)
                                    val isViewing = recipientId == currentUserId &&
                                        isAppForeground &&
                                        RealtimeManager.isUserViewingConversation(currentUserId, canonicalConvId)

                                    val effectiveStatus = when {
                                        // Outgoing optimistic write still in local pending queue: keep SENDING until server confirms
                                        senderId == currentUserId && hasPendingWrites && existingMsg?.status == MessageStatus.SENDING.name -> {
                                            MessageStatus.SENDING.name
                                        }
                                        isViewing && cloudStatus != MessageStatus.READ.name -> {
                                            if (broadcastedMessageStatuses[id] != MessageStatus.READ.name) {
                                                broadcastedMessageStatuses[id] = MessageStatus.READ.name
                                                updateMessageStatusInCloud(canonicalConvId, id, MessageStatus.READ.name)
                                            }
                                            MessageStatus.READ.name
                                        }
                                        recipientId == currentUserId && cloudStatus == MessageStatus.SENT.name -> {
                                            if (broadcastedMessageStatuses[id] == null) {
                                                broadcastedMessageStatuses[id] = MessageStatus.DELIVERED.name
                                                updateMessageStatusInCloud(canonicalConvId, id, MessageStatus.DELIVERED.name)
                                            }
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
                                        type = if (isImage) "image" else "text",
                                        mediaUrl = mediaUrl ?: attachmentUri,
                                        attachmentUri = attachmentUri ?: mediaUrl,
                                        attachmentType = attachmentType ?: if (isImage) "IMAGE" else null,
                                        attachmentSize = attachmentSize,
                                        attachmentName = attachmentName
                                    )
                                    messageDao.upsertMessageSafely(message)
                                    conversationDao.updateLastMessageStatus(canonicalConvId, effectiveStatus)

                                    // When the app is in the background, on the home screen, or on the lock screen,
                                    // ensure incoming messages received via Firestore listeners trigger system notifications
                                    // (deduplicated by messageId inside BitchatNotificationManager).
                                    if (recipientId == currentUserId && !isViewing && !isAppForeground && effectiveStatus != MessageStatus.READ.name) {
                                        val senderUser = userDao.getUserByIdDirect(senderId)
                                        val senderName = doc.getString("senderName")?.takeIf { it.isNotBlank() }
                                            ?: senderUser?.displayName?.takeIf { it.isNotBlank() }
                                            ?: senderUser?.username?.takeIf { it.isNotBlank() }
                                            ?: "Contact"
                                        BitchatNotificationManager.showIncomingMessageNotification(
                                            context = context.applicationContext,
                                            recipientId = currentUserId,
                                            message = message,
                                            senderDisplayName = senderName
                                        )
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
        if (normQuery.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        try {
            val snapshot = db.collection("users").get().await()
            val results = mutableListOf<UserEntity>()
            for (doc in snapshot.documents) {
                val parsed = parseAndUpsertUserDoc(doc) ?: continue
                if (parsed.id == excludeUserId) continue
                if (parsed.usernameNormalized.contains(normQuery) ||
                    parsed.username.lowercase().contains(normQuery)
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

    suspend fun fetchUserByIdFromCloud(userId: String): Result<UserEntity?> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.success(null)
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return@withContext Result.success(null)
        try {
            val doc = db.collection("users").document(cleanUid).get().await()
            if (doc.exists()) {
                val user = parseAndUpsertUserDoc(doc)
                listenToUserProfile(cleanUid)
                Result.success(user)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(tag, "Firestore fetchUserById error for $cleanUid: ${e.message}")
            Result.failure(e)
        }
    }

    fun listenToUserProfile(userId: String) {
        val db = firestore ?: return
        val cleanUid = userId.trim()
        if (cleanUid.isBlank() || profileListeners.containsKey(cleanUid)) return
        try {
            val reg = db.collection("users").document(cleanUid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(tag, "Profile listener error for $cleanUid: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    scope.launch {
                        parseAndUpsertUserDoc(snapshot)
                    }
                }
            }
            profileListeners[cleanUid] = reg
        } catch (e: Exception) {
            Log.e(tag, "Failed to listen to user profile $cleanUid: ${e.message}")
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

    suspend fun syncUserConversationStateToCloud(state: UserConversationStateEntity) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        if (state.userId.isBlank() || state.conversationId.isBlank()) return@withContext
        try {
            val stateMap = hashMapOf<String, Any>(
                "conversationId" to state.conversationId,
                "userId" to state.userId,
                "otherUserId" to state.otherUserId,
                "hidden" to state.hidden,
                "deletedAt" to state.deletedAt,
                "lastMessage" to state.lastMessage,
                "lastMessageAt" to state.lastMessageAt,
                "updatedAt" to state.updatedAt
            )
            db.collection("users")
                .document(state.userId)
                .collection("conversations")
                .document(state.conversationId)
                .set(stateMap, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync user conversation state: ${e.message}")
        }
    }

    suspend fun syncMessageToCloud(message: MessageEntity): Boolean = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext false
        try {
            val canonicalConvId = buildDeterministicConversationId(message.senderId, message.recipientId)
            val isImage = message.type.equals("image", ignoreCase = true) ||
                !message.mediaUrl.isNullOrBlank() ||
                !message.attachmentUri.isNullOrBlank()
            val msgMap = hashMapOf(
                "messageId" to message.id,
                "id" to message.id,
                "conversationId" to canonicalConvId,
                "senderId" to message.senderId,
                "receiverId" to message.recipientId,
                "recipientId" to message.recipientId,
                "type" to if (isImage) "image" else "text",
                "text" to message.content,
                "content" to message.content,
                "mediaUrl" to (message.mediaUrl ?: message.attachmentUri ?: ""),
                "createdAt" to message.timestamp,
                "timestamp" to message.timestamp,
                "status" to MessageStatus.SENT.name,
                "attachmentUri" to (message.attachmentUri ?: message.mediaUrl ?: ""),
                "attachmentType" to (message.attachmentType ?: if (isImage) "IMAGE" else ""),
                "attachmentSize" to (message.attachmentSize ?: 0L),
                "attachmentName" to (message.attachmentName ?: "")
            )
            // Combine both message document writes into a single atomic WriteBatch instead of sequential awaits
            val batch = db.batch()
            val convMsgRef = db.collection("conversations")
                .document(canonicalConvId)
                .collection("messages")
                .document(message.id)
            val rootMsgRef = db.collection("messages")
                .document(message.id)
            batch.set(convMsgRef, msgMap, SetOptions.merge())
            batch.set(rootMsgRef, msgMap, SetOptions.merge())
            withTimeoutOrNull(4_500L) {
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync message: ${e.message}")
            false
        }
    }

    private val pendingOfflineMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isMessageQueuedOffline(messageId: String): Boolean {
        return pendingOfflineMessageIds.contains(messageId)
    }

    suspend fun syncMessageAndConversationToCloud(
        message: MessageEntity,
        conversation: ConversationEntity
    ): Boolean {
        return syncMessageAndConversationBatch(message, conversation)
    }

    /**
     * Combines the message write (`conversations/{id}/messages/{msgId}` + `messages/{msgId}`)
     * and the conversation summary update (`conversations/{id}`) into a SINGLE atomic Firestore WriteBatch
     * so sending a message requires only 1 network round-trip instead of 3 sequential writes.
     */
    suspend fun syncMessageAndConversationBatch(
        message: MessageEntity,
        conversation: ConversationEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext false
        try {
            pendingOfflineMessageIds.add(message.id)
            val sorted = listOf(conversation.participant1Id, conversation.participant2Id).sorted()
            val canonicalConvId = buildDeterministicConversationId(message.senderId, message.recipientId)
            val isImage = message.type.equals("image", ignoreCase = true) ||
                !message.mediaUrl.isNullOrBlank() ||
                !message.attachmentUri.isNullOrBlank()
            val msgMap = hashMapOf(
                "messageId" to message.id,
                "id" to message.id,
                "conversationId" to canonicalConvId,
                "senderId" to message.senderId,
                "receiverId" to message.recipientId,
                "recipientId" to message.recipientId,
                "type" to if (isImage) "image" else "text",
                "text" to message.content,
                "content" to message.content,
                "mediaUrl" to (message.mediaUrl ?: message.attachmentUri ?: ""),
                "createdAt" to message.timestamp,
                "timestamp" to message.timestamp,
                "status" to MessageStatus.SENT.name,
                "attachmentUri" to (message.attachmentUri ?: message.mediaUrl ?: ""),
                "attachmentType" to (message.attachmentType ?: if (isImage) "IMAGE" else ""),
                "attachmentSize" to (message.attachmentSize ?: 0L),
                "attachmentName" to (message.attachmentName ?: "")
            )
            val convMap = hashMapOf(
                "id" to canonicalConvId,
                "participant1Id" to sorted[0],
                "participant2Id" to sorted[1],
                "participants" to sorted,
                "participantIds" to sorted,
                "lastMessage" to conversation.lastMessageText,
                "lastMessageText" to conversation.lastMessageText,
                "lastMessageAt" to conversation.lastMessageTimestamp,
                "lastMessageTimestamp" to conversation.lastMessageTimestamp,
                "lastMessageSenderId" to conversation.lastMessageSenderId,
                "lastMessageStatus" to MessageStatus.SENT.name,
                "unreadCountForUser1" to conversation.unreadCountForUser1,
                "unreadCountForUser2" to conversation.unreadCountForUser2,
                "createdAt" to conversation.updatedAt,
                "updatedAt" to conversation.updatedAt
            )
            val batch = db.batch()
            val convMsgRef = db.collection("conversations")
                .document(canonicalConvId)
                .collection("messages")
                .document(message.id)
            val rootMsgRef = db.collection("messages")
                .document(message.id)
            val convRef = db.collection("conversations")
                .document(canonicalConvId)
            batch.set(convMsgRef, msgMap, SetOptions.merge())
            batch.set(rootMsgRef, msgMap, SetOptions.merge())
            batch.set(convRef, convMap, SetOptions.merge())
            val committed = withTimeoutOrNull(4_500L) {
                batch.commit().await()
                true
            } ?: false
            if (committed) {
                pendingOfflineMessageIds.remove(message.id)
            }
            committed
        } catch (e: Exception) {
            Log.e(tag, "Failed to batch sync message and conversation: ${e.message}")
            false
        }
    }

    suspend fun registerUserDeviceInCloud(device: UserDeviceEntity) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val deviceMap = hashMapOf<String, Any>(
                "deviceId" to device.deviceId,
                "fcmToken" to device.fcmToken,
                "platform" to device.platform,
                "updatedAt" to device.updatedAt
            )
            val userTokenUpdate = hashMapOf<String, Any>(
                "fcmToken" to device.fcmToken,
                "fcmTokens" to FieldValue.arrayUnion(device.fcmToken),
                "tokenUpdatedAt" to device.updatedAt
            )
            val batch = db.batch()
            val userRef = db.collection("users").document(device.userId)
            val deviceRef = userRef.collection("devices").document(device.deviceId)
            batch.set(deviceRef, deviceMap, SetOptions.merge())
            batch.set(userRef, userTokenUpdate, SetOptions.merge())
            withTimeoutOrNull(4_000L) {
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to register user device in cloud: ${e.message}")
        }
    }

    suspend fun fetchUserDevicesFromCloud(userId: String): List<UserDeviceEntity> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext emptyList()
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return@withContext emptyList()
        try {
            val snapshot = withTimeoutOrNull(3_500L) {
                db.collection("users")
                    .document(cleanUid)
                    .collection("devices")
                    .get()
                    .await()
            } ?: return@withContext emptyList()
            snapshot.documents.mapNotNull { doc ->
                val token = doc.getString("fcmToken")?.trim() ?: return@mapNotNull null
                if (token.isBlank()) return@mapNotNull null
                val devId = doc.getString("deviceId")?.trim()?.ifBlank { doc.id } ?: doc.id
                val platform = doc.getString("platform") ?: "android"
                val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                UserDeviceEntity(
                    userId = cleanUid,
                    deviceId = devId,
                    fcmToken = token,
                    platform = platform,
                    updatedAt = updatedAt
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch user devices from cloud: ${e.message}")
            emptyList()
        }
    }

    suspend fun removeUserDeviceFromCloud(
        userId: String,
        deviceId: String,
        fcmToken: String? = null
    ) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        val cleanUid = userId.trim()
        val cleanDevId = deviceId.trim()
        if (cleanUid.isBlank() || cleanDevId.isBlank()) return@withContext
        try {
            val batch = db.batch()
            val userRef = db.collection("users").document(cleanUid)
            val deviceRef = userRef.collection("devices").document(cleanDevId)
            batch.delete(deviceRef)
            if (!fcmToken.isNullOrBlank()) {
                batch.update(
                    userRef,
                    mapOf(
                        "fcmTokens" to FieldValue.arrayRemove(fcmToken.trim()),
                        "fcmToken" to ""
                    )
                )
            }
            withTimeoutOrNull(4_000L) {
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to remove device token from cloud: ${e.message}")
        }
    }

    suspend fun enqueuePushNotificationInCloud(
        message: MessageEntity,
        senderDisplayName: String,
        devices: List<UserDeviceEntity>
    ) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val isImage = message.type.equals("image", ignoreCase = true) ||
                !message.mediaUrl.isNullOrBlank() ||
                !message.attachmentUri.isNullOrBlank()
            val bodyPreview = BitchatNotificationManager.formatNotificationBody(
                type = if (isImage) "image" else message.type,
                content = message.content,
                hasMedia = isImage
            )
            val pushDoc = hashMapOf<String, Any>(
                "messageId" to message.id,
                "conversationId" to message.conversationId,
                "senderId" to message.senderId,
                "receiverId" to message.recipientId,
                "senderName" to senderDisplayName,
                "appTitle" to "BITCHAT",
                "type" to if (isImage) "image" else "text",
                "text" to message.content,
                "notificationBody" to bodyPreview,
                "collapseKey" to "bitchat_conv_${message.conversationId}",
                "notificationTag" to "bitchat_conv_${message.conversationId}",
                "groupKey" to BitchatNotificationManager.GROUP_KEY_MESSAGES,
                "channelId" to BitchatNotificationManager.CHANNEL_ID,
                "fcmTokens" to devices.map { it.fcmToken },
                "deviceIds" to devices.map { it.deviceId },
                "createdAt" to message.timestamp,
                "status" to "queued"
            )
            db.collection("push_notifications")
                .document(message.id)
                .set(pushDoc, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(tag, "Failed to enqueue push notification in cloud: ${e.message}")
        }
    }

    suspend fun synchronizeOfflineMessagesForUser(recipientUid: String): Int {
        return syncMissedMessagesForRecipient(recipientUid)
    }

    suspend fun syncMissedMessagesForRecipient(recipientUid: String): Int = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext 0
        val cleanUid = recipientUid.trim()
        if (cleanUid.isBlank()) return@withContext 0
        var syncedCount = 0
        try {
            val snapshot = db.collection("messages")
                .whereEqualTo("receiverId", cleanUid)
                .get()
                .await()
            for (doc in snapshot.documents) {
                val id = doc.getString("messageId") ?: doc.getString("id") ?: doc.id
                val senderId = doc.getString("senderId") ?: ""
                val receiverId = doc.getString("receiverId") ?: doc.getString("recipientId") ?: ""
                if (senderId.isBlank() || receiverId != cleanUid) continue
                val canonicalConvId = buildDeterministicConversationId(senderId, receiverId)
                val content = doc.getString("text") ?: doc.getString("content") ?: ""
                val timestamp = doc.getLong("createdAt") ?: doc.getLong("timestamp") ?: System.currentTimeMillis()
                val cloudStatus = doc.getString("status") ?: MessageStatus.SENT.name
                val rawType = doc.getString("type") ?: "text"
                val mediaUrl = doc.getString("mediaUrl")?.ifBlank { null }
                val attachmentUri = doc.getString("attachmentUri")?.ifBlank { null }
                val isImage = rawType.equals("image", ignoreCase = true) || !mediaUrl.isNullOrBlank() || !attachmentUri.isNullOrBlank()

                val isAppForeground = BitchatNotificationManager.isAppEffectivelyInForeground(context)
                val isViewing = isAppForeground &&
                    RealtimeManager.isUserViewingConversation(cleanUid, canonicalConvId)
                val nextStatus = when {
                    isViewing -> MessageStatus.READ.name
                    cloudStatus == MessageStatus.SENT.name -> MessageStatus.DELIVERED.name
                    else -> cloudStatus
                }
                if (nextStatus != cloudStatus) {
                    updateMessageStatusInCloud(canonicalConvId, id, nextStatus)
                }
                val msg = MessageEntity(
                    id = id,
                    conversationId = canonicalConvId,
                    senderId = senderId,
                    recipientId = receiverId,
                    content = content,
                    timestamp = timestamp,
                    status = nextStatus,
                    type = if (isImage) "image" else "text",
                    mediaUrl = mediaUrl ?: attachmentUri,
                    attachmentUri = attachmentUri ?: mediaUrl,
                    attachmentType = if (isImage) "IMAGE" else null
                )
                messageDao.upsertMessageSafely(msg)
                if (!isViewing && !isAppForeground && nextStatus != MessageStatus.READ.name) {
                    val senderUser = userDao.getUserByIdDirect(senderId)
                    val senderName = doc.getString("senderName")?.takeIf { it.isNotBlank() }
                        ?: senderUser?.displayName?.takeIf { it.isNotBlank() }
                        ?: senderUser?.username?.takeIf { it.isNotBlank() }
                        ?: "Contact"
                    BitchatNotificationManager.showIncomingMessageNotification(
                        context = context.applicationContext,
                        recipientId = cleanUid,
                        message = msg,
                        senderDisplayName = senderName
                    )
                }
                syncedCount++
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync missed messages for $cleanUid: ${e.message}")
        }
        syncedCount
    }

    suspend fun updateMessageStatusInCloud(conversationId: String, messageId: String, status: String) = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext
        try {
            val batch = db.batch()
            val convMsgRef = db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document(messageId)
            val rootMsgRef = db.collection("messages")
                .document(messageId)
            val convRef = db.collection("conversations")
                .document(conversationId)
            batch.update(convMsgRef, "status", status)
            batch.update(rootMsgRef, "status", status)
            batch.update(convRef, "lastMessageStatus", status)
            batch.commit()
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
        for ((_, listener) in profileListeners) {
            listener.remove()
        }
        profileListeners.clear()
    }

    companion object {
        @Volatile
        private var INSTANCE: FirestoreSyncManager? = null

        fun getInstance(context: Context): FirestoreSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val db = com.example.data.database.EasappDatabase.getInstance(appCtx)
                    FirestoreSyncManager(
                        context = appCtx,
                        userDao = db.userDao(),
                        conversationDao = db.conversationDao(),
                        messageDao = db.messageDao()
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}
