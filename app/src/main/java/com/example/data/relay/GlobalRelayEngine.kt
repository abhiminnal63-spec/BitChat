package com.example.data.relay

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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GlobalRelayEngine(
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val tag = "GlobalRelay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseTopic = "easapp_cloud_v3"

    private val streamClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for SSE stream
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val queryClient = OkHttpClient.Builder()
        .readTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val textMediaType = "text/plain; charset=utf-8".toMediaType()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val cloudUsersCache = ConcurrentHashMap<String, UserEntity>()
    // Maps usernameNormalized -> backend authVerifier hash (never exposed to UI)
    private val cloudAuthVerifiers = ConcurrentHashMap<String, String>()
    // Tracks processed message/status event signatures to make sync idempotent
    private val processedMessageStatuses = ConcurrentHashMap<String, String>()

    private val cloudSyncMutex = Mutex()
    @Volatile
    private var lastCloudSyncTimestamp: Long = 0L

    private var multiplexedStreamJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var activeConversationId: String? = null
    @Volatile
    private var currentUserId: String? = null

    init {
        restartMultiplexedStream()
    }

    /**
     * Uses a SINGLE multiplexed SSE connection per device (`users` + `user-{uid}`)
     * so two physical devices on the same Wi-Fi router never hit per-IP connection limits.
     */
    private fun restartMultiplexedStream() {
        multiplexedStreamJob?.cancel()
        val uid = currentUserId
        val topics = if (!uid.isNullOrBlank()) {
            "$baseTopic-users,$baseTopic-user-$uid"
        } else {
            "$baseTopic-users"
        }

        multiplexedStreamJob = scope.launch {
            listenToMultiplexedStream(topics)
        }
    }

    fun start(userId: String) {
        val changed = currentUserId != userId
        currentUserId = userId
        if (changed || multiplexedStreamJob?.isActive != true) {
            restartMultiplexedStream()
        }

        // Start background sync loop + immediate history pull for this authenticated backend UID
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            // Immediate initial sync of cloud users and user's cloud message/conversation inbox
            try {
                syncUsersFromCloud("")
                syncUserInboxFromCloud(userId)
            } catch (_: Exception) {
            }

            while (isActive) {
                delay(4000L)
                if (RealtimeManager.isNetworkConnected.value) {
                    try {
                        val activeUid = currentUserId ?: break
                        syncUserInboxFromCloud(activeUid)
                        activeConversationId?.let { convId ->
                            syncConversationHistoryFromCloud(convId)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun subscribeToConversation(conversationId: String) {
        activeConversationId = conversationId
        scope.launch {
            if (RealtimeManager.isNetworkConnected.value) {
                syncConversationHistoryFromCloud(conversationId)
            }
        }
    }

    fun unsubscribeFromConversation(conversationId: String) {
        if (activeConversationId == conversationId) {
            activeConversationId = null
        }
    }

    private suspend fun listenToMultiplexedStream(topicsCsv: String) {
        var firstConnect = true
        while (scope.isActive) {
            var retryDelayMs = 3000L
            if (!RealtimeManager.isNetworkConnected.value) {
                _isConnected.value = false
                delay(2000L)
                continue
            }
            try {
                val url = if (firstConnect) {
                    "https://ntfy.sh/$topicsCsv/json?since=all"
                } else {
                    "https://ntfy.sh/$topicsCsv/json"
                }
                val request = Request.Builder()
                    .url(url)
                    .build()

                streamClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _isConnected.value = true
                        firstConnect = false
                        val inputStream = response.body?.byteStream() ?: return@use
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        var line: String?

                        while (reader.readLine().also { line = it } != null) {
                            if (!RealtimeManager.isNetworkConnected.value) break
                            val lineStr = line?.trim() ?: continue
                            if (lineStr.isBlank()) continue

                            try {
                                val wrapper = JSONObject(lineStr)
                                val event = wrapper.optString("event", "")
                                if (event == "message") {
                                    val messageContent = wrapper.optString("message", "")
                                    if (messageContent.isNotBlank()) {
                                        dispatchIncomingPayload(messageContent)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Stream parse error: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(tag, "Multiplexed stream HTTP ${response.code}")
                        if (response.code == 429) {
                            retryDelayMs = 5000L
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Multiplexed stream disconnected: ${e.message}")
                _isConnected.value = false
            }
            delay(retryDelayMs)
        }
    }

    private suspend fun dispatchIncomingPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            when (json.optString("action")) {
                "USER_REGISTERED", "USER_UPDATED", "PRESENCE_UPDATE" -> {
                    handleIncomingUserPayload(json)
                }
                "NEW_MESSAGE", "MESSAGE_STATUS", "READ_RECEIPT", "TYPING_INDICATOR", "CONVERSATION_SYNC" -> {
                    handleIncomingMessagingPayload(json)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to dispatch payload: ${e.message}")
        }
    }

    private fun parseUserFromJsonObject(userObj: JSONObject): UserEntity? {
        val id = userObj.optString("uid", userObj.optString("id", "")).trim()
        val rawUsername = userObj.optString("username", "").trim()
        if (id.isBlank() || rawUsername.isBlank()) return null

        val cleanUser = cleanDisplayUsername(rawUsername)
        val normUser = userObj.optString("usernameNormalized", "")
            .let { normalizeUsername(it).ifBlank { normalizeUsername(cleanUser) } }
        if (normUser.isBlank()) return null

        val verifier = userObj.optString("authVerifier", "").trim()
        if (verifier.isNotBlank()) {
            cloudAuthVerifiers[normUser] = verifier
        }

        val lastSeen = userObj.optLong(
            "lastSeen",
            userObj.optLong("lastSeenTimestamp", System.currentTimeMillis())
        )
        val rawOnline = userObj.optBoolean("online", userObj.optBoolean("isOnline", false))
        // Do not mark a remote account online if its presence timestamp is stale (> 5 minutes)
        val isFreshOnline = rawOnline && (System.currentTimeMillis() - lastSeen) < 300_000L

        return UserEntity(
            id = id,
            username = cleanUser,
            usernameNormalized = normUser,
            displayName = userObj.optString("displayName", cleanUser).ifBlank { cleanUser },
            passwordHash = "", // Security: never expose passwordHash to UserEntity from cloud
            avatarSeed = userObj.optString("photoURL", userObj.optString("avatarSeed", "BRUTAL_1")),
            statusMessage = userObj.optString("statusMessage", "Available on Easapp"),
            isOnline = isFreshOnline,
            lastSeenTimestamp = lastSeen,
            createdAt = userObj.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private suspend fun handleIncomingUserPayload(json: JSONObject): UserEntity? {
        return try {
            when (json.optString("action")) {
                "USER_REGISTERED", "USER_UPDATED" -> {
                    val userObj = json.getJSONObject("user")
                    val user = parseUserFromJsonObject(userObj) ?: return null
                    cloudUsersCache[user.id] = user
                    userDao.upsertRemoteUser(user)
                    user
                }

                "PRESENCE_UPDATE" -> {
                    val userId = json.optString("uid", json.optString("userId", ""))
                    if (userId.isBlank() || userId == currentUserId) return null
                    val isOnline = json.optBoolean("online", json.optBoolean("isOnline", false))
                    val timestamp = json.optLong("lastSeen", json.optLong("lastSeenTimestamp", System.currentTimeMillis()))
                    val effectiveOnline = isOnline && (System.currentTimeMillis() - timestamp) < 300_000L
                    cloudUsersCache[userId]?.let { existing ->
                        if (timestamp >= existing.lastSeenTimestamp) {
                            cloudUsersCache[userId] = existing.copy(
                                isOnline = effectiveOnline,
                                lastSeenTimestamp = timestamp
                            )
                        }
                    }
                    userDao.updateOnlineStatus(userId, effectiveOnline, timestamp)
                    null
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(tag, "User payload error: ${e.message}")
            null
        }
    }

    private fun statusRank(status: String): Int = when (status) {
        MessageStatus.SENDING.name -> 0
        MessageStatus.SENT.name -> 1
        MessageStatus.DELIVERED.name -> 2
        MessageStatus.READ.name -> 3
        else -> 1
    }

    private suspend fun handleIncomingMessagingPayload(json: JSONObject) {
        try {
            val myUid = currentUserId ?: return
            when (json.optString("action")) {
                "CONVERSATION_SYNC" -> {
                    val convObj = json.optJSONObject("conversation") ?: return
                    val p1 = convObj.optString("participant1Id", "")
                    val p2 = convObj.optString("participant2Id", "")
                    if (p1.isBlank() || p2.isBlank()) return
                    // Security rule: only process conversations that the authenticated user participates in
                    if (p1 != myUid && p2 != myUid) return

                    json.optJSONObject("participant1Profile")?.let { parseUserFromJsonObject(it) }?.let {
                        cloudUsersCache[it.id] = it
                        userDao.upsertRemoteUser(it)
                    }
                    json.optJSONObject("participant2Profile")?.let { parseUserFromJsonObject(it) }?.let {
                        cloudUsersCache[it.id] = it
                        userDao.upsertRemoteUser(it)
                    }

                    val detId = buildDeterministicConversationId(p1, p2)
                    val existing = conversationDao.getConversationByIdDirect(detId)
                    if (existing == null) {
                        val sorted = listOf(p1, p2).sorted()
                        val created = ConversationEntity(
                            id = detId,
                            participant1Id = sorted[0],
                            participant2Id = sorted[1],
                            lastMessageText = convObj.optString("lastMessage", ""),
                            lastMessageTimestamp = convObj.optLong("lastMessageAt", 0L),
                            lastMessageSenderId = convObj.optString("lastMessageSenderId", ""),
                            lastMessageStatus = convObj.optString("lastMessageStatus", MessageStatus.SENT.name),
                            unreadCountForUser1 = 0,
                            unreadCountForUser2 = 0,
                            updatedAt = convObj.optLong("updatedAt", System.currentTimeMillis())
                        )
                        conversationDao.insertConversation(created)
                    }
                }

                "NEW_MESSAGE" -> {
                    val msgObj = json.optJSONObject("message") ?: return
                    val senderId = msgObj.optString("senderId", "").trim()
                    val recipientId = msgObj.optString("receiverId", msgObj.optString("recipientId", "")).trim()
                    if (senderId.isBlank() || recipientId.isBlank()) return

                    // Security rule: only participants of this conversation may read/process this message
                    if (senderId != myUid && recipientId != myUid) return

                    // Upsert embedded sender and receiver profiles immediately so CHATS list never misses the user
                    json.optJSONObject("senderProfile")?.let { parseUserFromJsonObject(it) }?.let { senderUser ->
                        if (senderUser.id == senderId) {
                            cloudUsersCache[senderUser.id] = senderUser
                            userDao.upsertRemoteUser(senderUser)
                        }
                    }
                    json.optJSONObject("receiverProfile")?.let { parseUserFromJsonObject(it) }?.let { receiverUser ->
                        if (receiverUser.id == recipientId) {
                            cloudUsersCache[receiverUser.id] = receiverUser
                            userDao.upsertRemoteUser(receiverUser)
                        }
                    }

                    // Ensure both participants exist in local userDao cache
                    val otherPeerId = if (senderId == myUid) recipientId else senderId
                    if (userDao.getUserByIdDirect(otherPeerId) == null) {
                        cloudUsersCache[otherPeerId]?.let { userDao.upsertRemoteUser(it) }
                    }

                    val messageId = msgObj.optString("messageId", msgObj.optString("id", "")).trim()
                    if (messageId.isBlank()) return

                    val canonicalConvId = buildDeterministicConversationId(senderId, recipientId)
                    val content = msgObj.optString("text", msgObj.optString("content", ""))
                    val timestamp = msgObj.optLong("createdAt", msgObj.optLong("timestamp", System.currentTimeMillis()))
                    val incomingStatus = msgObj.optString("status", MessageStatus.SENT.name)

                    val attachmentUri = if (msgObj.has("attachmentUri") && !msgObj.isNull("attachmentUri")) {
                        msgObj.optString("attachmentUri", "").ifBlank { null }
                    } else null
                    val attachmentType = if (msgObj.has("attachmentType") && !msgObj.isNull("attachmentType")) {
                        msgObj.optString("attachmentType", "").ifBlank { null }
                    } else null
                    val attachmentName = if (msgObj.has("attachmentName") && !msgObj.isNull("attachmentName")) {
                        msgObj.optString("attachmentName", "").ifBlank { null }
                    } else null
                    val attachmentSize = if (msgObj.has("attachmentSize") && !msgObj.isNull("attachmentSize")) {
                        msgObj.optLong("attachmentSize", 0L).takeIf { it > 0L }
                    } else null

                    val isRecipientMe = recipientId == myUid
                    val isViewing = isRecipientMe && RealtimeManager.isUserViewingConversation(myUid, canonicalConvId)

                    val effectiveStatus = when {
                        isRecipientMe && isViewing -> MessageStatus.READ.name
                        isRecipientMe && statusRank(incomingStatus) < statusRank(MessageStatus.DELIVERED.name) -> MessageStatus.DELIVERED.name
                        else -> incomingStatus
                    }

                    // Preserve higher status if message was already marked DELIVERED or READ
                    val prevStatus = processedMessageStatuses[messageId]
                    val finalStatus = if (prevStatus != null && statusRank(prevStatus) > statusRank(effectiveStatus)) {
                        prevStatus
                    } else {
                        effectiveStatus
                    }
                    processedMessageStatuses[messageId] = finalStatus

                    val message = MessageEntity(
                        id = messageId,
                        conversationId = canonicalConvId,
                        senderId = senderId,
                        recipientId = recipientId,
                        content = content,
                        timestamp = timestamp,
                        status = finalStatus,
                        attachmentUri = attachmentUri,
                        attachmentType = attachmentType,
                        attachmentSize = attachmentSize,
                        attachmentName = attachmentName
                    )
                    messageDao.insertMessage(message)

                    val sortedParticipants = listOf(senderId, recipientId).sorted()
                    val previewText = when {
                        attachmentType != null && content.isNotBlank() -> "📷 $content"
                        attachmentType != null -> "📷 Photo"
                        else -> content
                    }

                    val existingConv = conversationDao.getConversationByIdDirect(canonicalConvId)
                        ?: conversationDao.findConversationBetween(senderId, recipientId)

                    if (existingConv != null) {
                        if (timestamp >= existingConv.lastMessageTimestamp) {
                            val isMeP1 = existingConv.participant1Id == myUid
                            val newUnread1 = if (isRecipientMe && isMeP1 && !isViewing && prevStatus == null) {
                                existingConv.unreadCountForUser1 + 1
                            } else if (isMeP1 && isViewing) {
                                0
                            } else {
                                existingConv.unreadCountForUser1
                            }
                            val newUnread2 = if (isRecipientMe && !isMeP1 && !isViewing && prevStatus == null) {
                                existingConv.unreadCountForUser2 + 1
                            } else if (!isMeP1 && isViewing) {
                                0
                            } else {
                                existingConv.unreadCountForUser2
                            }

                            val updated = existingConv.copy(
                                id = canonicalConvId,
                                participant1Id = sortedParticipants[0],
                                participant2Id = sortedParticipants[1],
                                lastMessageText = previewText,
                                lastMessageTimestamp = timestamp,
                                lastMessageSenderId = senderId,
                                lastMessageStatus = finalStatus,
                                unreadCountForUser1 = newUnread1,
                                unreadCountForUser2 = newUnread2,
                                updatedAt = maxOf(timestamp, existingConv.updatedAt)
                            )
                            conversationDao.insertConversation(updated)
                        }
                    } else {
                        val isMeP1 = sortedParticipants[0] == myUid
                        val unread1 = if (isRecipientMe && isMeP1 && !isViewing) 1 else 0
                        val unread2 = if (isRecipientMe && !isMeP1 && !isViewing) 1 else 0
                        val newConv = ConversationEntity(
                            id = canonicalConvId,
                            participant1Id = sortedParticipants[0],
                            participant2Id = sortedParticipants[1],
                            lastMessageText = previewText,
                            lastMessageTimestamp = timestamp,
                            lastMessageSenderId = senderId,
                            lastMessageStatus = finalStatus,
                            unreadCountForUser1 = unread1,
                            unreadCountForUser2 = unread2,
                            updatedAt = timestamp
                        )
                        conversationDao.insertConversation(newConv)
                    }

                    // If this message was sent to me on this device, publish real DELIVERED / READ status back to sender
                    if (isRecipientMe && statusRank(finalStatus) > statusRank(incomingStatus)) {
                        broadcastMessageStatus(
                            conversationId = canonicalConvId,
                            messageId = messageId,
                            receiverId = myUid,
                            senderId = senderId,
                            status = finalStatus
                        )
                    }
                }

                "MESSAGE_STATUS", "READ_RECEIPT" -> {
                    val convId = json.optString("conversationId", "")
                    val messageId = json.optString("messageId", "")
                    val status = json.optString(
                        "status",
                        if (json.optString("action") == "READ_RECEIPT") MessageStatus.READ.name else MessageStatus.DELIVERED.name
                    )
                    val actorId = json.optString("receiverId", json.optString("readerId", ""))
                    if (actorId.isNotBlank() && actorId != myUid) {
                        if (messageId.isNotBlank()) {
                            val prev = processedMessageStatuses[messageId]
                            if (prev == null || statusRank(status) >= statusRank(prev)) {
                                processedMessageStatuses[messageId] = status
                                messageDao.updateSingleMessageStatus(messageId, status)
                            }
                        } else if (convId.isNotBlank() && status == MessageStatus.READ.name) {
                            messageDao.markAllInConversationAsRead(convId, MessageStatus.READ.name)
                        }
                        if (convId.isNotBlank()) {
                            conversationDao.updateLastMessageStatus(convId, status)
                        }
                    }
                }

                "TYPING_INDICATOR" -> {
                    val convId = json.optString("conversationId", "")
                    val userId = json.optString("userId", "")
                    val isTyping = json.optBoolean("isTyping", false)
                    val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                    val isRecent = (System.currentTimeMillis() - updatedAt) < 10_000L

                    if (convId.isNotBlank() && userId.isNotBlank() && userId != myUid) {
                        if (isTyping && isRecent) {
                            RealtimeManager.onUserTyping(convId, userId)
                        } else {
                            RealtimeManager.stopUserTyping(convId, userId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle messaging payload: ${e.message}")
        }
    }

    private suspend fun fetchTopicPayloadsFromCloud(topic: String): List<String> = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) {
            throw IOException("Unable to search because of a network/backend error.")
        }
        val request = Request.Builder()
            .url("https://ntfy.sh/$topic/json?poll=1&since=all")
            .build()

        queryClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP_${response.code}: Backend returned HTTP ${response.code}")
            }
            _isConnected.value = true
            val bodyStr = response.body?.string() ?: return@use emptyList()
            val payloads = mutableListOf<String>()
            bodyStr.lineSequence().forEach { rawLine ->
                val lineStr = rawLine.trim()
                if (lineStr.isNotBlank()) {
                    try {
                        val wrapper = JSONObject(lineStr)
                        if (wrapper.optString("event") == "message") {
                            val msg = wrapper.optString("message", "")
                            if (msg.isNotBlank()) {
                                payloads.add(msg)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            payloads
        }
    }

    private suspend fun applyUserPayloadsToCache(payloads: List<String>) {
        val discoveredById = linkedMapOf<String, UserEntity>()
        val presenceById = mutableMapOf<String, Pair<Boolean, Long>>()

        for (payload in payloads) {
            try {
                val json = JSONObject(payload)
                when (json.optString("action")) {
                    "USER_REGISTERED", "USER_UPDATED" -> {
                        val parsed = parseUserFromJsonObject(json.getJSONObject("user"))
                        if (parsed != null) {
                            discoveredById[parsed.id] = parsed
                        }
                    }
                    "PRESENCE_UPDATE" -> {
                        val uid = json.optString("uid", json.optString("userId", ""))
                        if (uid.isNotBlank()) {
                            presenceById[uid] = json.optBoolean("online", json.optBoolean("isOnline", false)) to
                                json.optLong("lastSeen", json.optLong("lastSeenTimestamp", System.currentTimeMillis()))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val now = System.currentTimeMillis()
        for ((uid, user) in discoveredById) {
            val presence = presenceById[uid]
            val finalUser = if (presence != null && presence.second >= user.lastSeenTimestamp) {
                val effectiveOnline = presence.first && (now - presence.second) < 300_000L
                user.copy(isOnline = effectiveOnline, lastSeenTimestamp = presence.second)
            } else {
                val effectiveOnline = user.isOnline && (now - user.lastSeenTimestamp) < 300_000L
                user.copy(isOnline = effectiveOnline)
            }
            cloudUsersCache[uid] = finalUser
            userDao.upsertRemoteUser(finalUser)
        }
    }

    suspend fun syncUserInboxFromCloud(userId: String) = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value || userId.isBlank()) return@withContext
        try {
            val payloads = fetchTopicPayloadsFromCloud("$baseTopic-user-$userId")
            for (payload in payloads) {
                dispatchIncomingPayload(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Inbox sync warning for $userId: ${e.message}")
        }
    }

    suspend fun syncConversationHistoryFromCloud(conversationId: String) = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value || conversationId.isBlank()) return@withContext
        try {
            val payloads = fetchTopicPayloadsFromCloud("$baseTopic-conv-$conversationId")
            for (payload in payloads) {
                dispatchIncomingPayload(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Conversation history sync warning for $conversationId: ${e.message}")
        }
    }

    private suspend fun syncUsersFromCloud(targetNormUsername: String = ""): Result<Collection<UserEntity>> = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Unable to search because of a network/backend error."))
        }

        cloudSyncMutex.withLock {
            val now = System.currentTimeMillis()
            val hasTargetInCache = targetNormUsername.isNotBlank() &&
                cloudUsersCache.values.any { it.usernameNormalized == targetNormUsername }

            if ((now - lastCloudSyncTimestamp) < 1200L && (targetNormUsername.isBlank() || hasTargetInCache) && cloudUsersCache.isNotEmpty()) {
                return@withLock Result.success(cloudUsersCache.values.toList())
            }

            val canQuerySpecificUsername = targetNormUsername.isNotBlank() &&
                targetNormUsername.matches(Regex("^[a-z0-9_.]+$"))

            try {
                val globalPayloads = fetchTopicPayloadsFromCloud("$baseTopic-users")
                applyUserPayloadsToCache(globalPayloads)
                lastCloudSyncTimestamp = System.currentTimeMillis()

                if (canQuerySpecificUsername &&
                    cloudUsersCache.values.none { it.usernameNormalized == targetNormUsername }
                ) {
                    try {
                        val specificPayloads = fetchTopicPayloadsFromCloud("$baseTopic-uname-$targetNormUsername")
                        applyUserPayloadsToCache(specificPayloads)
                    } catch (_: Exception) {
                    }
                }

                Result.success(cloudUsersCache.values.toList())
            } catch (e: Exception) {
                if (canQuerySpecificUsername) {
                    try {
                        val specificPayloads = fetchTopicPayloadsFromCloud("$baseTopic-uname-$targetNormUsername")
                        applyUserPayloadsToCache(specificPayloads)
                        if (cloudUsersCache.values.any { it.usernameNormalized == targetNormUsername }) {
                            return@withLock Result.success(cloudUsersCache.values.toList())
                        }
                    } catch (_: Exception) {
                    }
                }
                val msg = e.message ?: ""
                if (msg.contains("HTTP_429") && RealtimeManager.isNetworkConnected.value && (_isConnected.value || cloudUsersCache.isNotEmpty())) {
                    Log.w(tag, "Cloud poll rate-limited (429); serving live multiplexed stream cache")
                    return@withLock Result.success(cloudUsersCache.values.toList())
                }
                Log.e(tag, "Cloud sync error: ${e.message}")
                Result.failure(IOException("Unable to search because of a network/backend error.", e))
            }
        }
    }

    suspend fun checkUsernameExistsInCloud(rawUsername: String): Result<UserEntity?> = withContext(Dispatchers.IO) {
        val norm = normalizeUsername(rawUsername)
        if (norm.isBlank()) return@withContext Result.success(null)

        val syncResult = syncUsersFromCloud(targetNormUsername = norm)
        if (syncResult.isFailure) {
            return@withContext Result.failure(
                syncResult.exceptionOrNull() ?: IOException("Unable to search because of a network/backend error.")
            )
        }

        val matched = cloudUsersCache.values.firstOrNull { it.usernameNormalized == norm }
        Result.success(matched)
    }

    /**
     * Authenticates a user against the shared cloud backend and returns their permanent backend UserEntity.
     */
    suspend fun authenticateUserInCloud(rawUsername: String, expectedAuthVerifier: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val norm = normalizeUsername(rawUsername)
        if (norm.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid username"))
        }
        val checkResult = checkUsernameExistsInCloud(norm)
        if (checkResult.isFailure) {
            return@withContext Result.failure(
                checkResult.exceptionOrNull() ?: IOException("Unable to authenticate because of a network/backend error.")
            )
        }
        val cloudUser = checkResult.getOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("No registered account found for @$norm"))

        val storedVerifier = cloudAuthVerifiers[norm]
        if (!storedVerifier.isNullOrBlank() && storedVerifier != expectedAuthVerifier) {
            return@withContext Result.failure(IllegalArgumentException("Incorrect password for @$norm"))
        }

        Result.success(cloudUser)
    }

    /**
     * Queries the shared cloud `users` collection directly (never local device sessions).
     */
    suspend fun searchUsersInCloud(rawQuery: String, excludeUserId: String): Result<List<UserEntity>> = withContext(Dispatchers.IO) {
        val normQuery = normalizeUsername(rawQuery)
        val syncResult = syncUsersFromCloud(targetNormUsername = normQuery)
        if (syncResult.isFailure) {
            return@withContext Result.failure(
                syncResult.exceptionOrNull() ?: IOException("Unable to search because of a network/backend error.")
            )
        }

        val deduplicatedByNorm = linkedMapOf<String, UserEntity>()
        for (u in cloudUsersCache.values) {
            if (u.id != excludeUserId) {
                val norm = normalizeUsername(u.usernameNormalized.ifBlank { u.username })
                if (norm.isNotBlank()) {
                    deduplicatedByNorm[norm] = u.copy(usernameNormalized = norm, passwordHash = "")
                }
            }
        }

        val matched = deduplicatedByNorm.values.filter { user ->
            if (normQuery.isBlank()) {
                true
            } else {
                user.usernameNormalized.contains(normQuery) ||
                    user.username.lowercase().contains(normQuery) ||
                    user.displayName.lowercase().contains(normQuery)
            }
        }.sortedWith(
            compareBy<UserEntity> {
                when {
                    it.usernameNormalized == normQuery -> 0
                    it.usernameNormalized.startsWith(normQuery) -> 1
                    else -> 2
                }
            }.thenByDescending { it.isOnline }.thenBy { it.displayName.lowercase() }
        )

        Result.success(matched)
    }

    private suspend fun postPayloadDirect(topic: String, jsonObject: JSONObject): Boolean = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) return@withContext false
        try {
            val body = jsonObject.toString().toRequestBody(textMediaType)
            val request = Request.Builder()
                .url("https://ntfy.sh/$topic")
                .header("Cache", "yes")
                .post(body)
                .build()

            queryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "Failed to publish to $topic: ${response.code}")
                } else {
                    _isConnected.value = true
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(tag, "Error posting to $topic: ${e.message}")
            false
        }
    }

    private fun postPayload(topic: String, jsonObject: JSONObject) {
        scope.launch {
            postPayloadDirect(topic, jsonObject)
        }
    }

    private fun buildUserJsonObject(user: UserEntity, authVerifier: String? = null): JSONObject {
        val cleanUser = cleanDisplayUsername(user.username)
        val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
        return JSONObject().apply {
            put("uid", user.id)
            put("id", user.id)
            put("username", cleanUser)
            put("usernameNormalized", normUser)
            put("displayName", user.displayName)
            put("photoURL", user.avatarSeed)
            put("avatarSeed", user.avatarSeed)
            put("statusMessage", user.statusMessage)
            put("online", user.isOnline)
            put("isOnline", user.isOnline)
            put("lastSeen", user.lastSeenTimestamp)
            put("lastSeenTimestamp", user.lastSeenTimestamp)
            put("createdAt", user.createdAt)
            if (!authVerifier.isNullOrBlank()) {
                put("authVerifier", authVerifier)
            }
        }
    }

    private fun buildUserPayload(user: UserEntity, isNew: Boolean, authVerifier: String? = null): JSONObject {
        return JSONObject().apply {
            put("action", if (isNew) "USER_REGISTERED" else "USER_UPDATED")
            put("user", buildUserJsonObject(user, authVerifier))
        }
    }

    suspend fun publishUserSync(user: UserEntity, isNew: Boolean = false, authVerifier: String? = null): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = cleanDisplayUsername(user.username)
        val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
        val sanitized = user.copy(
            username = cleanUser,
            usernameNormalized = normUser,
            passwordHash = ""
        )
        cloudUsersCache[user.id] = sanitized
        val verifierToStore = authVerifier ?: cloudAuthVerifiers[normUser]
        if (!verifierToStore.isNullOrBlank()) {
            cloudAuthVerifiers[normUser] = verifierToStore
        }

        val payload = buildUserPayload(sanitized, isNew, verifierToStore)
        coroutineScope {
            val globalDef = async { postPayloadDirect("$baseTopic-users", payload) }
            val unameDef = async {
                if (normUser.isNotBlank()) {
                    postPayloadDirect("$baseTopic-uname-$normUser", payload)
                } else {
                    false
                }
            }
            val globalOk = globalDef.await()
            val unameOk = unameDef.await()
            globalOk || unameOk
        }
    }

    fun broadcastUser(user: UserEntity, isNew: Boolean = false, authVerifier: String? = null) {
        val cleanUser = cleanDisplayUsername(user.username)
        val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
        val sanitized = user.copy(
            username = cleanUser,
            usernameNormalized = normUser,
            passwordHash = ""
        )
        cloudUsersCache[user.id] = sanitized
        val verifierToStore = authVerifier ?: cloudAuthVerifiers[normUser]
        if (!verifierToStore.isNullOrBlank()) {
            cloudAuthVerifiers[normUser] = verifierToStore
        }

        val payload = buildUserPayload(sanitized, isNew, verifierToStore)
        postPayload("$baseTopic-users", payload)
        if (normUser.isNotBlank()) {
            postPayload("$baseTopic-uname-$normUser", payload)
        }
    }

    fun broadcastPresence(userId: String, isOnline: Boolean, lastSeenTimestamp: Long) {
        if (userId.isBlank()) return
        cloudUsersCache[userId]?.let { existing ->
            cloudUsersCache[userId] = existing.copy(isOnline = isOnline, lastSeenTimestamp = lastSeenTimestamp)
        }
        val payload = JSONObject().apply {
            put("action", "PRESENCE_UPDATE")
            put("uid", userId)
            put("userId", userId)
            put("online", isOnline)
            put("isOnline", isOnline)
            put("lastSeen", lastSeenTimestamp)
            put("lastSeenTimestamp", lastSeenTimestamp)
        }
        postPayload("$baseTopic-users", payload)
    }

    fun syncConversationToCloud(
        conversation: ConversationEntity,
        participant1Profile: UserEntity?,
        participant2Profile: UserEntity?
    ) {
        val convObj = JSONObject().apply {
            put("conversationId", conversation.id)
            put("participant1Id", conversation.participant1Id)
            put("participant2Id", conversation.participant2Id)
            put("lastMessage", conversation.lastMessageText)
            put("lastMessageAt", conversation.lastMessageTimestamp)
            put("lastMessageSenderId", conversation.lastMessageSenderId)
            put("lastMessageStatus", conversation.lastMessageStatus)
            put("updatedAt", conversation.updatedAt)
        }
        val payload = JSONObject().apply {
            put("action", "CONVERSATION_SYNC")
            put("conversation", convObj)
            if (participant1Profile != null) {
                put("participant1Profile", buildUserJsonObject(participant1Profile))
            }
            if (participant2Profile != null) {
                put("participant2Profile", buildUserJsonObject(participant2Profile))
            }
        }
        postPayload("$baseTopic-user-${conversation.participant1Id}", payload)
        postPayload("$baseTopic-user-${conversation.participant2Id}", payload)
        postPayload("$baseTopic-conv-${conversation.id}", payload)
    }

    suspend fun publishMessageToCloud(
        message: MessageEntity,
        senderProfile: UserEntity?,
        receiverProfile: UserEntity?
    ): Boolean = withContext(Dispatchers.IO) {
        // Security: enforce that senderId matches the authenticated backend session UID
        val authenticatedUid = currentUserId
        if (authenticatedUid == null || message.senderId != authenticatedUid) {
            Log.e(tag, "Security violation: attempted to send message with unauthenticated senderId=${message.senderId}")
            return@withContext false
        }

        processedMessageStatuses[message.id] = message.status

        val msgObj = JSONObject().apply {
            put("messageId", message.id)
            put("id", message.id)
            put("conversationId", message.conversationId)
            put("senderId", message.senderId)
            put("receiverId", message.recipientId)
            put("recipientId", message.recipientId)
            put("text", message.content)
            put("content", message.content)
            put("createdAt", message.timestamp)
            put("timestamp", message.timestamp)
            put("status", message.status)
            put("attachmentUri", message.attachmentUri ?: JSONObject.NULL)
            put("attachmentType", message.attachmentType ?: JSONObject.NULL)
            put("attachmentName", message.attachmentName ?: JSONObject.NULL)
            put("attachmentSize", message.attachmentSize ?: JSONObject.NULL)
        }
        val payload = JSONObject().apply {
            put("action", "NEW_MESSAGE")
            put("message", msgObj)
            if (senderProfile != null) {
                put("senderProfile", buildUserJsonObject(senderProfile))
            }
            if (receiverProfile != null) {
                put("receiverProfile", buildUserJsonObject(receiverProfile))
            }
        }

        coroutineScope {
            val recipientDef = async { postPayloadDirect("$baseTopic-user-${message.recipientId}", payload) }
            val convDef = async { postPayloadDirect("$baseTopic-conv-${message.conversationId}", payload) }
            val senderDef = async { postPayloadDirect("$baseTopic-user-${message.senderId}", payload) }
            val deliveredToRecipient = recipientDef.await()
            val storedInConv = convDef.await()
            senderDef.await()
            deliveredToRecipient || storedInConv
        }
    }

    fun broadcastMessageStatus(
        conversationId: String,
        messageId: String,
        receiverId: String,
        senderId: String,
        status: String
    ) {
        processedMessageStatuses[messageId] = status
        val payload = JSONObject().apply {
            put("action", "MESSAGE_STATUS")
            put("conversationId", conversationId)
            put("messageId", messageId)
            put("receiverId", receiverId)
            put("senderId", senderId)
            put("status", status)
            put("updatedAt", System.currentTimeMillis())
        }
        postPayload("$baseTopic-user-$senderId", payload)
        postPayload("$baseTopic-conv-$conversationId", payload)
    }

    fun broadcastReadReceipt(conversationId: String, messageId: String?, readerId: String, senderId: String) {
        if (!messageId.isNullOrBlank()) {
            processedMessageStatuses[messageId] = MessageStatus.READ.name
        }
        val payload = JSONObject().apply {
            put("action", "READ_RECEIPT")
            put("conversationId", conversationId)
            put("messageId", messageId ?: "")
            put("readerId", readerId)
            put("receiverId", readerId)
            put("status", MessageStatus.READ.name)
            put("updatedAt", System.currentTimeMillis())
        }
        postPayload("$baseTopic-user-$senderId", payload)
        postPayload("$baseTopic-conv-$conversationId", payload)
    }

    fun broadcastTyping(conversationId: String, userId: String, recipientId: String?, isTyping: Boolean) {
        val payload = JSONObject().apply {
            put("action", "TYPING_INDICATOR")
            put("conversationId", conversationId)
            put("userId", userId)
            put("isTyping", isTyping)
            put("updatedAt", System.currentTimeMillis())
        }
        if (!recipientId.isNullOrBlank()) {
            postPayload("$baseTopic-user-$recipientId", payload)
        } else {
            postPayload("$baseTopic-conv-$conversationId", payload)
        }
    }

    fun stop() {
        currentUserId = null
        activeConversationId = null
        periodicSyncJob?.cancel()
        restartMultiplexedStream()
    }
}
