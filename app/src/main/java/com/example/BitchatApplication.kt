package com.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.realtime.RealtimeManager
import com.example.data.relay.GlobalRelayEngine
import com.example.notifications.BitchatNotificationManager
import com.example.notifications.BitchatPushJobService
import com.example.notifications.BitchatPushService
import java.util.concurrent.atomic.AtomicInteger

class BitchatApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        BitchatNotificationManager.ensureNotificationChannel(applicationContext)
        RealtimeManager.initNetworkMonitoring(applicationContext)
        // On cold process start (which may be triggered in the background by FCM, JobScheduler, or BroadcastReceiver),
        // mark app as backgrounded until an Activity actually starts.
        BitchatNotificationManager.setAppInForeground(startedActivityCount.get() > 0)
        registerActivityLifecycleCallbacks(this)

        val prefs = applicationContext.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)
        val loggedInUid = prefs.getString("logged_in_user_id", null)?.trim()
        if (!loggedInUid.isNullOrBlank()) {
            // Start background push engine even when MainActivity is not open
            GlobalRelayEngine.getInstance(applicationContext).start(loggedInUid)
            FirestoreSyncManager.getInstance(applicationContext).startSync(loggedInUid)
            BitchatPushService.ensureStarted(applicationContext, loggedInUid)
            BitchatPushJobService.scheduleKeepAliveJob(applicationContext)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        val count = startedActivityCount.incrementAndGet()
        if (count >= 1) {
            BitchatNotificationManager.setAppInForeground(true)
            FirestoreSyncManager.getInstance(applicationContext).onAppForegrounded()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        BitchatNotificationManager.setAppInForeground(true)
        FirestoreSyncManager.getInstance(applicationContext).onAppForegrounded()
    }

    override fun onActivityPaused(activity: Activity) {
        if (!BitchatNotificationManager.isDeviceInteractiveAndUnlocked(applicationContext)) {
            BitchatNotificationManager.setAppInForeground(false)
            RealtimeManager.clearAllActiveConversations()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val count = startedActivityCount.decrementAndGet().coerceAtLeast(0)
        startedActivityCount.set(count)
        if (count == 0) {
            // App is now minimized, on lock screen, or in background:
            // Mark app not in foreground and clear active foreground conversation so Android notifications appear in shade
            BitchatNotificationManager.setAppInForeground(false)
            RealtimeManager.clearAllActiveConversations()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private val startedActivityCount = AtomicInteger(0)

        val isAnyActivityStarted: Boolean
            get() = startedActivityCount.get() > 0
    }
}
