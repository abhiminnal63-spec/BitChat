package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.relay.GlobalRelayEngine

class BitchatPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        BitchatNotificationManager.ensureNotificationChannel(appCtx)

        val prefs = appCtx.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)
        val loggedInUid = intent?.getStringExtra(BitchatPushService.EXTRA_USER_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("logged_in_user_id", null)?.trim()
            ?: return

        GlobalRelayEngine.getInstance(appCtx).start(loggedInUid)
        FirestoreSyncManager.getInstance(appCtx).startSync(loggedInUid)
        BitchatPushService.ensureStarted(appCtx, loggedInUid)
        BitchatPushJobService.scheduleKeepAliveJob(appCtx)
    }

    companion object {
        const val ACTION_RESTART_PUSH_SERVICE = "com.example.bitchat.ACTION_RESTART_PUSH_SERVICE"
    }
}
