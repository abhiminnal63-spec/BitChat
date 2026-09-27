package com.example.data.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    // Tracks whether BITCHAT is in the foreground (true) or background/closed (false)
    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    private var osNetworkAvailable = true
    private var manualOfflineOverride = false
    private var networkCallbackRegistered = false

    // Network connectivity state (reflects real OS connectivity and optional debug toggle)
    private val _isNetworkConnected = MutableStateFlow(true)
    val isNetworkConnected: StateFlow<Boolean> = _isNetworkConnected.asStateFlow()

    fun initNetworkMonitoring(context: Context) {
        if (networkCallbackRegistered) return
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val activeNet = cm.activeNetwork
            val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
            val anyNetHasInternet = cm.allNetworks.any { net ->
                cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            osNetworkAvailable = if (activeNet != null && caps != null) {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || anyNetHasInternet
            } else {
                true
            }
            if (!osNetworkAvailable && cm.allNetworks.isEmpty()) {
                osNetworkAvailable = true
            }
            updateEffectiveNetworkState()

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        osNetworkAvailable = true
                        updateEffectiveNetworkState()
                    }

                    override fun onLost(network: Network) {
                        val currentActive = cm.activeNetwork
                        val currentCaps = currentActive?.let { cm.getNetworkCapabilities(it) }
                        val stillConnected = currentCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
                            cm.allNetworks.any { net ->
                                cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                            }
                        osNetworkAvailable = stillConnected || currentActive == null
                        updateEffectiveNetworkState()
                    }
                }
            )
            networkCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun updateEffectiveNetworkState() {
        _isNetworkConnected.value = osNetworkAvailable && !manualOfflineOverride
    }

    fun setNetworkConnected(connected: Boolean) {
        if (connected) {
            osNetworkAvailable = true
        }
        manualOfflineOverride = !connected
        updateEffectiveNetworkState()
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
