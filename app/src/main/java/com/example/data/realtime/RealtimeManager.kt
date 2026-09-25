package com.example.data.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object RealtimeManager {
    private val scope = CoroutineScope(Dispatchers.Default)

    // Tracks conversationId -> Set of userIds who are currently typing
    private val _typingUsers = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typingUsers: StateFlow<Map<String, Set<String>>> = _typingUsers.asStateFlow()

    // Store typing timeout jobs: "conversationId_userId" -> Job
    private val typingJobs = mutableMapOf<String, Job>()

    // Tracks which conversation a user is actively viewing: userId -> conversationId
    private val _activeUserScreens = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeUserScreens: StateFlow<Map<String, String>> = _activeUserScreens.asStateFlow()

    // Network connectivity simulation state (can be toggled in brutalist debug bar)
    private val _isNetworkConnected = MutableStateFlow(true)
    val isNetworkConnected: StateFlow<Boolean> = _isNetworkConnected.asStateFlow()

    fun setNetworkConnected(connected: Boolean) {
        _isNetworkConnected.value = connected
    }

    fun setUserActiveConversation(userId: String, conversationId: String?) {
        val current = _activeUserScreens.value.toMutableMap()
        if (conversationId != null) {
            current[userId] = conversationId
        } else {
            current.remove(userId)
        }
        _activeUserScreens.value = current
    }

    fun isUserViewingConversation(userId: String, conversationId: String): Boolean {
        return _activeUserScreens.value[userId] == conversationId
    }

    fun onUserTyping(conversationId: String, userId: String) {
        val jobKey = "${conversationId}_$userId"
        typingJobs[jobKey]?.cancel()

        // Add to typing map
        val currentMap = _typingUsers.value.toMutableMap()
        val currentSet = currentMap[conversationId]?.toMutableSet() ?: mutableSetOf()
        currentSet.add(userId)
        currentMap[conversationId] = currentSet
        _typingUsers.value = currentMap

        // Auto-expire typing indicator after 2.5 seconds
        typingJobs[jobKey] = scope.launch {
            delay(2500)
            stopUserTyping(conversationId, userId)
        }
    }

    fun stopUserTyping(conversationId: String, userId: String) {
        val jobKey = "${conversationId}_$userId"
        typingJobs[jobKey]?.cancel()
        typingJobs.remove(jobKey)

        val currentMap = _typingUsers.value.toMutableMap()
        val currentSet = currentMap[conversationId]?.toMutableSet() ?: return
        currentSet.remove(userId)
        if (currentSet.isEmpty()) {
            currentMap.remove(conversationId)
        } else {
            currentMap[conversationId] = currentSet
        }
        _typingUsers.value = currentMap
    }
}
