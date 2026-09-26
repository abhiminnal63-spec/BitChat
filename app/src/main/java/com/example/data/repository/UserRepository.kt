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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

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

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // Load initial user if stored
        val storedId = _currentUserId.value
        if (storedId != null) {
            scope.launch {
                val user = userDao.getUserByIdDirect(storedId)
                if (user != null) {
                    val now = System.currentTimeMillis()
                    userDao.updateOnlineStatus(storedId, true, now)
                    val activeUser = user.copy(
                        username = cleanDisplayUsername(user.username),
                        usernameNormalized = normalizeUsername(user.usernameNormalized.ifBlank { user.username }),
                        isOnline = true,
                        lastSeenTimestamp = now
                    )
                    _currentUser.value = activeUser
                    firestoreSyncManager?.updatePresenceInCloud(storedId, true, now)
                    firestoreSyncManager?.syncUserToCloud(activeUser)
                    firestoreSyncManager?.startSync(storedId)

                    // Connect cross-device global real-time relay
                    relayEngine?.start(storedId)
                    relayEngine?.broadcastPresence(storedId, true, now)
                    relayEngine?.broadcastUser(activeUser, isNew = false)
                } else {
                    _currentUserId.value = null
                    prefs.edit().remove("logged_in_user_id").apply()
                }
            }
        }
    }

    suspend fun registerUser(
        rawUsername: String,
        displayName: String,
        rawPassword: String
    ): Result<UserEntity> = withContext(Dispatchers.IO) {
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

        // 4. Check the shared backend & local cache for an existing usernameNormalized
        val existingLocal = userDao.getUserByUsername(usernameNormalized)
        if (existingLocal != null) {
            return@withContext Result.failure(IllegalArgumentException("Username @$usernameNormalized is already registered"))
        }

        val (firestoreExisting, relayExisting) = coroutineScope {
            val fsDeferred = async { firestoreSyncManager?.checkUsernameExistsInCloud(usernameNormalized) }
            val relayDeferred = async { relayEngine?.checkUsernameExistsInCloud(usernameNormalized) }
            fsDeferred.await() to relayDeferred.await()
        }

        // 5. Reject the registration if it already exists on the shared backend
        if (firestoreExisting?.getOrNull() != null || relayExisting?.getOrNull() != null) {
            return@withContext Result.failure(IllegalArgumentException("Username @$usernameNormalized is already registered"))
        }

        val existingAfterSync = userDao.getUserByUsername(usernameNormalized)
        if (existingAfterSync != null) {
            return@withContext Result.failure(IllegalArgumentException("Username @$usernameNormalized is already registered"))
        }

        // 6. Store the authenticated user's UID with the profile
        val id = UUID.randomUUID().toString()
        val passwordHash = hashPassword(rawPassword)
        val defaultAvatars = listOf("BRUTAL_1", "BRUTAL_2", "BRUTAL_3", "BRUTAL_4", "BRUTAL_5")
        val avatarSeed = defaultAvatars[usernameNormalized.hashCode().let { kotlin.math.abs(it) % defaultAvatars.size }]
        val now = System.currentTimeMillis()

        val newUser = UserEntity(
            id = id,
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

        userDao.insertUser(newUser)
        firestoreSyncManager?.syncUserToCloud(newUser)
        firestoreSyncManager?.startSync(newUser.id)

        // Synchronously publish to global real-time relay so peer devices can find the new user immediately
        relayEngine?.start(newUser.id)
        relayEngine?.publishUserSync(newUser, isNew = true)

        setSession(newUser)
        Result.success(newUser)
    }

    suspend fun loginUser(rawUsername: String, rawPassword: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val usernameNormalized = normalizeUsername(rawUsername)
        if (usernameNormalized.isBlank() || rawPassword.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter username and password"))
        }

        var user = userDao.getUserByUsername(usernameNormalized)
        if (user == null) {
            // Query shared backend in case user was registered earlier
            firestoreSyncManager?.checkUsernameExistsInCloud(usernameNormalized)
            relayEngine?.checkUsernameExistsInCloud(usernameNormalized)
            user = userDao.getUserByUsername(usernameNormalized)
        }

        if (user == null) {
            return@withContext Result.failure(IllegalArgumentException("No user found with @$usernameNormalized"))
        }

        val expectedHash = hashPassword(rawPassword)
        if (user.passwordHash.isNotBlank() && user.passwordHash != expectedHash) {
            return@withContext Result.failure(IllegalArgumentException("Incorrect password"))
        }

        val now = System.currentTimeMillis()
        userDao.updateOnlineStatus(user.id, true, now)
        val updatedUser = user.copy(
            username = cleanDisplayUsername(user.username),
            usernameNormalized = normalizeUsername(user.usernameNormalized.ifBlank { user.username }),
            isOnline = true,
            lastSeenTimestamp = now
        )

        firestoreSyncManager?.updatePresenceInCloud(user.id, true, now)
        firestoreSyncManager?.syncUserToCloud(updatedUser)
        firestoreSyncManager?.startSync(user.id)

        relayEngine?.start(user.id)
        relayEngine?.broadcastPresence(user.id, true, now)
        relayEngine?.broadcastUser(updatedUser, isNew = false)

        setSession(updatedUser)
        Result.success(updatedUser)
    }

    suspend fun switchUser(userId: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Mark old user as offline
        _currentUserId.value?.let { oldId ->
            userDao.updateOnlineStatus(oldId, false, now)
            firestoreSyncManager?.updatePresenceInCloud(oldId, false, now)
            relayEngine?.broadcastPresence(oldId, false, now)
        }

        val targetUser = userDao.getUserByIdDirect(userId)
            ?: return@withContext Result.failure(IllegalArgumentException("User not found"))

        userDao.updateOnlineStatus(userId, true, now)
        val updated = targetUser.copy(
            username = cleanDisplayUsername(targetUser.username),
            usernameNormalized = normalizeUsername(targetUser.usernameNormalized.ifBlank { targetUser.username }),
            isOnline = true,
            lastSeenTimestamp = now
        )

        firestoreSyncManager?.updatePresenceInCloud(userId, true, now)
        firestoreSyncManager?.syncUserToCloud(updated)
        firestoreSyncManager?.startSync(userId)

        relayEngine?.start(userId)
        relayEngine?.broadcastPresence(userId, true, now)
        relayEngine?.broadcastUser(updated, isNew = false)

        setSession(updated)
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
        prefs.edit().remove("logged_in_user_id").apply()
    }

    private fun setSession(user: UserEntity) {
        _currentUserId.value = user.id
        _currentUser.value = user
        prefs.edit().putString("logged_in_user_id", user.id).apply()
    }

    suspend fun updateProfile(displayName: String, statusMessage: String, avatarSeed: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext Result.failure(IllegalStateException("Not logged in"))
        val updated = current.copy(
            displayName = displayName.trim().ifBlank { current.displayName },
            statusMessage = statusMessage.trim().ifBlank { current.statusMessage },
            avatarSeed = avatarSeed
        )
        userDao.updateUser(updated)
        firestoreSyncManager?.syncUserToCloud(updated)
        relayEngine?.publishUserSync(updated, isNew = false)
        _currentUser.value = updated
        Result.success(updated)
    }

    /**
     * Queries the shared cloud backend (Firestore + GlobalRelayEngine) in real time using normalized username.
     * Supports queries with or without '@' and any capitalization (e.g., "@alice", "alice", "ALICE", " @Alice ").
     * Returns Result.failure when offline or when the backend request fails so the UI can distinguish
     * between "No registered user found" and "Unable to search because of a network/backend error."
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

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
