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
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class UserRepository(
    private val userDao: UserDao,
    context: Context,
    val firestoreSyncManager: FirestoreSyncManager? = null,
    val relayEngine: GlobalRelayEngine? = null
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)

    private val _currentUserId = MutableStateFlow<String?>(prefs.getString("logged_in_user_id", null))
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    // Strictly tracks ONLY accounts that have authenticated with credentials on THIS physical device
    // Maps backend UID -> authVerifier
    private val _localSessionVerifiers = MutableStateFlow<Map<String, String>>(loadLocalSessionVerifiers())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        RealtimeManager.initNetworkMonitoring(context.applicationContext)

        val storedId = _currentUserId.value
        val storedProfileJson = prefs.getString("logged_in_user_profile_json", null)
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
                    val norm = normalizeUsername(user.usernameNormalized.ifBlank { user.username })
                    val clean = cleanDisplayUsername(user.username)
                    val activeUser = user.copy(
                        username = clean,
                        usernameNormalized = norm,
                        isOnline = true,
                        lastSeenTimestamp = now
                    )
                    userDao.insertUser(activeUser)
                    _currentUser.value = activeUser

                    val storedVerifier = _localSessionVerifiers.value[storedId]
                    saveLocalAuthenticatedSession(activeUser, storedVerifier ?: "")

                    firestoreSyncManager?.updatePresenceInCloud(storedId, true, now)
                    firestoreSyncManager?.syncUserToCloud(activeUser, storedVerifier)
                    firestoreSyncManager?.startSync(storedId)

                    relayEngine?.start(storedId)
                    relayEngine?.broadcastPresence(storedId, true, now)
                    relayEngine?.broadcastUser(activeUser, isNew = false, authVerifier = storedVerifier)
                } else {
                    _currentUserId.value = null
                    prefs.edit()
                        .remove("logged_in_user_id")
                        .remove("logged_in_user_profile_json")
                        .apply()
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
                statusMessage = obj.optString("statusMessage", "Available on Easapp"),
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

        // 4. Check the shared cloud backend for an existing usernameNormalized
        val (firestoreExisting, relayExisting) = coroutineScope {
            val fsDeferred = async { firestoreSyncManager?.checkUsernameExistsInCloud(usernameNormalized) }
            val relayDeferred = async { relayEngine?.checkUsernameExistsInCloud(usernameNormalized) }
            fsDeferred.await() to relayDeferred.await()
        }

        if (firestoreExisting == null && relayExisting?.isFailure == true) {
            return@withContext Result.failure(
                relayExisting.exceptionOrNull() ?: IOException("Unable to reach shared backend to verify username uniqueness.")
            )
        }

        // 5. Reject the registration if it already exists on the shared backend
        if (firestoreExisting?.getOrNull() != null || relayExisting?.getOrNull() != null) {
            return@withContext Result.failure(IllegalArgumentException("Username @$usernameNormalized is already registered"))
        }

        // 6. Generate permanent backend UID and cryptographic auth verifier
        val backendUid = "uid_${UUID.randomUUID().toString().replace("-", "")}"
        val passwordHash = hashPassword(rawPassword)
        val authVerifier = computeAuthVerifier(usernameNormalized, rawPassword)
        val defaultAvatars = listOf("BRUTAL_1", "BRUTAL_2", "BRUTAL_3", "BRUTAL_4", "BRUTAL_5")
        val avatarSeed = defaultAvatars[usernameNormalized.hashCode().let { kotlin.math.abs(it) % defaultAvatars.size }]
        val now = System.currentTimeMillis()

        val newUser = UserEntity(
            id = backendUid,
            username = cleanUsername,
            usernameNormalized = usernameNormalized,
            displayName = cleanDisplayName,
            passwordHash = passwordHash,
            avatarSeed = avatarSeed,
            statusMessage = "Available on Easapp",
            isOnline = true,
            lastSeenTimestamp = now,
            createdAt = now
        )

        // Publish to the shared cloud backend first so the backend is the source of truth
        firestoreSyncManager?.syncUserToCloud(newUser, authVerifier)
        val publishedOk = relayEngine?.publishUserSync(newUser, isNew = true, authVerifier = authVerifier) ?: true
        if (!publishedOk && firestoreSyncManager?.isCloudConnected?.value != true) {
            return@withContext Result.failure(IOException("Failed to register account on the shared cloud backend. Check your connection."))
        }

        userDao.insertUser(newUser)
        firestoreSyncManager?.startSync(newUser.id)
        relayEngine?.start(newUser.id)

        setSession(newUser, authVerifier)
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

        // Authenticate against the shared cloud backend
        val (fsAuthResult, relayAuthResult) = coroutineScope {
            val fsDef = async { firestoreSyncManager?.authenticateUserInCloud(usernameNormalized, expectedVerifier) }
            val relayDef = async { relayEngine?.authenticateUserInCloud(usernameNormalized, expectedVerifier) }
            fsDef.await() to relayDef.await()
        }

        val cloudUser = relayAuthResult?.getOrNull() ?: fsAuthResult?.getOrNull()
        if (cloudUser == null) {
            val err = relayAuthResult?.exceptionOrNull()
                ?: fsAuthResult?.exceptionOrNull()
                ?: IllegalArgumentException("No registered user found with @$usernameNormalized")
            return@withContext Result.failure(err)
        }

        val now = System.currentTimeMillis()
        val updatedUser = cloudUser.copy(
            username = cleanDisplayUsername(cloudUser.username),
            usernameNormalized = normalizeUsername(cloudUser.usernameNormalized.ifBlank { cloudUser.username }),
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
        Result.success(updated)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        _currentUserId.value?.let { id ->
            userDao.updateOnlineStatus(id, false, now)
            firestoreSyncManager?.updatePresenceInCloud(id, false, now)
            relayEngine?.broadcastPresence(id, false, now)
        }
        firestoreSyncManager?.stopSync()
        relayEngine?.stop()

        _currentUserId.value = null
        _currentUser.value = null
        prefs.edit()
            .remove("logged_in_user_id")
            .remove("logged_in_user_profile_json")
            .apply()
    }

    private fun setSession(user: UserEntity, authVerifier: String) {
        _currentUserId.value = user.id
        _currentUser.value = user
        saveLocalAuthenticatedSession(user, authVerifier)
    }

    suspend fun updateProfile(displayName: String, statusMessage: String, avatarSeed: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext Result.failure(IllegalStateException("Not logged in"))
        val updated = current.copy(
            displayName = displayName.trim().ifBlank { current.displayName },
            statusMessage = statusMessage.trim().ifBlank { current.statusMessage },
            avatarSeed = avatarSeed
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
     * Never uses local sessions as the source of truth.
     */
    suspend fun searchUsersInBackend(rawQuery: String, currentUserId: String): Result<List<UserEntity>> = withContext(Dispatchers.IO) {
        if (!RealtimeManager.isNetworkConnected.value) {
            return@withContext Result.failure(IOException("Unable to search because of a network/backend error."))
        }

        val normQuery = normalizeUsername(rawQuery)
        val (firestoreResult, relayResult) = coroutineScope {
            val fsDeferred = async { firestoreSyncManager?.searchUsersInCloud(normQuery, currentUserId) }
            val relayDeferred = async { relayEngine?.searchUsersInCloud(normQuery, currentUserId) }
            fsDeferred.await() to relayDeferred.await()
        }

        val fsSuccess = firestoreResult?.isSuccess == true
        val relaySuccess = relayResult?.isSuccess == true

        if (!fsSuccess && !relaySuccess) {
            val err = relayResult?.exceptionOrNull()
                ?: firestoreResult?.exceptionOrNull()
                ?: IOException("Unable to search because of a network/backend error.")
            return@withContext Result.failure(err)
        }

        val mergedByNorm = linkedMapOf<String, UserEntity>()
        firestoreResult?.getOrNull()?.forEach { user ->
            if (user.id != currentUserId) {
                mergedByNorm[user.usernameNormalized] = user.copy(passwordHash = "")
            }
        }
        relayResult?.getOrNull()?.forEach { user ->
            if (user.id != currentUserId) {
                mergedByNorm[user.usernameNormalized] = user.copy(passwordHash = "")
            }
        }

        val sorted = mergedByNorm.values.filter { user ->
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
