package com.example.data.relay

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class GlobalRelayEngine(
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val tag = "GlobalRelay"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val baseTopic = "easapp_relay_v1"

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for SSE stream
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var usersStreamJob: Job? = null
    private var userDirectStreamJob: Job? = null
    private val activeConvStreamJobs = mutableMapOf<String, Job>()
    private var currentUserId: String? = null

    fun start(userId: String) {
        currentUserId = userId
        stop()

        // 1. Listen to global users & presence topic
        usersStreamJob = scope.launch {
            listenToStream("$baseTopic-users") { payload ->
                handleIncomingUserPayload(payload)
            }
        }

        // 2. Listen to direct messages for this user
        userDirectStreamJob = scope.launch {
            listenToStream("$baseTopic-user-$userId") { payload ->
                handleIncomingMessagePayload(payload)
            }
        }
    }

    fun subscribeToConversation(conversationId: String) {
        if (activeConvStreamJobs.containsKey(conversationId)) return

        val job = scope.launch {
            listenToStream("$baseTopic-conv-$conversationId") { payload ->
                handleIncomingConvPayload(payload)
            }
        }
        activeConvStreamJobs[conversationId] = job
    }

    fun unsubscribeFromConversation(conversationId: String) {
        activeConvStreamJobs[conversationId]?.cancel()
        activeConvStreamJobs.remove(conversationId)
    }

    private suspend fun listenToStream(topic: String, onPayload: suspend (String) -> Unit) {
        while (scope.isActive) {
            try {
                val request = Request.Builder()
                    .url("https://ntfy.sh/$topic/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _isConnected.value = true
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
                        Log.w(tag, "Stream response unsuccessful: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Stream disconnected for topic $topic: ${e.message}. Retrying in 4s...")
                _isConnected.value = false
                delay(4000)
            }
        }
    }

    private suspend fun handleIncomingUserPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            val action = json.optString("action")

            when (action) {
                "USER_REGISTERED", "USER_UPDATED" -> {
                    val userObj = json.getJSONObject("user")
                    val id = userObj.getString("id")
                    if (id == currentUserId) return // skip our own echo

                    val user = UserEntity(
                        id = id,
                        username = userObj.getString("username"),
                        displayName = userObj.getString("displayName"),
                        passwordHash = userObj.optString("passwordHash", ""),
                        avatarSeed = userObj.optString("avatarSeed", "BRUTAL_1"),
                        statusMessage = userObj.optString("statusMessage", "Using Easapp"),
                        isOnline = userObj.optBoolean("isOnline", false),
                        lastSeenTimestamp = userObj.optLong("lastSeenTimestamp", System.currentTimeMillis()),
                        createdAt = userObj.optLong("createdAt", System.currentTimeMillis())
                    )
                    userDao.insertUser(user)
                    Log.d(tag, "Synchronized peer user: @${user.username} from cloud relay")
                }

                "PRESENCE_UPDATE" -> {
                    val userId = json.getString("userId")
                    if (userId == currentUserId) return
                    val isOnline = json.getBoolean("isOnline")
                    val timestamp = json.getLong("lastSeenTimestamp")
                    userDao.updateOnlineStatus(userId, isOnline, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle user payload: ${e.message}")
        }
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

                    if (userId != currentUserId) {
                        if (isTyping) {
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

    private fun postPayload(topic: String, jsonObject: JSONObject) {
        scope.launch {
            try {
                val body = jsonObject.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url("https://ntfy.sh/$topic")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(tag, "Failed to publish to $topic: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error posting to $topic: ${e.message}")
            }
        }
    }

    fun broadcastUser(user: UserEntity, isNew: Boolean = false) {
        val userObj = JSONObject().apply {
            put("id", user.id)
            put("username", user.username)
            put("displayName", user.displayName)
            put("avatarSeed", user.avatarSeed)
            put("statusMessage", user.statusMessage)
            put("isOnline", user.isOnline)
            put("lastSeenTimestamp", user.lastSeenTimestamp)
            put("createdAt", user.createdAt)
        }
        val payload = JSONObject().apply {
            put("action", if (isNew) "USER_REGISTERED" else "USER_UPDATED")
            put("user", userObj)
        }
        postPayload("$baseTopic-users", payload)
    }

    fun broadcastPresence(userId: String, isOnline: Boolean, lastSeenTimestamp: Long) {
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
