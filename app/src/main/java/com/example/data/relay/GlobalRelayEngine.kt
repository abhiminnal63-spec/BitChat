package com.example.data.relay

import android.util.Log
import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserEntity
import com.example.data.model.cleanDisplayUsername
import com.example.data.model.normalizeUsername
import com.example.data.realtime.RealtimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GlobalRelayEngine(
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val tag = "GlobalRelay"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val baseTopic = "easapp_relay_v2"

    private val streamClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for SSE stream
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val queryClient = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val textMediaType = "text/plain; charset=utf-8".toMediaType()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val cloudUsersCache = ConcurrentHashMap<String, UserEntity>()
    private val cloudSyncMutex = Mutex()
    @Volatile
    private var lastCloudSyncTimestamp: Long = 0L

    private var usersStreamJob: Job? = null
    private var userDirectStreamJob: Job? = null
    private val activeConvStreamJobs = mutableMapOf<String, Job>()
    private var currentUserId: String? = null

    init {
        // Start listening to global users stream immediately so all registered accounts across devices sync in real time
        ensureUsersStreamActive()
    }

    private fun ensureUsersStreamActive() {
        if (usersStreamJob?.isActive == true) return
        usersStreamJob = scope.launch {
            listenToStream("$baseTopic-users", includeHistory = true) { payload ->
                handleIncomingUserPayload(payload)
            }
        }
    }

    fun start(userId: String) {
        currentUserId = userId
        userDirectStreamJob?.cancel()
        ensureUsersStreamActive()

        // Listen to direct messages for this user (including recent history)
        userDirectStreamJob = scope.launch {
            listenToStream("$baseTopic-user-$userId", includeHistory = true) { payload ->
                handleIncomingMessagePayload(payload)
            }
        }
    }

    fun subscribeToConversation(conversationId: String) {
        if (activeConvStreamJobs.containsKey(conversationId)) return

        val job = scope.launch {
            listenToStream("$baseTopic-conv-$conversationId", includeHistory = true) { payload ->
                handleIncomingConvPayload(payload)
            }
        }
        activeConvStreamJobs[conversationId] = job
    }

    fun unsubscribeFromConversation(conversationId: String) {
        activeConvStreamJobs[conversationId]?.cancel()
        activeConvStreamJobs.remove(conversationId)
    }

    private suspend fun listenToStream(
        topic: String,
        includeHistory: Boolean = true,
        onPayload: suspend (String) -> Unit
    ) {
        var firstConnect = includeHistory
        while (scope.isActive) {
            var retryDelayMs = 3000L
            try {
                val url = if (firstConnect) {
                    "https://ntfy.sh/$topic/json?since=all"
                } else {
                    "https://ntfy.sh/$topic/json"
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
                            val lineStr = line?.trim() ?: continue
                            if (lineStr.isBlank()) continue

                            try {
                                val json = JSONObject(lineStr)
                                val event = json.optString("event", "")
                                if (event == "message") {
                                    val messageContent = json.optString("message", "")
                                    if (messageContent.isNotBlank()) {
                                        onPayload(messageContent)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "JSON parse error from topic $topic: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(tag, "Stream response unsuccessful for $topic: ${response.code}")
                        if (response.code == 429) {
                            retryDelayMs = 6000L
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Stream disconnected for topic $topic: ${e.message}. Retrying...")
                _isConnected.value = false
            }
            delay(retryDelayMs)
        }
    }

    private fun parseUserFromJsonObject(userObj: JSONObject): UserEntity? {
        val id = userObj.optString("id", "").trim()
        val rawUsername = userObj.optString("username", "").trim()
        if (id.isBlank() || rawUsername.isBlank()) return null

        val cleanUser = cleanDisplayUsername(rawUsername)
        val normUser = userObj.optString("usernameNormalized", "")
            .let { normalizeUsername(it).ifBlank { normalizeUsername(cleanUser) } }
        if (normUser.isBlank()) return null

        return UserEntity(
            id = id,
            username = cleanUser,
            usernameNormalized = normUser,
            displayName = userObj.optString("displayName", cleanUser).ifBlank { cleanUser },
            passwordHash = "", // Security: never expose or consume passwordHash from public profile payloads
            avatarSeed = userObj.optString("avatarSeed", "BRUTAL_1"),
            statusMessage = userObj.optString("statusMessage", "Using Easapp"),
            isOnline = userObj.optBoolean("isOnline", false),
            lastSeenTimestamp = userObj.optLong("lastSeenTimestamp", System.currentTimeMillis()),
            createdAt = userObj.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private suspend fun handleIncomingUserPayload(payload: String): UserEntity? {
        try {
            val json = JSONObject(payload)
            val action = json.optString("action")

            when (action) {
                "USER_REGISTERED", "USER_UPDATED" -> {
                    val userObj = json.getJSONObject("user")
                    val user = parseUserFromJsonObject(userObj) ?: return null
                    cloudUsersCache[user.id] = user
                    userDao.upsertRemoteUser(user)
                    Log.d(tag, "Synchronized peer user: @${user.username} (norm=${user.usernameNormalized}) from cloud relay")
                    return user
                }

                "PRESENCE_UPDATE" -> {
                    val userId = json.getString("userId")
                    if (userId == currentUserId) return null
                    val isOnline = json.getBoolean("isOnline")
                    val timestamp = json.getLong("lastSeenTimestamp")
                    cloudUsersCache[userId]?.let { existing ->
                        if (timestamp >= existing.lastSeenTimestamp) {
                            cloudUsersCache[userId] = existing.copy(
                                isOnline = isOnline,
                                lastSeenTimestamp = timestamp
                            )
                        }
                    }
                    userDao.updateOnlineStatus(userId, isOnline, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle user payload: ${e.message}")
        }
        return null
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

    private suspend fun applyPayloadsToCache(payloads: List<String>) {
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
                        val uid = json.optString("userId")
                        if (uid.isNotBlank()) {
                            presenceById[uid] = json.optBoolean("isOnline", false) to
                                json.optLong("lastSeenTimestamp", System.currentTimeMillis())
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        for ((uid, user) in discoveredById) {
            val presence = presenceById[uid]
            val finalUser = if (presence != null && presence.second >= user.lastSeenTimestamp) {
                user.copy(isOnline = presence.first, lastSeenTimestamp = presence.second)
            } else {
                user
            }
            cloudUsersCache[uid] = finalUser
            userDao.upsertRemoteUser(finalUser)
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

            // If we just synced from the cloud within the last 1.5s and already found the target username, reuse fresh snapshot
            if ((now - lastCloudSyncTimestamp) < 1500L && (targetNormUsername.isBlank() || hasTargetInCache) && cloudUsersCache.isNotEmpty()) {
                return@withLock Result.success(cloudUsersCache.values.toList())
            }

            try {
                val globalPayloads = fetchTopicPayloadsFromCloud("$baseTopic-users")
                applyPayloadsToCache(globalPayloads)
                lastCloudSyncTimestamp = System.currentTimeMillis()

                // If looking for a specific username not yet in global topic, also check its dedicated index topic
                if (targetNormUsername.isNotBlank() &&
                    targetNormUsername.matches(Regex("^[a-z0-9_.]+$")) &&
                    cloudUsersCache.values.none { it.usernameNormalized == targetNormUsername }
                ) {
                    try {
                        val specificPayloads = fetchTopicPayloadsFromCloud("$baseTopic-uname-$targetNormUsername")
                        applyPayloadsToCache(specificPayloads)
                    } catch (_: Exception) {
                    }
                }

                Result.success(cloudUsersCache.values.toList())
            } catch (e: Exception) {
                val msg = e.message ?: ""
                // If rate-limited (HTTP 429) during rapid typing while online and SSE stream / cache is active
                if (msg.contains("HTTP_429") && RealtimeManager.isNetworkConnected.value && (_isConnected.value || cloudUsersCache.isNotEmpty())) {
                    Log.w(tag, "Cloud poll rate-limited (429); serving real-time SSE + cloud cache")
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
            ?: userDao.getUserByUsername(norm)
        Result.success(matched)
    }

    suspend fun searchUsersInCloud(rawQuery: String, excludeUserId: String): Result<List<UserEntity>> = withContext(Dispatchers.IO) {
        val normQuery = normalizeUsername(rawQuery)
        val syncResult = syncUsersFromCloud(targetNormUsername = normQuery)
        if (syncResult.isFailure) {
            return@withContext Result.failure(
                syncResult.exceptionOrNull() ?: IOException("Unable to search because of a network/backend error.")
            )
        }

        val combinedMap = linkedMapOf<String, UserEntity>()
        for (u in userDao.getAllUsersDirect()) {
            if (u.id != excludeUserId) {
                val norm = normalizeUsername(u.usernameNormalized.ifBlank { u.username })
                if (norm.isNotBlank()) {
                    combinedMap[norm] = u.copy(usernameNormalized = norm, passwordHash = "")
                }
            }
        }
        for (u in cloudUsersCache.values) {
            if (u.id != excludeUserId) {
                val norm = normalizeUsername(u.usernameNormalized.ifBlank { u.username })
                if (norm.isNotBlank()) {
                    combinedMap[norm] = u.copy(usernameNormalized = norm, passwordHash = "")
                }
            }
        }

        val matched = combinedMap.values.filter { user ->
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

    private suspend fun handleIncomingMessagePayload(payload: String) {
        try {
            val json = JSONObject(payload)
            val action = json.optString("action")

            when (action) {
                "NEW_MESSAGE" -> {
                    val msgObj = json.getJSONObject("message")
                    val senderId = msgObj.getString("senderId")
                    if (senderId == currentUserId) return // skip our own echo

                    val messageId = msgObj.getString("id")
                    val convId = msgObj.getString("conversationId")
                    val recipientId = msgObj.getString("recipientId")
                    val content = msgObj.getString("content")
                    val timestamp = msgObj.getLong("timestamp")
                    val attachmentUri = if (msgObj.has("attachmentUri") && !msgObj.isNull("attachmentUri")) msgObj.getString("attachmentUri") else null
                    val attachmentType = if (msgObj.has("attachmentType") && !msgObj.isNull("attachmentType")) msgObj.getString("attachmentType") else null
                    val attachmentName = if (msgObj.has("attachmentName") && !msgObj.isNull("attachmentName")) msgObj.getString("attachmentName") else null
                    val attachmentSize = if (msgObj.has("attachmentSize")) msgObj.getLong("attachmentSize") else null

                    val isViewing = RealtimeManager.isUserViewingConversation(recipientId, convId)
                    val status = if (isViewing) MessageStatus.READ.name else MessageStatus.DELIVERED.name

                    val message = MessageEntity(
                        id = messageId,
                        conversationId = convId,
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

                    // Update or create local conversation entry
                    val conv = conversationDao.getConversationByIdDirect(convId)
                    if (conv != null) {
                        val isUser1 = conv.participant1Id == currentUserId
                        val unread1 = if (isUser1 && !isViewing) conv.unreadCountForUser1 + 1 else if (isUser1) 0 else conv.unreadCountForUser1
                        val unread2 = if (!isUser1 && !isViewing) conv.unreadCountForUser2 + 1 else if (!isUser1) 0 else conv.unreadCountForUser2

                        val updated = conv.copy(
                            lastMessageText = if (attachmentType != null) "📷 Photo" else content,
                            lastMessageTimestamp = timestamp,
                            lastMessageSenderId = senderId,
                            lastMessageStatus = status,
                            unreadCountForUser1 = unread1,
                            unreadCountForUser2 = unread2,
                            updatedAt = timestamp
                        )
                        conversationDao.updateConversation(updated)
                    } else {
                        val newConv = ConversationEntity(
                            id = convId,
                            participant1Id = senderId,
                            participant2Id = recipientId,
                            lastMessageText = if (attachmentType != null) "📷 Photo" else content,
                            lastMessageTimestamp = timestamp,
                            lastMessageSenderId = senderId,
                            lastMessageStatus = status,
                            unreadCountForUser1 = 0,
                            unreadCountForUser2 = if (!isViewing) 1 else 0,
                            updatedAt = timestamp
                        )
                        conversationDao.insertConversation(newConv)
                    }

                    // If currently viewing, broadcast READ receipt back to sender immediately
                    if (isViewing) {
                        broadcastReadReceipt(convId, messageId, recipientId, senderId)
                    }
                }

                "READ_RECEIPT" -> {
                    val convId = json.getString("conversationId")
                    val messageId = json.optString("messageId")
                    val readerId = json.getString("readerId")

                    if (readerId != currentUserId) {
                        if (messageId.isNotBlank()) {
                            messageDao.updateSingleMessageStatus(messageId, MessageStatus.READ.name)
                        } else {
                            messageDao.markAllInConversationAsRead(convId)
                        }
                        conversationDao.updateLastMessageStatus(convId, MessageStatus.READ.name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle message payload: ${e.message}")
        }
    }

    private suspend fun handleIncomingConvPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            val action = json.optString("action")

            when (action) {
                "NEW_MESSAGE" -> {
                    handleIncomingMessagePayload(payload)
                }

                "READ_RECEIPT" -> {
                    val convId = json.getString("conversationId")
                    val messageId = json.optString("messageId")
                    val readerId = json.getString("readerId")

                    if (readerId != currentUserId) {
                        if (messageId.isNotBlank()) {
                            messageDao.updateSingleMessageStatus(messageId, MessageStatus.READ.name)
                        } else {
                            messageDao.markAllInConversationAsRead(convId)
                        }
                        conversationDao.updateLastMessageStatus(convId, MessageStatus.READ.name)
                    }
                }

                "TYPING_INDICATOR" -> {
                    val convId = json.getString("conversationId")
                    val userId = json.getString("userId")
                    val isTyping = json.getBoolean("isTyping")
                    val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                    val isRecent = (System.currentTimeMillis() - updatedAt) < 10_000L

                    if (userId != currentUserId) {
                        if (isTyping && isRecent) {
                            RealtimeManager.onUserTyping(convId, userId)
                        } else {
                            RealtimeManager.stopUserTyping(convId, userId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle conv payload: ${e.message}")
        }
    }

    private suspend fun postPayloadDirect(topic: String, jsonObject: JSONObject): Boolean = withContext(Dispatchers.IO) {
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

    private fun buildUserPayload(user: UserEntity, isNew: Boolean): JSONObject {
        val cleanUser = cleanDisplayUsername(user.username)
        val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
        // Public searchable profile fields only — never expose passwordHash or private credentials
        val userObj = JSONObject().apply {
            put("id", user.id)
            put("username", cleanUser)
            put("usernameNormalized", normUser)
            put("displayName", user.displayName)
            put("avatarSeed", user.avatarSeed)
            put("statusMessage", user.statusMessage)
            put("isOnline", user.isOnline)
            put("lastSeenTimestamp", user.lastSeenTimestamp)
            put("createdAt", user.createdAt)
        }
        return JSONObject().apply {
            put("action", if (isNew) "USER_REGISTERED" else "USER_UPDATED")
            put("user", userObj)
        }
    }

    suspend fun publishUserSync(user: UserEntity, isNew: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = cleanDisplayUsername(user.username)
        val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
        val sanitized = user.copy(
            username = cleanUser,
            usernameNormalized = normUser,
            passwordHash = ""
        )
        cloudUsersCache[user.id] = sanitized

        val payload = buildUserPayload(sanitized, isNew)
        val globalOk = postPayloadDirect("$baseTopic-users", payload)
        if (normUser.isNotBlank()) {
            postPayload("$baseTopic-uname-$normUser", payload)
        }
        globalOk
    }

    fun broadcastUser(user: UserEntity, isNew: Boolean = false) {
        val cleanUser = cleanDisplayUsername(user.username)
        val normUser = normalizeUsername(user.usernameNormalized.ifBlank { cleanUser })
        val sanitized = user.copy(
            username = cleanUser,
            usernameNormalized = normUser,
            passwordHash = ""
        )
        cloudUsersCache[user.id] = sanitized

        val payload = buildUserPayload(sanitized, isNew)
        postPayload("$baseTopic-users", payload)
        if (normUser.isNotBlank()) {
            postPayload("$baseTopic-uname-$normUser", payload)
        }
    }

    fun broadcastPresence(userId: String, isOnline: Boolean, lastSeenTimestamp: Long) {
        cloudUsersCache[userId]?.let { existing ->
            cloudUsersCache[userId] = existing.copy(isOnline = isOnline, lastSeenTimestamp = lastSeenTimestamp)
        }
        val payload = JSONObject().apply {
            put("action", "PRESENCE_UPDATE")
            put("userId", userId)
            put("isOnline", isOnline)
            put("lastSeenTimestamp", lastSeenTimestamp)
        }
        postPayload("$baseTopic-users", payload)
    }

    fun broadcastMessage(message: MessageEntity) {
        val msgObj = JSONObject().apply {
            put("id", message.id)
            put("conversationId", message.conversationId)
            put("senderId", message.senderId)
            put("recipientId", message.recipientId)
            put("content", message.content)
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
        }

        // Post to recipient direct topic
        postPayload("$baseTopic-user-${message.recipientId}", payload)
        // Also post to conversation topic
        postPayload("$baseTopic-conv-${message.conversationId}", payload)
    }

    fun broadcastReadReceipt(conversationId: String, messageId: String?, readerId: String, senderId: String) {
        val payload = JSONObject().apply {
            put("action", "READ_RECEIPT")
            put("conversationId", conversationId)
            put("messageId", messageId ?: "")
            put("readerId", readerId)
        }
        postPayload("$baseTopic-conv-$conversationId", payload)
        postPayload("$baseTopic-user-$senderId", payload)
    }

    fun broadcastTyping(conversationId: String, userId: String, isTyping: Boolean) {
        val payload = JSONObject().apply {
            put("action", "TYPING_INDICATOR")
            put("conversationId", conversationId)
            put("userId", userId)
            put("isTyping", isTyping)
            put("updatedAt", System.currentTimeMillis())
        }
        postPayload("$baseTopic-conv-$conversationId", payload)
    }

    fun stop() {
        usersStreamJob?.cancel()
        userDirectStreamJob?.cancel()
        activeConvStreamJobs.values.forEach { it.cancel() }
        activeConvStreamJobs.clear()
        _isConnected.value = false
    }
}
