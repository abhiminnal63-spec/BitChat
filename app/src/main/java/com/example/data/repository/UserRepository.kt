package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.dao.UserDao
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.model.UserEntity
import com.example.data.model.cleanDisplayUsername
import com.example.data.model.normalizeUsername
import com.example.data.realtime.RealtimeManager
import com.example.data.relay.GlobalRelayEngine
import com.example.notifications.BitchatNotificationManager
import com.example.notifications.DeviceTokenManager
import com.example.util.BitchatLog
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AuthState {
    INITIALIZING_AUTH,
    AUTHENTICATED,
    UNAUTHENTICATED
}

class UserRepository(
    private val userDao: UserDao,
    context: Context,
    val firestoreSyncManager: FirestoreSyncManager? = null,
    val relayEngine: GlobalRelayEngine? = null
) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)

    private suspend fun registerCurrentDeviceToken(userId: String) {
        if (userId.isBlank()) return
        try {
            DeviceTokenManager.registerDeviceForUser(
                context = appContext,
                userId = userId,
                userDao = userDao,
                firestoreSyncManager = firestoreSyncManager,
                relayEngine = relayEngine
            )
        } catch (_: Exception) {
        }
    }

    private val _currentUserId = MutableStateFlow<String?>(prefs.getString("logged_in_user_id", null))
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(
        if (prefs.getString("logged_in_user_id", null).isNullOrBlank()) {
            AuthState.UNAUTHENTICATED
        } else {
            AuthState.AUTHENTICATED
        }
    )
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Strictly tracks ONLY accounts that have authenticated with credentials on THIS physical device
    // Maps backend UID -> authVerifier
    private val _localSessionVerifiers = MutableStateFlow<Map<String, String>>(loadLocalSessionVerifiers())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private val isAppInForeground = MutableStateFlow(true)

    init {
        RealtimeManager.initNetworkMonitoring(context.applicationContext)
        BitchatNotificationManager.ensureNotificationChannel(appContext)
        BitchatNotificationManager.setAppInForeground(true)

        // Automatically synchronize missed offline messages whenever network connectivity is restored
        scope.launch {
            var wasConnected = RealtimeManager.isNetworkConnected.value
            RealtimeManager.isNetworkConnected.collect { connected ->
                if (connected && !wasConnected) {
                    val uid = _currentUserId.value
                    if (!uid.isNullOrBlank()) {
                        registerCurrentDeviceToken(uid)
                        firestoreSyncManager?.synchronizeOfflineMessagesForUser(uid)
                        relayEngine?.synchronizeOfflineMessagesForUser(uid)
                    }
                }
                wasConnected = connected
            }
        }

        val storedId = _currentUserId.value
        val storedProfileJson = prefs.getString("logged_in_user_profile_json", null)
        if (storedId != null && !storedProfileJson.isNullOrBlank()) {
            parseStoredSessionUser(storedProfileJson)?.let { cachedUser ->
                _currentUser.value = cachedUser
            }
        }
        if (storedId != null) {
            scope.launch {
                var user = userDao.getUserByIdDirect(storedId)
                if (user == null && !storedProfileJson.isNullOrBlank()) {
                    user = parseStoredSessionUser(storedProfileJson)
                    if (user != null) {
                        userDao.insertUser(user)
                    }
                }

                if (user != null) {
                    val now = System.currentTimeMillis()
                    val latestCurrent = _currentUser.value
                    val effectiveUser = if (latestCurrent != null &&
                        latestCurrent.id == user.id &&
                        latestCurrent.lastSeenTimestamp >= user.lastSeenTimestamp
                    ) {
                        user.copy(
                            displayName = latestCurrent.displayName,
                            avatarSeed = latestCurrent.avatarSeed,
                            statusMessage = latestCurrent.statusMessage,
                            lastSeenTimestamp = maxOf(now, latestCurrent.lastSeenTimestamp)
                        )
                    } else {
                        user
                    }
                    val norm = normalizeUsername(effectiveUser.usernameNormalized.ifBlank { effectiveUser.username })
                    val clean = cleanDisplayUsername(effectiveUser.username)
                    val activeUser = effectiveUser.copy(
                        username = clean,
                        usernameNormalized = norm,
                        isOnline = true,
                        lastSeenTimestamp = maxOf(now, effectiveUser.lastSeenTimestamp)
                    )
                    userDao.insertUser(activeUser)
                    _currentUser.value = activeUser

                    val storedVerifier = _localSessionVerifiers.value[storedId]
                    saveLocalAuthenticatedSession(activeUser, storedVerifier ?: "")

                    firestoreSyncManager?.updatePresenceInCloud(storedId, true, now)
                    firestoreSyncManager?.syncUserToCloud(activeUser, storedVerifier)
                    firestoreSyncManager?.startSync(storedId)

                    relayEngine?.start(storedId)
                    relayEngine?.broadcastPresence(storedId, true, now, cacheInHistory = true)
                    relayEngine?.broadcastUser(activeUser, isNew = false, authVerifier = storedVerifier)
                    registerCurrentDeviceToken(storedId)
                    firestoreSyncManager?.synchronizeOfflineMessagesForUser(storedId)
                    startPresenceHeartbeat()
                } else {
                    _currentUserId.value = null
                    _currentUser.value = null
                    _authState.value = AuthState.UNAUTHENTICATED
                    BitchatLog.authState("UNAUTHENTICATED", null)
                    prefs.edit()
                        .remove("logged_in_user_id")
                        .remove("logged_in_user_profile_json")
                        .apply()
                }
            }
        }
    }

    fun onAppForegrounded() {
        isAppInForeground.value = true
        BitchatNotificationManager.setAppInForeground(true)
        val uid = _currentUserId.value ?: return
        BitchatLog.presenceOnline(uid)
        com.example.notifications.BitchatPushService.ensureStarted(appContext, uid)
        scope.launch {
            val now = System.currentTimeMillis()
            userDao.updateOnlineStatus(uid, true, now)
            _currentUser.value = _currentUser.value?.copy(isOnline = true, lastSeenTimestamp = now)
            firestoreSyncManager?.updatePresenceInCloud(uid, true, now)
            relayEngine?.start(uid)
            relayEngine?.broadcastPresence(uid, true, now, cacheInHistory = true)
            registerCurrentDeviceToken(uid)
            firestoreSyncManager?.synchronizeOfflineMessagesForUser(uid)
            relayEngine?.synchronizeOfflineMessagesForUser(uid)
        }
        startPresenceHeartbeat()
    }

    fun onAppBackgrounded() {
        isAppInForeground.value = false
        BitchatNotificationManager.setAppInForeground(false)
        RealtimeManager.clearAllActiveConversations()
        heartbeatJob?.cancel()
        heartbeatJob = null
        val uid = _currentUserId.value ?: return
        BitchatLog.presenceOffline(uid)
        com.example.notifications.BitchatPushService.ensureStarted(appContext, uid)
        scope.launch {
            val now = System.currentTimeMillis()
            userDao.updateOnlineStatus(uid, false, now)
            _currentUser.value = _currentUser.value?.copy(isOnline = false, lastSeenTimestamp = now)
            firestoreSyncManager?.updatePresenceInCloud(uid, false, now)
            relayEngine?.broadcastPresence(uid, false, now, cacheInHistory = true)
        }
    }

    suspend fun synchronizeOfflineMessages(): Int = withContext(Dispatchers.IO) {
        val uid = _currentUserId.value ?: return@withContext 0
        val fsCount = firestoreSyncManager?.synchronizeOfflineMessagesForUser(uid) ?: 0
        val relayCount = relayEngine?.synchronizeOfflineMessagesForUser(uid) ?: 0
        fsCount + relayCount
    }

    private fun startPresenceHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var tick = 0
            while (isActive && isAppInForeground.value) {
                delay(25_000L)
                if (!isAppInForeground.value) break
                val uid = _currentUserId.value ?: break
                if (RealtimeManager.isNetworkConnected.value) {
                    val now = System.currentTimeMillis()
                    userDao.updateOnlineStatus(uid, true, now)
                    _currentUser.value = _currentUser.value?.copy(isOnline = true, lastSeenTimestamp = now)
                    firestoreSyncManager?.updatePresenceInCloud(uid, true, now)
                    // Cache every 3rd heartbeat in ntfy history, stream all others live with Cache: no
                    relayEngine?.broadcastPresence(uid, true, now, cacheInHistory = (tick % 3 == 0))
                    tick++
                }
            }
        }
    }

    private fun loadLocalSessionVerifiers(): Map<String, String> {
        val raw = prefs.getString("local_authenticated_sessions_v3", null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val map = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val uid = keys.next()
                val verifier = json.optString(uid, "")
                if (uid.isNotBlank() && verifier.isNotBlank()) {
                    map[uid] = verifier
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveLocalAuthenticatedSession(user: UserEntity, authVerifier: String) {
        val updated = _localSessionVerifiers.value.toMutableMap()
        if (authVerifier.isNotBlank()) {
            updated[user.id] = authVerifier
        } else if (!updated.containsKey(user.id)) {
            updated[user.id] = "session_${user.id}"
        }
        _localSessionVerifiers.value = updated

        val json = JSONObject()
        for ((uid, verifier) in updated) {
            json.put(uid, verifier)
        }

        val profileJson = JSONObject().apply {
            put("id", user.id)
            put("username", cleanDisplayUsername(user.username))
            put("usernameNormalized", normalizeUsername(user.usernameNormalized.ifBlank { user.username }))
            put("displayName", user.displayName)
            put("avatarSeed", user.avatarSeed)
            put("statusMessage", user.statusMessage)
            put("createdAt", user.createdAt)
        }.toString()

        prefs.edit()
            .putString("local_authenticated_sessions_v3", json.toString())
            .putString("logged_in_user_id", user.id)
            .putString("logged_in_user_profile_json", profileJson)
            .apply()
    }

    private fun parseStoredSessionUser(jsonStr: String): UserEntity? {
        return try {
            val obj = JSONObject(jsonStr)
            val id = obj.optString("id", "")
            val username = cleanDisplayUsername(obj.optString("username", ""))
            if (id.isBlank() || username.isBlank()) return null
            val norm = normalizeUsername(obj.optString("usernameNormalized", username))
            UserEntity(
                id = id,
                username = username,
                usernameNormalized = norm,
                displayName = obj.optString("displayName", username).ifBlank { username },
                passwordHash = "",
                avatarSeed = obj.optString("avatarSeed", "BRUTAL_1"),
                statusMessage = obj.optString("statusMessage", "Available on BITCHAT"),
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis(),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns ONLY accounts that have explicitly authenticated on THIS physical device
     * (for the local Account Switcher UI). Remote users discovered from the cloud never appear here.
     */
    fun getLocalAuthenticatedSessionsFlow(): Flow<List<UserEntity>> {
        return combine(userDao.getAllUsersFlow(), _localSessionVerifiers) { allUsers, localVerifiers ->
            allUsers.filter { localVerifiers.containsKey(it.id) }
        }
    }

    suspend fun registerUser(
        rawUsername: String,
        displayName: String,
        rawPassword: String
    ): Result<UserEntity> = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Cannot register while offline. Connect to the internet to register on the shared backend."))
        }

        // 1. Remove @ if supplied & 2. Trim whitespace
        val cleanUsername = cleanDisplayUsername(rawUsername)
        // 3. Convert to lowercase for usernameNormalized
        val usernameNormalized = normalizeUsername(cleanUsername)
        val cleanDisplayName = cleanDisplayUsername(displayName).ifBlank { cleanUsername }

        if (usernameNormalized.length < 3) {
            return@withContext Result.failure(IllegalArgumentException("Username must be at least 3 characters"))
        }
        if (!usernameNormalized.matches(Regex("^[a-z0-9_.]+$"))) {
            return@withContext Result.failure(IllegalArgumentException("Username can only contain letters, numbers, underscores, and dots"))
        }
        if (cleanDisplayName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Display name cannot be blank"))
        }
        if (rawPassword.length < 4) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 4 characters"))
        }

        val passwordHash = hashPassword(rawPassword)
        val authVerifier = computeAuthVerifier(usernameNormalized, rawPassword)

        // 4. Check local DB for an existing registered local account with password
        val localExisting = userDao.getUserByUsername(usernameNormalized)
        if (localExisting != null && localExisting.passwordHash.isNotBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Username @$usernameNormalized is already registered"))
        }

        val (firestoreExisting, relayExisting) = coroutineScope {
            val fsDeferred = async { firestoreSyncManager?.checkUsernameExistsInCloud(usernameNormalized) }
            val relayDeferred = async { relayEngine?.checkUsernameExistsInCloud(usernameNormalized) }
            fsDeferred.await() to relayDeferred.await()
        }

        val cloudExisting = relayExisting?.getOrNull() ?: firestoreExisting?.getOrNull()

        // 5. Generate permanent backend UID (or reuse existing cloud UID for cross-device identity continuity)
        val backendUid = cloudExisting?.id ?: "uid_${UUID.randomUUID().toString().replace("-", "")}"
        val avatarSeed = cloudExisting?.avatarSeed?.takeIf { it.isNotBlank() } ?: "BRUTAL_${(1..5).random()}"
        val now = System.currentTimeMillis()

        val newUser = UserEntity(
            id = backendUid,
            username = cleanUsername,
            usernameNormalized = usernameNormalized,
            displayName = cleanDisplayName,
            passwordHash = passwordHash,
            avatarSeed = avatarSeed,
            statusMessage = "Available on BITCHAT",
            isOnline = true,
            lastSeenTimestamp = now,
            createdAt = now
        )

        // Persist locally first and set session, then publish to shared cloud backend
        userDao.insertUser(newUser)
        setSession(newUser, authVerifier)

        firestoreSyncManager?.syncUserToCloud(newUser, authVerifier)
        relayEngine?.publishUserSync(newUser, isNew = true, authVerifier = authVerifier)
        firestoreSyncManager?.startSync(newUser.id)
        relayEngine?.start(newUser.id)
        scope.launch {
            registerCurrentDeviceToken(newUser.id)
        }

        Result.success(newUser)
    }

    suspend fun loginUser(rawUsername: String, rawPassword: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val usernameNormalized = normalizeUsername(rawUsername)
        if (usernameNormalized.isBlank() || rawPassword.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter username and password"))
        }

        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Unable to sign in because the device is offline."))
        }

        val expectedVerifier = computeAuthVerifier(usernameNormalized, rawPassword)
        val expectedHash = hashPassword(rawPassword)

        // Authenticate against the shared cloud backend and local persistence
        val (fsAuthResult, relayAuthResult) = coroutineScope {
            val fsDef = async { firestoreSyncManager?.authenticateUserInCloud(usernameNormalized, expectedVerifier) }
            val relayDef = async { relayEngine?.authenticateUserInCloud(usernameNormalized, expectedVerifier) }
            fsDef.await() to relayDef.await()
        }

        var authenticatedUser = relayAuthResult?.getOrNull() ?: fsAuthResult?.getOrNull()
        val localUser = userDao.getUserByUsername(usernameNormalized)
        if (authenticatedUser == null) {
            if (localUser != null) {
                val savedVerifier = _localSessionVerifiers.value[localUser.id]
                val passwordMatches = (localUser.passwordHash.isNotBlank() && localUser.passwordHash == expectedHash) ||
                    (!savedVerifier.isNullOrBlank() && savedVerifier == expectedVerifier)
                val hasStoredCredentials = localUser.passwordHash.isNotBlank() || !savedVerifier.isNullOrBlank()
                if (hasStoredCredentials && !passwordMatches) {
                    return@withContext Result.failure(IllegalArgumentException("Incorrect password for @$usernameNormalized"))
                }
                authenticatedUser = localUser
            }
        } else if (localUser != null && localUser.id == authenticatedUser.id && localUser.lastSeenTimestamp >= authenticatedUser.lastSeenTimestamp) {
            authenticatedUser = authenticatedUser.copy(
                displayName = localUser.displayName.ifBlank { authenticatedUser.displayName },
                avatarSeed = localUser.avatarSeed.ifBlank { authenticatedUser.avatarSeed },
                statusMessage = localUser.statusMessage.ifBlank { authenticatedUser.statusMessage },
                lastSeenTimestamp = localUser.lastSeenTimestamp
            )
        }

        if (authenticatedUser == null) {
            val err = relayAuthResult?.exceptionOrNull()
                ?: fsAuthResult?.exceptionOrNull()
                ?: IllegalArgumentException("No registered user found with @$usernameNormalized")
            return@withContext Result.failure(err)
        }

        val now = System.currentTimeMillis()
        val updatedUser = authenticatedUser.copy(
            username = cleanDisplayUsername(authenticatedUser.username),
            usernameNormalized = normalizeUsername(authenticatedUser.usernameNormalized.ifBlank { authenticatedUser.username }),
            passwordHash = expectedHash,
            isOnline = true,
            lastSeenTimestamp = now
        )

        userDao.upsertRemoteUser(updatedUser)
        userDao.updateOnlineStatus(updatedUser.id, true, now)

        firestoreSyncManager?.updatePresenceInCloud(updatedUser.id, true, now)
        firestoreSyncManager?.syncUserToCloud(updatedUser, expectedVerifier)
        firestoreSyncManager?.startSync(updatedUser.id)

        relayEngine?.start(updatedUser.id)
        relayEngine?.broadcastPresence(updatedUser.id, true, now)
        relayEngine?.broadcastUser(updatedUser, isNew = false, authVerifier = expectedVerifier)

        setSession(updatedUser, expectedVerifier)
        scope.launch {
            registerCurrentDeviceToken(updatedUser.id)
            firestoreSyncManager?.synchronizeOfflineMessagesForUser(updatedUser.id)
        }
        Result.success(updatedUser)
    }

    suspend fun switchUser(userId: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        // Security: Only allow switching to an account that has authenticated on THIS device
        val savedVerifier = _localSessionVerifiers.value[userId]
            ?: return@withContext Result.failure(SecurityException("Account is not authenticated on this device. Please sign in with username and password."))

        val targetUser = userDao.getUserByIdDirect(userId)
            ?: return@withContext Result.failure(IllegalArgumentException("Session account not found"))

        val now = System.currentTimeMillis()
        // Mark previous local session as offline in cloud
        _currentUserId.value?.let { oldId ->
            userDao.updateOnlineStatus(oldId, false, now)
            firestoreSyncManager?.updatePresenceInCloud(oldId, false, now)
            relayEngine?.broadcastPresence(oldId, false, now)
        }

        val norm = normalizeUsername(targetUser.usernameNormalized.ifBlank { targetUser.username })
        if (RealtimeManager.isNetworkConnected.value && savedVerifier.startsWith("v1_")) {
            val cloudAuth = relayEngine?.authenticateUserInCloud(norm, savedVerifier)
            if (cloudAuth?.isFailure == true && cloudAuth.exceptionOrNull() is IllegalArgumentException) {
                return@withContext Result.failure(cloudAuth.exceptionOrNull()!!)
            }
        }

        userDao.updateOnlineStatus(userId, true, now)
        val updated = targetUser.copy(
            username = cleanDisplayUsername(targetUser.username),
            usernameNormalized = norm,
            isOnline = true,
            lastSeenTimestamp = now
        )

        firestoreSyncManager?.updatePresenceInCloud(userId, true, now)
        firestoreSyncManager?.syncUserToCloud(updated, savedVerifier)
        firestoreSyncManager?.startSync(userId)

        relayEngine?.start(userId)
        relayEngine?.broadcastPresence(userId, true, now)
        relayEngine?.broadcastUser(updated, isNew = false, authVerifier = savedVerifier)

        setSession(updated, savedVerifier)
        scope.launch {
            registerCurrentDeviceToken(userId)
            firestoreSyncManager?.synchronizeOfflineMessagesForUser(userId)
        }
        Result.success(updated)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val now = System.currentTimeMillis()
        _currentUserId.value?.let { id ->
            userDao.updateOnlineStatus(id, false, now)
            firestoreSyncManager?.updatePresenceInCloud(id, false, now)
            relayEngine?.broadcastPresence(id, false, now, cacheInHistory = true)
            BitchatLog.presenceOffline(id)
        }
        firestoreSyncManager?.stopSync()
        relayEngine?.stop()

        _currentUserId.value = null
        _currentUser.value = null
        _authState.value = AuthState.UNAUTHENTICATED
        BitchatLog.authState("UNAUTHENTICATED", null)
        prefs.edit()
            .remove("logged_in_user_id")
            .remove("logged_in_user_profile_json")
            .apply()
    }

    private fun setSession(user: UserEntity, authVerifier: String) {
        _currentUserId.value = user.id
        _currentUser.value = user
        _authState.value = AuthState.AUTHENTICATED
        BitchatLog.authState("AUTHENTICATED", user.id)
        BitchatLog.presenceOnline(user.id)
        saveLocalAuthenticatedSession(user, authVerifier)
        com.example.notifications.BitchatPushService.ensureStarted(appContext, user.id)
        startPresenceHeartbeat()
    }

    fun selectAvatarImmediate(avatarSeed: String): UserEntity? {
        val current = _currentUser.value ?: return null
        val cleanSeed = avatarSeed.trim().ifBlank { current.avatarSeed }
        val now = System.currentTimeMillis()
        val updated = current.copy(
            avatarSeed = cleanSeed,
            isOnline = true,
            lastSeenTimestamp = maxOf(current.lastSeenTimestamp + 1L, now)
        )
        val verifier = _localSessionVerifiers.value[current.id] ?: ""
        _currentUser.value = updated
        saveLocalAuthenticatedSession(updated, verifier)
        scope.launch {
            try {
                userDao.updateUser(updated)
            } catch (_: Exception) {
            }
        }
        return updated
    }

    suspend fun updateProfile(displayName: String, statusMessage: String, avatarSeed: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext Result.failure(IllegalStateException("Not logged in"))
        val now = System.currentTimeMillis()
        val updated = current.copy(
            displayName = displayName.trim().ifBlank { current.displayName },
            statusMessage = statusMessage.trim().ifBlank { current.statusMessage },
            avatarSeed = avatarSeed.trim().ifBlank { current.avatarSeed },
            isOnline = true,
            lastSeenTimestamp = maxOf(current.lastSeenTimestamp + 1L, now)
        )
        val verifier = _localSessionVerifiers.value[current.id]
        userDao.updateUser(updated)
        firestoreSyncManager?.syncUserToCloud(updated, verifier)
        relayEngine?.publishUserSync(updated, isNew = false, authVerifier = verifier)
        _currentUser.value = updated
        saveLocalAuthenticatedSession(updated, verifier ?: "")
        Result.success(updated)
    }

    /**
     * Queries the shared cloud backend (Firestore + GlobalRelayEngine) in real time using normalized username.
     * Never exposes the full user directory when query is blank and never uses local sessions as the source of truth.
     */
    suspend fun searchUsersInBackend(rawQuery: String, currentUserId: String): Result<List<UserEntity>> = withContext(Dispatchers.IO) {
        val normQuery = normalizeUsername(rawQuery)
        if (normQuery.isBlank()) {
            return@withContext Result.success(emptyList())
        }

        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Unable to search because of a network/backend error."))
        }

        val (firestoreResult, relayResult) = coroutineScope {
            val fsDeferred = async { firestoreSyncManager?.searchUsersInCloud(normQuery, currentUserId) }
            val relayDeferred = async { relayEngine?.searchUsersInCloud(normQuery, currentUserId) }
            fsDeferred.await() to relayDeferred.await()
        }

        val mergedByNorm = linkedMapOf<String, UserEntity>()
        if (firestoreSyncManager == null && relayEngine == null) {
            val fallbackUsers = try {
                userDao.searchUsersDirect(normQuery, currentUserId)
            } catch (_: Exception) {
                emptyList()
            }
            fallbackUsers.forEach { user ->
                if (user.id != currentUserId) {
                    val norm = normalizeUsername(user.usernameNormalized.ifBlank { user.username })
                    if (norm.isNotBlank()) {
                        mergedByNorm[norm] = user.copy(usernameNormalized = norm, passwordHash = "")
                    }
                }
            }
        }
        firestoreResult?.getOrNull()?.forEach { user ->
            if (user.id != currentUserId) {
                val norm = normalizeUsername(user.usernameNormalized.ifBlank { user.username })
                if (norm.isNotBlank()) {
                    mergedByNorm[norm] = user.copy(usernameNormalized = norm, passwordHash = "")
                }
            }
        }
        relayResult?.getOrNull()?.forEach { user ->
            if (user.id != currentUserId) {
                val norm = normalizeUsername(user.usernameNormalized.ifBlank { user.username })
                if (norm.isNotBlank()) {
                    val existing = mergedByNorm[norm]
                    if (existing == null || user.lastSeenTimestamp >= existing.lastSeenTimestamp) {
                        mergedByNorm[norm] = user.copy(usernameNormalized = norm, passwordHash = "")
                    }
                }
            }
        }

        val exactMatch = mergedByNorm[normQuery]
        val sorted = if (exactMatch != null) {
            listOf(exactMatch)
        } else {
            mergedByNorm.values.filter { user ->
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

        Result.success(sorted)
    }

    fun searchUsers(query: String, currentUserId: String): Flow<List<UserEntity>> {
        val normQuery = normalizeUsername(query)
        return userDao.searchUsers(normQuery, currentUserId)
    }

    fun getAllUsersExcept(currentUserId: String): Flow<List<UserEntity>> {
        return userDao.getAllUsersExcept(currentUserId)
    }

    fun getAllUsersFlow(): Flow<List<UserEntity>> {
        return userDao.getAllUsersFlow()
    }

    fun getUserById(id: String): Flow<UserEntity?> {
        return userDao.getUserById(id)
    }

    suspend fun getUserByIdDirect(id: String): UserEntity? = withContext(Dispatchers.IO) {
        userDao.getUserByIdDirect(id)
    }

    /**
     * Resolves the user's real profile directly from the shared backend (Firestore + GlobalRelayEngine)
     * using their authenticated backend UID and updates the local database.
     */
    suspend fun fetchAndCacheUserById(userId: String): Result<UserEntity?> = withContext(Dispatchers.IO) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return@withContext Result.success(null)

        val localUser = userDao.getUserByIdDirect(cleanUid)

        val (firestoreUser, relayUser) = coroutineScope {
            val fsDeferred = async {
                try {
                    firestoreSyncManager?.fetchUserByIdFromCloud(cleanUid)?.getOrNull()
                } catch (_: Exception) {
                    null
                }
            }
            val relayDeferred = async {
                try {
                    relayEngine?.fetchUserByIdFromCloud(cleanUid)?.getOrNull()
                } catch (_: Exception) {
                    null
                }
            }
            fsDeferred.await() to relayDeferred.await()
        }

        val resolvedCloudUser = relayUser ?: firestoreUser
        if (resolvedCloudUser != null) {
            val merged = if (localUser != null) {
                localUser.copy(
                    username = resolvedCloudUser.username.ifBlank { localUser.username },
                    usernameNormalized = resolvedCloudUser.usernameNormalized.ifBlank { localUser.usernameNormalized },
                    displayName = resolvedCloudUser.displayName.ifBlank { localUser.displayName },
                    avatarSeed = resolvedCloudUser.avatarSeed.ifBlank { localUser.avatarSeed },
                    statusMessage = resolvedCloudUser.statusMessage.ifBlank { localUser.statusMessage },
                    isOnline = resolvedCloudUser.isOnline,
                    lastSeenTimestamp = maxOf(localUser.lastSeenTimestamp, resolvedCloudUser.lastSeenTimestamp)
                )
            } else {
                resolvedCloudUser
            }
            userDao.upsertRemoteUser(merged, allowPresenceUpdate = true)
            return@withContext Result.success(merged)
        }

        if (localUser != null && localUser.displayName.isNotBlank()) {
            return@withContext Result.success(localUser)
        }

        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Offline: profile unavailable"))
        }

        Result.success(localUser)
    }

    private fun computeAuthVerifier(usernameNormalized: String, rawPassword: String): String {
        val input = "easapp_auth_verifier_v1:$usernameNormalized:$rawPassword"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return "v1_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
