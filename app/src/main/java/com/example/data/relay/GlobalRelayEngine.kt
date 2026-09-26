package com.example.data.relay

import android.util.Log
import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserConversationStateEntity
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
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
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
    // Tracks highest status already broadcasted back to sender for each incoming messageId (prevents amplification loops)
    private val broadcastedRecipientStatuses = ConcurrentHashMap<String, String>()
    // Tracks last event unix timestamp (seconds) per polled topic for incremental polling
    private val topicLastPollTimestamp = ConcurrentHashMap<String, Long>()
    @Volatile
    private var streamLastEventTime: Long = 0L

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
        if (changed) {
            streamLastEventTime = 0L
        }
        if (changed || multiplexedStreamJob?.isActive != true) {
            restartMultiplexedStream()
        }

        // Start background sync loop + immediate history pull for this authenticated backend UID
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            // Immediate initial sync of user's cloud message/conversation inbox
            try {
                syncUserInboxFromCloud(userId, forceFullHistory = true)
            } catch (_: Exception) {
            }

            while (isActive) {
                delay(12_000L)
                if (RealtimeManager.isNetworkConnected.value) {
                    try {
                        val activeUid = currentUserId ?: break
                        syncUserInboxFromCloud(activeUid, forceFullHistory = false)
                        activeConversationId?.let { convId ->
                            syncConversationHistoryFromCloud(convId, forceFullHistory = false)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun subscribeToConversation(conversationId: String) {
        val isNewConv = activeConversationId != conversationId
        activeConversationId = conversationId
        scope.launch {
            if (RealtimeManager.isNetworkConnected.value) {
                syncConversationHistoryFromCloud(conversationId, forceFullHistory = isNewConv)
            }
        }
    }

    fun unsubscribeFromConversation(conversationId: String) {
        if (activeConversationId == conversationId) {
            activeConversationId = null
        }
    }

    private suspend fun listenToMultiplexedStream(topicsCsv: String) {
        while (scope.isActive) {
            var retryDelayMs = 3000L
            if (!RealtimeManager.isNetworkConnected.value) {
                _isConnected.value = false
                delay(2000L)
                continue
            }
            try {
                val sinceParam = if (streamLastEventTime > 0L) {
                    "${maxOf(0L, streamLastEventTime - 3L)}"
                } else {
                    "all"
                }
                val url = "https://ntfy.sh/$topicsCsv/json?since=$sinceParam"
                val request = Request.Builder()
                    .url(url)
                    .build()

                streamClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _isConnected.value = true
                        val inputStream = response.body?.byteStream() ?: return@use
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        var line: String?

                        while (reader.readLine().also { line = it } != null) {
                            if (!RealtimeManager.isNetworkConnected.value) break
                            val lineStr = line?.trim() ?: continue
                            if (lineStr.isBlank()) continue

                            try {
                                val wrapper = JSONObject(lineStr)
                                val evTime = wrapper.optLong("time", 0L)
                                if (evTime > streamLastEventTime) {
                                    streamLastEventTime = evTime
                                }
                                val event = wrapper.optString("event", "")
                                if (event == "message") {
                                    val messageContent = extractPayloadFromWrapper(wrapper)
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
                            retryDelayMs = 6000L
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

    private fun extractPayloadFromWrapper(wrapper: JSONObject): String {
        val msg = wrapper.optString("message", "").trim()
        if (msg.startsWith("{") && msg.endsWith("}")) {
            return msg
        }
        val attachmentObj = wrapper.optJSONObject("attachment")
        val attachmentUrl = attachmentObj?.optString("url", "")?.trim() ?: ""
        val attachmentMime = attachmentObj?.optString("type", "")?.lowercase() ?: ""
        if (attachmentUrl.startsWith("http") && !attachmentMime.startsWith("image/")) {
            return try {
                val req = Request.Builder().url(attachmentUrl).build()
                queryClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.string()?.trim() ?: ""
                    } else {
                        ""
                    }
                }
            } catch (_: Exception) {
                ""
            }
        }
        return ""
    }

    private suspend fun dispatchIncomingPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            when (json.optString("action")) {
                "USER_REGISTERED", "USER_UPDATED", "PRESENCE_UPDATE" -> {
                    handleIncomingUserPayload(json)
                }
                "NEW_MESSAGE", "MESSAGE_STATUS", "READ_RECEIPT", "TYPING_INDICATOR", "CONVERSATION_SYNC", "USER_CONVERSATION_STATE" -> {
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

        val createdAt = userObj.optLong("createdAt", 0L).let { if (it > 0L) it else System.currentTimeMillis() }
        val lastSeen = userObj.optLong(
            "lastSeen",
            userObj.optLong("lastSeenTimestamp", createdAt)
        )
        val rawOnline = userObj.optBoolean("online", userObj.optBoolean("isOnline", false))
        // Do not mark a remote account online if its presence timestamp is stale (> 90 seconds)
        val isFreshOnline = rawOnline && lastSeen > 0L && (System.currentTimeMillis() - lastSeen) < 90_000L

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
            createdAt = createdAt
        )
    }

    private fun mergeUserPreservingNewestPresence(existing: UserEntity?, incoming: UserEntity): UserEntity {
        if (existing == null) return incoming
        return if (existing.lastSeenTimestamp > incoming.lastSeenTimestamp) {
            val freshOnline = existing.isOnline && (System.currentTimeMillis() - existing.lastSeenTimestamp) < 90_000L
            incoming.copy(
                displayName = existing.displayName.ifBlank { incoming.displayName },
                avatarSeed = existing.avatarSeed.ifBlank { incoming.avatarSeed },
                statusMessage = existing.statusMessage.ifBlank { incoming.statusMessage },
                isOnline = freshOnline,
                lastSeenTimestamp = existing.lastSeenTimestamp
            )
        } else {
            val freshOnline = incoming.isOnline && (System.currentTimeMillis() - incoming.lastSeenTimestamp) < 90_000L
            incoming.copy(isOnline = freshOnline)
        }
    }

    private suspend fun handleIncomingUserPayload(json: JSONObject): UserEntity? {
        return try {
            when (json.optString("action")) {
                "USER_REGISTERED", "USER_UPDATED" -> {
                    val userObj = json.getJSONObject("user")
                    val parsed = parseUserFromJsonObject(userObj) ?: return null
                    val merged = mergeUserPreservingNewestPresence(cloudUsersCache[parsed.id], parsed)
                    cloudUsersCache[merged.id] = merged
                    if (userDao.getUserByIdDirect(merged.id) != null) {
                        userDao.upsertRemoteUser(merged, allowPresenceUpdate = true)
                    }
                    merged
                }

                "PRESENCE_UPDATE" -> {
                    val userId = json.optString("uid", json.optString("userId", ""))
                    if (userId.isBlank() || userId == currentUserId) return null
                    val isOnline = json.optBoolean("online", json.optBoolean("isOnline", false))
                    val timestamp = json.optLong("lastSeen", json.optLong("lastSeenTimestamp", 0L))
                    if (timestamp <= 0L) return null
                    val effectiveOnline = isOnline && (System.currentTimeMillis() - timestamp) < 90_000L
                    cloudUsersCache[userId]?.let { existing ->
                        if (timestamp >= existing.lastSeenTimestamp) {
                            cloudUsersCache[userId] = existing.copy(
                                isOnline = effectiveOnline,
                                lastSeenTimestamp = timestamp
                            )
                        }
                    }
                    userDao.updateRemotePresenceIfNewer(userId, effectiveOnline, timestamp)
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
        MessageStatus.FAILED.name -> -1
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
                "USER_CONVERSATION_STATE" -> {
                    val stateObj = json.optJSONObject("state") ?: json
                    val stateUserId = stateObj.optString("userId", "").trim()
                    val convId = stateObj.optString("conversationId", "").trim()
                    // Security rule: a user can only modify/sync their own conversation state
                    if (stateUserId.isBlank() || convId.isBlank() || stateUserId != myUid) return

                    val otherUserId = stateObj.optString("otherUserId", "").trim()
                    val hidden = stateObj.optBoolean("hidden", false)
                    val deletedAt = stateObj.optLong("deletedAt", 0L)
                    val lastMessage = stateObj.optString("lastMessage", "")
                    val lastMessageAt = stateObj.optLong("lastMessageAt", 0L)
                    val updatedAt = stateObj.optLong("updatedAt", deletedAt)

                    val existingState = conversationDao.getUserConversationStateDirect(myUid, convId)
                    if (existingState == null || updatedAt >= existingState.updatedAt || deletedAt >= existingState.deletedAt) {
                        conversationDao.upsertUserConversationState(
                            UserConversationStateEntity(
                                userId = myUid,
                                conversationId = convId,
                                otherUserId = otherUserId.ifBlank { existingState?.otherUserId ?: "" },
                                hidden = hidden,
                                deletedAt = maxOf(deletedAt, existingState?.deletedAt ?: 0L),
                                lastMessage = lastMessage,
                                lastMessageAt = lastMessageAt,
                                updatedAt = maxOf(updatedAt, existingState?.updatedAt ?: 0L)
                            )
                        )
                    }
                }

                "CONVERSATION_SYNC" -> {
                    val convObj = json.optJSONObject("conversation") ?: return
                    val p1 = convObj.optString("participant1Id", "")
                    val p2 = convObj.optString("participant2Id", "")
                    if (p1.isBlank() || p2.isBlank()) return
                    // Security rule: only process conversations that the authenticated user participates in
                    if (p1 != myUid && p2 != myUid) return

                    // Upsert participant profiles WITHOUT overwriting their newer live presence/lastSeen
                    json.optJSONObject("participant1Profile")?.let { parseUserFromJsonObject(it) }?.let {
                        val merged = mergeUserPreservingNewestPresence(cloudUsersCache[it.id], it)
                        cloudUsersCache[merged.id] = merged
                        userDao.upsertRemoteUser(merged, allowPresenceUpdate = false)
                    }
                    json.optJSONObject("participant2Profile")?.let { parseUserFromJsonObject(it) }?.let {
                        val merged = mergeUserPreservingNewestPresence(cloudUsersCache[it.id], it)
                        cloudUsersCache[merged.id] = merged
                        userDao.upsertRemoteUser(merged, allowPresenceUpdate = false)
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

                    // Upsert embedded sender/receiver profiles WITHOUT overwriting live presence/lastSeen timestamps
                    json.optJSONObject("senderProfile")?.let { parseUserFromJsonObject(it) }?.let { senderUser ->
                        if (senderUser.id == senderId) {
                            val merged = mergeUserPreservingNewestPresence(cloudUsersCache[senderUser.id], senderUser)
                            cloudUsersCache[merged.id] = merged
                            userDao.upsertRemoteUser(merged, allowPresenceUpdate = false)
                        }
                    }
                    json.optJSONObject("receiverProfile")?.let { parseUserFromJsonObject(it) }?.let { receiverUser ->
                        if (receiverUser.id == recipientId) {
                            val merged = mergeUserPreservingNewestPresence(cloudUsersCache[receiverUser.id], receiverUser)
                            cloudUsersCache[merged.id] = merged
                            userDao.upsertRemoteUser(merged, allowPresenceUpdate = false)
                        }
                    }

                    val otherPeerId = if (senderId == myUid) recipientId else senderId
                    if (userDao.getUserByIdDirect(otherPeerId) == null) {
                        cloudUsersCache[otherPeerId]?.let { userDao.upsertRemoteUser(it, allowPresenceUpdate = false) }
                    }

                    val messageId = msgObj.optString("messageId", msgObj.optString("id", "")).trim()
                    if (messageId.isBlank()) return

                    val canonicalConvId = buildDeterministicConversationId(senderId, recipientId)
                    val content = msgObj.optString("text", msgObj.optString("content", ""))
                    val timestamp = msgObj.optLong("createdAt", msgObj.optLong("timestamp", System.currentTimeMillis()))
                    val incomingStatus = msgObj.optString("status", MessageStatus.SENT.name)

                    // Record sender's real activity timestamp if this message is from the other user
                    if (senderId != myUid && timestamp > 0L) {
                        userDao.recordPeerActivity(senderId, timestamp)
                        cloudUsersCache[senderId]?.let { existing ->
                            if (timestamp >= existing.lastSeenTimestamp) {
                                val freshOnline = (System.currentTimeMillis() - timestamp) < 90_000L
                                cloudUsersCache[senderId] = existing.copy(
                                    isOnline = if (freshOnline) true else existing.isOnline,
                                    lastSeenTimestamp = timestamp
                                )
                            }
                        }
                    }

                    val mediaUrl = if (msgObj.has("mediaUrl") && !msgObj.isNull("mediaUrl")) {
                        msgObj.optString("mediaUrl", "").ifBlank { null }
                    } else null
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

                    val rawType = msgObj.optString("type", "")
                    val isImage = rawType.equals("image", ignoreCase = true) ||
                        !mediaUrl.isNullOrBlank() ||
                        !attachmentUri.isNullOrBlank() ||
                        attachmentType != null
                    val resolvedType = if (isImage) "image" else "text"
                    val resolvedMediaUrl = mediaUrl ?: attachmentUri
                    val resolvedAttachmentUri = attachmentUri ?: mediaUrl

                    val existingLocalMsg = messageDao.getMessageByIdDirect(messageId)
                    val isRecipientMe = recipientId == myUid
                    val isViewing = isRecipientMe && RealtimeManager.isUserViewingConversation(myUid, canonicalConvId)

                    val effectiveStatus = when {
                        isRecipientMe && isViewing -> MessageStatus.READ.name
                        isRecipientMe && statusRank(incomingStatus) < statusRank(MessageStatus.DELIVERED.name) -> MessageStatus.DELIVERED.name
                        else -> incomingStatus
                    }

                    val prevStatus = processedMessageStatuses[messageId] ?: existingLocalMsg?.status
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
                        type = resolvedType,
                        mediaUrl = resolvedMediaUrl,
                        attachmentUri = resolvedAttachmentUri,
                        attachmentType = attachmentType ?: if (isImage) "IMAGE" else null,
                        attachmentSize = attachmentSize,
                        attachmentName = attachmentName
                    )
                    messageDao.upsertMessageSafely(message)

                    val sortedParticipants = listOf(senderId, recipientId).sorted()
                    val previewText = when {
                        isImage && content.isNotBlank() -> "📷 $content"
                        isImage -> "📷 Photo"
                        else -> content
                    }

                    val existingConv = conversationDao.getConversationByIdDirect(canonicalConvId)
                        ?: conversationDao.findConversationBetween(senderId, recipientId)

                    val isBrandNewMessage = existingLocalMsg == null
                    if (existingConv != null) {
                        if (timestamp >= existingConv.lastMessageTimestamp) {
                            val isMeP1 = existingConv.participant1Id == myUid
                            val newUnread1 = if (isRecipientMe && isMeP1 && !isViewing && isBrandNewMessage) {
                                existingConv.unreadCountForUser1 + 1
                            } else if (isMeP1 && isViewing) {
                                0
                            } else {
                                existingConv.unreadCountForUser1
                            }
                            val newUnread2 = if (isRecipientMe && !isMeP1 && !isViewing && isBrandNewMessage) {
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

                    // If this message is newer than the current user's deletedAt timestamp, unhide the conversation for currentUser
                    val existingUserState = conversationDao.getUserConversationStateDirect(myUid, canonicalConvId)
                    if (existingUserState != null && timestamp > existingUserState.deletedAt) {
                        conversationDao.upsertUserConversationState(
                            existingUserState.copy(
                                hidden = false,
                                lastMessage = previewText,
                                lastMessageAt = timestamp,
                                updatedAt = maxOf(timestamp, existingUserState.updatedAt)
                            )
                        )
                    }

                    // Publish DELIVERED / READ status back to sender ONLY ONCE per upward status transition
                    if (isRecipientMe && statusRank(finalStatus) > statusRank(incomingStatus)) {
                        val alreadyBroadcasted = broadcastedRecipientStatuses[messageId]
                        val alreadyInDbAtThisStatus = existingLocalMsg != null &&
                            statusRank(existingLocalMsg.status) >= statusRank(finalStatus)
                        if (!alreadyInDbAtThisStatus &&
                            (alreadyBroadcasted == null || statusRank(finalStatus) > statusRank(alreadyBroadcasted))
                        ) {
                            broadcastedRecipientStatuses[messageId] = finalStatus
                            broadcastMessageStatus(
                                conversationId = canonicalConvId,
                                messageId = messageId,
                                receiverId = myUid,
                                senderId = senderId,
                                status = finalStatus
                            )
                        }
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
                    val updatedAt = json.optLong("updatedAt", 0L)
                    if (actorId.isNotBlank() && actorId != myUid) {
                        if (updatedAt > 0L) {
                            userDao.recordPeerActivity(actorId, updatedAt)
                        }
                        if (messageId.isNotBlank()) {
                            val prev = processedMessageStatuses[messageId]
                            if (prev == null || statusRank(status) >= statusRank(prev)) {
                                processedMessageStatuses[messageId] = status
                                messageDao.advanceMessageStatus(messageId, status)
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
                        if (isRecent && updatedAt > 0L) {
                            userDao.recordPeerActivity(userId, updatedAt)
                        }
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

    private suspend fun fetchTopicPayloadsFromCloud(
        topic: String,
        forceFullHistory: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) {
            throw IOException("Unable to search because of a network/backend error.")
        }
        val lastPollSec = topicLastPollTimestamp[topic]
        val sinceParam = if (forceFullHistory || lastPollSec == null || lastPollSec <= 0L) {
            "all"
        } else {
            "${maxOf(0L, lastPollSec - 2L)}"
        }
        val request = Request.Builder()
            .url("https://ntfy.sh/$topic/json?poll=1&since=$sinceParam")
            .build()

        queryClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP_${response.code}: Backend returned HTTP ${response.code}")
            }
            _isConnected.value = true
            val bodyStr = response.body?.string() ?: return@use emptyList()
            val payloads = mutableListOf<String>()
            var maxEventTime = lastPollSec ?: 0L
            bodyStr.lineSequence().forEach { rawLine ->
                val lineStr = rawLine.trim()
                if (lineStr.isNotBlank()) {
                    try {
                        val wrapper = JSONObject(lineStr)
                        val evTime = wrapper.optLong("time", 0L)
                        if (evTime > maxEventTime) {
                            maxEventTime = evTime
                        }
                        if (wrapper.optString("event") == "message") {
                            val msg = extractPayloadFromWrapper(wrapper)
                            if (msg.isNotBlank()) {
                                payloads.add(msg)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (maxEventTime > 0L) {
                topicLastPollTimestamp[topic] = maxEventTime
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
                            val prev = discoveredById[parsed.id]
                            discoveredById[parsed.id] = mergeUserPreservingNewestPresence(prev, parsed)
                        }
                    }
                    "PRESENCE_UPDATE" -> {
                        val uid = json.optString("uid", json.optString("userId", ""))
                        val ts = json.optLong("lastSeen", json.optLong("lastSeenTimestamp", 0L))
                        if (uid.isNotBlank() && ts > 0L) {
                            val prevPresence = presenceById[uid]
                            if (prevPresence == null || ts >= prevPresence.second) {
                                presenceById[uid] = json.optBoolean("online", json.optBoolean("isOnline", false)) to ts
                            }
                        }
                    }
                    "NEW_MESSAGE" -> {
                        json.optJSONObject("senderProfile")?.let { parseUserFromJsonObject(it) }?.let { parsed ->
                            val prev = discoveredById[parsed.id]
                            discoveredById[parsed.id] = mergeUserPreservingNewestPresence(prev, parsed)
                        }
                        json.optJSONObject("receiverProfile")?.let { parseUserFromJsonObject(it) }?.let { parsed ->
                            val prev = discoveredById[parsed.id]
                            discoveredById[parsed.id] = mergeUserPreservingNewestPresence(prev, parsed)
                        }
                    }
                    "CONVERSATION_SYNC" -> {
                        json.optJSONObject("participant1Profile")?.let { parseUserFromJsonObject(it) }?.let { parsed ->
                            val prev = discoveredById[parsed.id]
                            discoveredById[parsed.id] = mergeUserPreservingNewestPresence(prev, parsed)
                        }
                        json.optJSONObject("participant2Profile")?.let { parseUserFromJsonObject(it) }?.let { parsed ->
                            val prev = discoveredById[parsed.id]
                            discoveredById[parsed.id] = mergeUserPreservingNewestPresence(prev, parsed)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val now = System.currentTimeMillis()
        for ((uid, user) in discoveredById) {
            val presence = presenceById[uid]
            val candidateUser = if (presence != null && presence.second >= user.lastSeenTimestamp) {
                val effectiveOnline = presence.first && (now - presence.second) < 90_000L
                user.copy(isOnline = effectiveOnline, lastSeenTimestamp = presence.second)
            } else {
                val effectiveOnline = user.isOnline && user.lastSeenTimestamp > 0L && (now - user.lastSeenTimestamp) < 90_000L
                user.copy(isOnline = effectiveOnline)
            }
            val finalUser = mergeUserPreservingNewestPresence(cloudUsersCache[uid], candidateUser)
            cloudUsersCache[uid] = finalUser
            if (userDao.getUserByIdDirect(finalUser.id) != null) {
                userDao.upsertRemoteUser(finalUser, allowPresenceUpdate = true)
            }
        }

        // Also apply standalone PRESENCE_UPDATE events for users already in cache/DB
        for ((uid, presence) in presenceById) {
            if (!discoveredById.containsKey(uid) && uid != currentUserId) {
                val effectiveOnline = presence.first && (now - presence.second) < 90_000L
                cloudUsersCache[uid]?.let { existing ->
                    if (presence.second >= existing.lastSeenTimestamp) {
                        cloudUsersCache[uid] = existing.copy(
                            isOnline = effectiveOnline,
                            lastSeenTimestamp = presence.second
                        )
                    }
                }
                userDao.updateRemotePresenceIfNewer(uid, effectiveOnline, presence.second)
            }
        }
    }

    suspend fun syncUserInboxFromCloud(userId: String, forceFullHistory: Boolean = false) = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value || userId.isBlank()) return@withContext
        try {
            val payloads = fetchTopicPayloadsFromCloud("$baseTopic-user-$userId", forceFullHistory = forceFullHistory)
            for (payload in payloads) {
                dispatchIncomingPayload(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Inbox sync warning for $userId: ${e.message}")
        }
    }

    suspend fun syncConversationHistoryFromCloud(conversationId: String, forceFullHistory: Boolean = false) = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value || conversationId.isBlank()) return@withContext
        try {
            val payloads = fetchTopicPayloadsFromCloud("$baseTopic-conv-$conversationId", forceFullHistory = forceFullHistory)
            for (payload in payloads) {
                dispatchIncomingPayload(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Conversation history sync warning for $conversationId: ${e.message}")
        }
    }

    private suspend fun hydrateCacheFromLocalDao() {
        try {
            val localUsers = userDao.getAllUsersDirect()
            for (local in localUsers) {
                val norm = normalizeUsername(local.usernameNormalized.ifBlank { local.username })
                if (local.id.isNotBlank() && norm.isNotBlank()) {
                    val sanitized = local.copy(
                        username = cleanDisplayUsername(local.username),
                        usernameNormalized = norm,
                        passwordHash = ""
                    )
                    cloudUsersCache[local.id] = mergeUserPreservingNewestPresence(cloudUsersCache[local.id], sanitized)
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun syncUsersFromCloud(targetNormUsername: String = ""): Result<Collection<UserEntity>> = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Unable to search because of a network/backend error."))
        }

        cloudSyncMutex.withLock {
            hydrateCacheFromLocalDao()

            val now = System.currentTimeMillis()
            val hasTargetInCache = targetNormUsername.isNotBlank() &&
                cloudUsersCache.values.any { it.usernameNormalized == targetNormUsername }

            if ((now - lastCloudSyncTimestamp) < 1200L && (targetNormUsername.isBlank() || hasTargetInCache) && cloudUsersCache.isNotEmpty()) {
                return@withLock Result.success(cloudUsersCache.values.toList())
            }

            val canQuerySpecificUsername = targetNormUsername.isNotBlank() &&
                targetNormUsername.matches(Regex("^[a-z0-9_.]+$"))

            // Fast path for username lookup: query per-username topic with full history first
            if (canQuerySpecificUsername) {
                try {
                    val specificPayloads = fetchTopicPayloadsFromCloud(
                        "$baseTopic-uname-$targetNormUsername",
                        forceFullHistory = true
                    )
                    applyUserPayloadsToCache(specificPayloads)
                    val foundExact = cloudUsersCache.values.any {
                        it.usernameNormalized == targetNormUsername
                    }
                    if (foundExact) {
                        return@withLock Result.success(cloudUsersCache.values.toList())
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Specific username topic poll fallback for $targetNormUsername: ${e.message}")
                }
            }

            try {
                val globalPayloads = fetchTopicPayloadsFromCloud(
                    "$baseTopic-users",
                    forceFullHistory = targetNormUsername.isNotBlank()
                )
                applyUserPayloadsToCache(globalPayloads)
                lastCloudSyncTimestamp = System.currentTimeMillis()
                Result.success(cloudUsersCache.values.toList())
            } catch (e: Exception) {
                Log.w(tag, "Cloud sync fallback to local/stream cache: ${e.message}")
                if (RealtimeManager.isNetworkConnected.value) {
                    return@withLock Result.success(cloudUsersCache.values.toList())
                }
                Result.failure(IOException("Unable to search because of a network/backend error.", e))
            }
        }
    }

    fun hasMatchingAuthVerifier(rawUsername: String, expectedAuthVerifier: String): Boolean {
        val norm = normalizeUsername(rawUsername)
        if (norm.isBlank() || expectedAuthVerifier.isBlank()) return false
        val storedVerifier = cloudAuthVerifiers[norm]
        return !storedVerifier.isNullOrBlank() && storedVerifier == expectedAuthVerifier
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

    suspend fun fetchUserByIdFromCloud(userId: String): Result<UserEntity?> = withContext(Dispatchers.IO) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return@withContext Result.success(null)

        cloudUsersCache[cleanUid]?.let { return@withContext Result.success(it) }

        try {
            val userPayloads = fetchTopicPayloadsFromCloud("$baseTopic-user-$cleanUid", forceFullHistory = true)
            applyUserPayloadsToCache(userPayloads)
            cloudUsersCache[cleanUid]?.let { user ->
                userDao.upsertRemoteUser(user, allowPresenceUpdate = true)
                return@withContext Result.success(user)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch user inbox payloads for $cleanUid: ${e.message}")
        }

        val syncRes = syncUsersFromCloud("")
        if (syncRes.isSuccess) {
            val user = cloudUsersCache[cleanUid]
            if (user != null) {
                userDao.upsertRemoteUser(user, allowPresenceUpdate = true)
            }
            return@withContext Result.success(user)
        }
        Result.success(cloudUsersCache[cleanUid])
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
        if (normQuery.isBlank()) {
            return@withContext Result.success(emptyList())
        }

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

        val exactMatch = deduplicatedByNorm[normQuery]
        val matched = if (exactMatch != null) {
            listOf(exactMatch)
        } else {
            deduplicatedByNorm.values.filter { user ->
                user.usernameNormalized == normQuery ||
                    user.usernameNormalized.startsWith(normQuery) ||
                    user.username.lowercase().startsWith(normQuery)
            }.sortedWith(
                compareBy<UserEntity> {
                    when {
                        it.usernameNormalized == normQuery -> 0
                        it.usernameNormalized.startsWith(normQuery) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.isOnline }.thenBy { it.displayName.lowercase() }
            )
        }

        Result.success(matched)
    }

    private suspend fun postPayloadDirect(
        topic: String,
        jsonObject: JSONObject,
        cacheHeader: Boolean = true,
        maxRetries: Int = 2
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) return@withContext false
        val bodyBytes = jsonObject.toString()
        for (attempt in 0..maxRetries) {
            if (!RealtimeManager.isNetworkConnected.value) return@withContext false
            try {
                val body = bodyBytes.toRequestBody(textMediaType)
                val request = Request.Builder()
                    .url("https://ntfy.sh/$topic")
                    .header("Cache", if (cacheHeader) "yes" else "no")
                    .post(body)
                    .build()

                val code = queryClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _isConnected.value = true
                        return@withContext true
                    }
                    response.code
                }
                Log.w(tag, "Publish to $topic HTTP $code (attempt ${attempt + 1})")
                if (code == 429 || code in 500..599) {
                    delay(650L * (attempt + 1))
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.e(tag, "Error posting to $topic (attempt ${attempt + 1}): ${e.message}")
                if (attempt < maxRetries) {
                    delay(500L * (attempt + 1))
                }
            }
        }
        false
    }

    private fun postPayload(topic: String, jsonObject: JSONObject, cacheHeader: Boolean = true) {
        scope.launch {
            postPayloadDirect(topic, jsonObject, cacheHeader = cacheHeader, maxRetries = 1)
        }
    }

    /**
     * Uploads compressed JPEG bytes to shared cloud media storage and returns a permanent https:// mediaUrl.
     */
    suspend fun uploadImageToCloudMedia(messageId: String, jpegBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value || jpegBytes.isEmpty()) return@withContext null
        val imageMediaType = "image/jpeg".toMediaType()
        val mediaTopic = "$baseTopic-media-${messageId.take(24)}"

        for (attempt in 0..1) {
            try {
                val request = Request.Builder()
                    .url("https://ntfy.sh/$mediaTopic")
                    .header("Filename", "easapp_${messageId.take(12)}.jpg")
                    .put(jpegBytes.toRequestBody(imageMediaType))
                    .build()

                val uploadedUrl = queryClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(tag, "Media upload HTTP ${response.code} on attempt ${attempt + 1}")
                        null
                    } else {
                        val respStr = response.body?.string()?.trim() ?: ""
                        if (respStr.startsWith("{")) {
                            val json = JSONObject(respStr)
                            json.optJSONObject("attachment")
                                ?.optString("url", "")
                                ?.trim()
                                ?.takeIf { it.startsWith("http") }
                        } else {
                            null
                        }
                    }
                }
                if (!uploadedUrl.isNullOrBlank()) {
                    return@withContext uploadedUrl
                }
            } catch (e: Exception) {
                Log.w(tag, "Media upload attempt ${attempt + 1} error: ${e.message}")
            }
            if (attempt == 0) delay(550L)
        }
        null
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
            val globalDef = async { postPayloadDirect("$baseTopic-users", payload, maxRetries = 1) }
            val unameDef = async {
                if (normUser.isNotBlank()) {
                    postPayloadDirect("$baseTopic-uname-$normUser", payload, maxRetries = 1)
                } else {
                    false
                }
            }
            val userDef = async {
                if (user.id.isNotBlank()) {
                    postPayloadDirect("$baseTopic-user-${user.id}", payload, maxRetries = 1)
                } else {
                    false
                }
            }
            val globalOk = globalDef.await()
            val unameOk = unameDef.await()
            val userOk = userDef.await()
            if (!globalOk && !unameOk && !userOk && RealtimeManager.isNetworkConnected.value) {
                // Queue background retry if relay was temporarily rate-limited or unreachable
                scope.launch {
                    delay(1500L)
                    postPayloadDirect("$baseTopic-users", payload, maxRetries = 2)
                    if (normUser.isNotBlank()) {
                        postPayloadDirect("$baseTopic-uname-$normUser", payload, maxRetries = 2)
                    }
                    if (user.id.isNotBlank()) {
                        postPayloadDirect("$baseTopic-user-${user.id}", payload, maxRetries = 2)
                    }
                }
                return@coroutineScope true
            }
            globalOk || unameOk || userOk
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
        if (user.id.isNotBlank()) {
            postPayload("$baseTopic-user-${user.id}", payload)
        }
    }

    fun broadcastPresence(
        userId: String,
        isOnline: Boolean,
        lastSeenTimestamp: Long,
        cacheInHistory: Boolean = true
    ) {
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
        postPayload("$baseTopic-users", payload, cacheHeader = cacheInHistory)
        postPayload("$baseTopic-user-$userId", payload, cacheHeader = cacheInHistory)
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

    suspend fun publishUserConversationState(state: UserConversationStateEntity): Boolean = withContext(Dispatchers.IO) {
        val authenticatedUid = currentUserId
        if (authenticatedUid == null || state.userId.isBlank() || state.userId != authenticatedUid) {
            Log.e(tag, "Security violation: attempted to modify conversation state for unauthenticated userId=${state.userId}")
            return@withContext false
        }
        val stateObj = JSONObject().apply {
            put("conversationId", state.conversationId)
            put("userId", state.userId)
            put("otherUserId", state.otherUserId)
            put("hidden", state.hidden)
            put("deletedAt", state.deletedAt)
            put("lastMessage", state.lastMessage)
            put("lastMessageAt", state.lastMessageAt)
            put("updatedAt", state.updatedAt)
        }
        val payload = JSONObject().apply {
            put("action", "USER_CONVERSATION_STATE")
            put("state", stateObj)
        }
        postPayloadDirect("$baseTopic-user-$authenticatedUid", payload, cacheHeader = true, maxRetries = 1)
    }

    suspend fun publishMessageToCloud(
        message: MessageEntity,
        senderProfile: UserEntity?,
        receiverProfile: UserEntity?,
        compactInlineImageUri: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // Security: enforce that senderId matches the authenticated backend session UID
        val authenticatedUid = currentUserId
        if (authenticatedUid == null || message.senderId != authenticatedUid) {
            Log.e(tag, "Security violation: attempted to send message with unauthenticated senderId=${message.senderId}")
            return@withContext false
        }

        val sentStatus = MessageStatus.SENT.name
        val prevRank = statusRank(processedMessageStatuses[message.id] ?: MessageStatus.SENDING.name)
        if (statusRank(sentStatus) >= prevRank) {
            processedMessageStatuses[message.id] = sentStatus
        }

        val isImage = message.type.equals("image", ignoreCase = true) ||
            !message.mediaUrl.isNullOrBlank() ||
            !message.attachmentUri.isNullOrBlank()
        val messageType = if (isImage) "image" else "text"

        // Keep JSON payload under 4,000 bytes so ntfy.sh always delivers it as a live JSON event
        val httpMediaUrl = message.mediaUrl?.takeIf { it.startsWith("http") }
            ?: message.attachmentUri?.takeIf { it.startsWith("http") }
        val safeInlineAttachment = compactInlineImageUri
            ?: message.attachmentUri?.takeIf { it.length <= 2650 }
            ?: httpMediaUrl
        val resolvedMediaUrl = httpMediaUrl ?: safeInlineAttachment

        val msgObj = JSONObject().apply {
            put("messageId", message.id)
            put("id", message.id)
            put("conversationId", message.conversationId)
            put("senderId", message.senderId)
            put("receiverId", message.recipientId)
            put("recipientId", message.recipientId)
            put("type", messageType)
            put("text", message.content)
            put("content", message.content)
            put("mediaUrl", resolvedMediaUrl ?: JSONObject.NULL)
            put("createdAt", message.timestamp)
            put("timestamp", message.timestamp)
            put("status", sentStatus)
            put("attachmentUri", safeInlineAttachment ?: JSONObject.NULL)
            put("attachmentType", message.attachmentType ?: if (isImage) "IMAGE" else JSONObject.NULL)
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
            val recipientDef = async { postPayloadDirect("$baseTopic-user-${message.recipientId}", payload, cacheHeader = true, maxRetries = 1) }
            val convDef = async { postPayloadDirect("$baseTopic-conv-${message.conversationId}", payload, cacheHeader = true, maxRetries = 1) }
            val deliveredToRecipient = recipientDef.await()
            val storedInConv = convDef.await()
            if (!deliveredToRecipient && !storedInConv && RealtimeManager.isNetworkConnected.value) {
                scope.launch {
                    delay(1500L)
                    postPayloadDirect("$baseTopic-user-${message.recipientId}", payload, cacheHeader = true, maxRetries = 2)
                    postPayloadDirect("$baseTopic-conv-${message.conversationId}", payload, cacheHeader = true, maxRetries = 2)
                }
                return@coroutineScope true
            }
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
        postPayload("$baseTopic-user-$senderId", payload, cacheHeader = true)
        postPayload("$baseTopic-conv-$conversationId", payload, cacheHeader = true)
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
        postPayload("$baseTopic-user-$senderId", payload, cacheHeader = true)
        postPayload("$baseTopic-conv-$conversationId", payload, cacheHeader = true)
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
            postPayload("$baseTopic-user-$recipientId", payload, cacheHeader = false)
        } else {
            postPayload("$baseTopic-conv-$conversationId", payload, cacheHeader = false)
        }
    }

    fun stop() {
        currentUserId = null
        activeConversationId = null
        periodicSyncJob?.cancel()
        restartMultiplexedStream()
    }
}
