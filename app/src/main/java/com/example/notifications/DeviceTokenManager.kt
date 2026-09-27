package com.example.notifications

import android.content.Context
import android.util.Log
import com.example.data.dao.UserDao
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.model.UserDeviceEntity
import com.example.data.relay.GlobalRelayEngine
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object DeviceTokenManager {
    private const val TAG = "DeviceTokenManager"
    private const val PREFS_NAME = "bitchat_device_token_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FCM_TOKEN = "fcm_token"

    // In-memory multi-device registry: userId -> (deviceId -> UserDeviceEntity)
    private val inMemoryUserDevices = ConcurrentHashMap<String, ConcurrentHashMap<String, UserDeviceEntity>>()
    private val invalidTokens = ConcurrentHashMap.newKeySet<String>()

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "android_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun getCachedFcmToken(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun saveFcmTokenLocally(context: Context, token: String) {
        val clean = token.trim()
        if (clean.isBlank()) return
        invalidTokens.remove(clean)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, clean).apply()
    }

    suspend fun fetchOrGenerateFcmToken(context: Context): String = withContext(Dispatchers.IO) {
        val cached = getCachedFcmToken(context)
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                val token = FirebaseMessaging.getInstance().token.await()
                if (!token.isNullOrBlank()) {
                    saveFcmTokenLocally(context, token)
                    return@withContext token
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token retrieval fallback: ${e.message}")
        }
        if (!cached.isNullOrBlank()) return@withContext cached
        val deviceId = getOrCreateDeviceId(context)
        val fallbackToken = "fcm_token_${deviceId}"
        saveFcmTokenLocally(context, fallbackToken)
        fallbackToken
    }

    /**
     * Registers or updates a device's FCM token under users/{uid}/devices/{deviceId}
     * supporting multiple Android devices logged into the same BITCHAT account.
     */
    suspend fun registerDeviceForUser(
        context: Context,
        userId: String,
        deviceIdOverride: String? = null,
        fcmTokenOverride: String? = null,
        userDao: UserDao? = null,
        firestoreSyncManager: FirestoreSyncManager? = null,
        relayEngine: GlobalRelayEngine? = null
    ): UserDeviceEntity? = withContext(Dispatchers.IO) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return@withContext null

        val deviceId = deviceIdOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: getOrCreateDeviceId(context)
        val token = fcmTokenOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: fetchOrGenerateFcmToken(context)

        if (invalidTokens.contains(token)) {
            return@withContext null
        }

        val now = System.currentTimeMillis()
        val entity = UserDeviceEntity(
            userId = cleanUid,
            deviceId = deviceId,
            fcmToken = token,
            platform = "android",
            updatedAt = now
        )

        val deviceMap = inMemoryUserDevices.getOrPut(cleanUid) { ConcurrentHashMap() }
        deviceMap[deviceId] = entity

        userDao?.upsertUserDevice(entity)
        firestoreSyncManager?.registerUserDeviceInCloud(entity)
        relayEngine?.publishUserDevice(entity)

        entity
    }

    suspend fun syncDeviceFromCloud(
        device: UserDeviceEntity,
        userDao: UserDao? = null
    ) {
        if (device.userId.isBlank() || device.deviceId.isBlank() || device.fcmToken.isBlank()) return
        if (invalidTokens.contains(device.fcmToken)) return
        val map = inMemoryUserDevices.getOrPut(device.userId) { ConcurrentHashMap() }
        val existing = map[device.deviceId]
        if (existing == null || device.updatedAt >= existing.updatedAt) {
            map[device.deviceId] = device
            userDao?.upsertUserDevice(device)
        }
    }

    suspend fun getRegisteredDevicesForUser(
        userId: String,
        userDao: UserDao? = null,
        firestoreSyncManager: FirestoreSyncManager? = null,
        relayEngine: GlobalRelayEngine? = null
    ): List<UserDeviceEntity> = withContext(Dispatchers.IO) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return@withContext emptyList()

        val merged = linkedMapOf<String, UserDeviceEntity>()

        inMemoryUserDevices[cleanUid]?.values?.forEach { dev ->
            if (!invalidTokens.contains(dev.fcmToken)) {
                merged[dev.deviceId] = dev
            }
        }

        userDao?.getDevicesForUserDirect(cleanUid)?.forEach { dev ->
            if (!invalidTokens.contains(dev.fcmToken)) {
                val existing = merged[dev.deviceId]
                if (existing == null || dev.updatedAt >= existing.updatedAt) {
                    merged[dev.deviceId] = dev
                }
            }
        }

        val cloudDevices = firestoreSyncManager?.fetchUserDevicesFromCloud(cleanUid) ?: emptyList()
        for (dev in cloudDevices) {
            if (!invalidTokens.contains(dev.fcmToken)) {
                val existing = merged[dev.deviceId]
                if (existing == null || dev.updatedAt >= existing.updatedAt) {
                    merged[dev.deviceId] = dev
                    userDao?.upsertUserDevice(dev)
                    inMemoryUserDevices.getOrPut(cleanUid) { ConcurrentHashMap() }[dev.deviceId] = dev
                }
            }
        }

        if (merged.isEmpty() && relayEngine != null) {
            val relayDevices = relayEngine.fetchUserDevicesFromCloud(cleanUid)
            for (dev in relayDevices) {
                if (!invalidTokens.contains(dev.fcmToken)) {
                    val existing = merged[dev.deviceId]
                    if (existing == null || dev.updatedAt >= existing.updatedAt) {
                        merged[dev.deviceId] = dev
                        userDao?.upsertUserDevice(dev)
                        inMemoryUserDevices.getOrPut(cleanUid) { ConcurrentHashMap() }[dev.deviceId] = dev
                    }
                }
            }
        }

        merged.values.sortedByDescending { it.updatedAt }
    }

    /**
     * Removes an expired or invalid FCM token when FCM reports UNREGISTERED / NotRegistered / InvalidRegistration.
     */
    suspend fun removeInvalidToken(
        userId: String,
        fcmToken: String,
        deviceId: String? = null,
        userDao: UserDao? = null,
        firestoreSyncManager: FirestoreSyncManager? = null
    ) = withContext(Dispatchers.IO) {
        val cleanToken = fcmToken.trim()
        if (cleanToken.isBlank()) return@withContext
        invalidTokens.add(cleanToken)

        val cleanUid = userId.trim()
        if (cleanUid.isNotBlank()) {
            val map = inMemoryUserDevices[cleanUid]
            if (map != null) {
                val toRemove = map.entries.filter {
                    it.value.fcmToken == cleanToken || (deviceId != null && it.key == deviceId)
                }.map { it.key }
                toRemove.forEach { devId ->
                    map.remove(devId)
                    userDao?.deleteUserDevice(cleanUid, devId)
                    firestoreSyncManager?.removeUserDeviceFromCloud(cleanUid, devId)
                }
            } else if (!deviceId.isNullOrBlank()) {
                userDao?.deleteUserDevice(cleanUid, deviceId)
                firestoreSyncManager?.removeUserDeviceFromCloud(cleanUid, deviceId)
            }
        }
        userDao?.deleteDeviceByToken(cleanToken)
    }

    fun isFcmErrorInvalidToken(errorCodeOrMessage: String?): Boolean {
        if (errorCodeOrMessage.isNullOrBlank()) return false
        val upper = errorCodeOrMessage.uppercase()
        return upper.contains("UNREGISTERED") ||
            upper.contains("NOTREGISTERED") ||
            upper.contains("NOT_REGISTERED") ||
            upper.contains("INVALID_REGISTRATION") ||
            upper.contains("INVALIDREGISTRATION") ||
            upper.contains("INVALID_ARGUMENT") ||
            upper.contains("MISMATCHED_CREDENTIAL")
    }

    fun clearCacheForTesting() {
        inMemoryUserDevices.clear()
        invalidTokens.clear()
    }
}
