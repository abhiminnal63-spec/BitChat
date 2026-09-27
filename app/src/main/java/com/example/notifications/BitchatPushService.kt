package com.example.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.example.data.database.EasappDatabase
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.realtime.RealtimeManager
import com.example.data.relay.GlobalRelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent background push receiver service that maintains BITCHAT's FCM/cloud push listener
 * even when BITCHAT is minimized, on the lock screen, or completely closed from Recent Apps.
 */
class BitchatPushService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        BitchatNotificationManager.ensureNotificationChannel(applicationContext)
        RealtimeManager.initNetworkMonitoring(applicationContext)
        startPushConnectionLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uidFromIntent = intent?.getStringExtra(EXTRA_USER_ID)?.trim()
        val activeUid = uidFromIntent?.ifBlank { null } ?: getLoggedInUserId(applicationContext)

        if (!activeUid.isNullOrBlank()) {
            val relay = GlobalRelayEngine.getInstance(applicationContext)
            val firestore = FirestoreSyncManager.getInstance(applicationContext)
            relay.start(activeUid)
            firestore.startSync(activeUid)
            BitchatPushJobService.scheduleKeepAliveJob(applicationContext)

            serviceScope.launch {
                val db = EasappDatabase.getInstance(applicationContext)
                DeviceTokenManager.registerDeviceForUser(
                    context = applicationContext,
                    userId = activeUid,
                    userDao = db.userDao(),
                    firestoreSyncManager = firestore,
                    relayEngine = relay
                )
            }
        }

        if (monitorJob?.isActive != true) {
            startPushConnectionLoop()
        }

        return START_STICKY
    }

    private fun startPushConnectionLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val uid = getLoggedInUserId(applicationContext)
                if (!uid.isNullOrBlank() && RealtimeManager.isNetworkConnected.value) {
                    try {
                        val relay = GlobalRelayEngine.getInstance(applicationContext)
                        relay.ensurePushStreamConnected(uid)
                    } catch (e: Exception) {
                        Log.w(TAG, "Background push stream check warning: ${e.message}")
                    }
                }
                delay(15_000L)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When user swipes BITCHAT away from Recent Apps, clear foreground state and keep push receiver alive
        BitchatNotificationManager.setAppInForeground(false)
        RealtimeManager.clearAllActiveConversations()

        val uid = getLoggedInUserId(applicationContext)
        if (!uid.isNullOrBlank()) {
            scheduleImmediateRestart(applicationContext, uid)
            BitchatPushJobService.scheduleKeepAliveJob(applicationContext)
        }
    }

    override fun onDestroy() {
        val uid = getLoggedInUserId(applicationContext)
        if (!uid.isNullOrBlank()) {
            scheduleImmediateRestart(applicationContext, uid)
            BitchatPushJobService.scheduleKeepAliveJob(applicationContext)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BitchatPushService"
        const val EXTRA_USER_ID = "extra_user_id"

        private fun getLoggedInUserId(context: Context): String? {
            return context.applicationContext
                .getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)
                .getString("logged_in_user_id", null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        fun ensureStarted(context: Context, userId: String? = null) {
            val appCtx = context.applicationContext
            val resolvedUid = userId?.trim()?.takeIf { it.isNotBlank() } ?: getLoggedInUserId(appCtx) ?: return
            try {
                val intent = Intent(appCtx, BitchatPushService::class.java).apply {
                    putExtra(EXTRA_USER_ID, resolvedUid)
                }
                appCtx.startService(intent)
            } catch (e: Exception) {
                // On Android 12+ if started from restricted background context, JobScheduler handles wakeup
                Log.d(TAG, "Deferred service start to BitchatPushJobService: ${e.message}")
            }
            BitchatPushJobService.scheduleKeepAliveJob(appCtx)
        }

        fun scheduleImmediateRestart(context: Context, userId: String) {
            val appCtx = context.applicationContext
            try {
                val restartIntent = Intent(appCtx, BitchatPushReceiver::class.java).apply {
                    action = BitchatPushReceiver.ACTION_RESTART_PUSH_SERVICE
                    putExtra(EXTRA_USER_ID, userId)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_ONE_SHOT
                }
                val pendingIntent = PendingIntent.getBroadcast(appCtx, 7071, restartIntent, flags)
                val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1_000L,
                    pendingIntent
                )
            } catch (_: Exception) {
            }
        }
    }
}
