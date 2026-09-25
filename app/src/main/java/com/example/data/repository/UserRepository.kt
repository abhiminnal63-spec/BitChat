package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.dao.UserDao
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.model.UserEntity
import com.example.data.relay.GlobalRelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                    _currentUser.value = user.copy(isOnline = true, lastSeenTimestamp = now)
                    firestoreSyncManager?.updatePresenceInCloud(storedId, true, now)
                    firestoreSyncManager?.startSync(storedId)

                    // Connect cross-device global real-time relay
                    relayEngine?.start(storedId)
                    relayEngine?.broadcastPresence(storedId, true, now)
                    relayEngine?.broadcastUser(user, isNew = false)
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
        val cleanUsername = rawUsername.trim().lowercase().removePrefix("@")
        val cleanDisplayName = displayName.trim()

        if (cleanUsername.length < 3) {
            return@withContext Result.failure(IllegalArgumentException("Username must be at least 3 characters"))
        }
        if (!cleanUsername.matches(Regex("^[a-z0-9_.]+$"))) {
            return@withContext Result.failure(IllegalArgumentException("Username can only contain letters, numbers, underscores, and dots"))
        }
        if (cleanDisplayName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Display name cannot be blank"))
        }
        if (rawPassword.length < 4) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 4 characters"))
        }

        val existing = userDao.getUserByUsername(cleanUsername)
        if (existing != null) {
            return@withContext Result.failure(IllegalArgumentException("Username @$cleanUsername is already registered"))
        }

        val id = UUID.randomUUID().toString()
        val passwordHash = hashPassword(rawPassword)
        val defaultAvatars = listOf("BRUTAL_1", "BRUTAL_2", "BRUTAL_3", "BRUTAL_4", "BRUTAL_5")
        val avatarSeed = defaultAvatars[cleanUsername.hashCode().let { kotlin.math.abs(it) % defaultAvatars.size }]
        val now = System.currentTimeMillis()

        val newUser = UserEntity(
            id = id,
            username = cleanUsername,
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

        // Broadcast to global real-time relay for instant Phone A <-> Phone B discovery
        relayEngine?.start(newUser.id)
        relayEngine?.broadcastUser(newUser, isNew = true)

        setSession(newUser)
        Result.success(newUser)
    }

    suspend fun loginUser(rawUsername: String, rawPassword: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val cleanUsername = rawUsername.trim().lowercase().removePrefix("@")
        if (cleanUsername.isBlank() || rawPassword.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter username and password"))
        }

        val user = userDao.getUserByUsername(cleanUsername)
            ?: return@withContext Result.failure(IllegalArgumentException("No user found with @$cleanUsername"))

        val expectedHash = hashPassword(rawPassword)
        if (user.passwordHash != expectedHash) {
            return@withContext Result.failure(IllegalArgumentException("Incorrect password"))
        }

        val now = System.currentTimeMillis()
        userDao.updateOnlineStatus(user.id, true, now)
        val updatedUser = user.copy(isOnline = true, lastSeenTimestamp = now)

        firestoreSyncManager?.updatePresenceInCloud(user.id, true, now)
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
        val updated = targetUser.copy(isOnline = true, lastSeenTimestamp = now)

        firestoreSyncManager?.updatePresenceInCloud(userId, true, now)
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
        relayEngine?.broadcastUser(updated, isNew = false)
        _currentUser.value = updated
        Result.success(updated)
    }

    fun searchUsers(query: String, currentUserId: String): Flow<List<UserEntity>> {
        return userDao.searchUsers(query, currentUserId)
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
