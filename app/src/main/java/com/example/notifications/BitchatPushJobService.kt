package com.example.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.example.data.firestore.FirestoreSyncManager
import com.example.data.relay.GlobalRelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System JobService that wakes up when network connectivity is available even if BITCHAT was closed,
 * synchronizes any pending FCM/backend messages, and keeps the push stream active.
 */
class BitchatPushJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = applicationContext.getSharedPreferences("easapp_session_prefs", Context.MODE_PRIVATE)
        val loggedInUid = prefs.getString("logged_in_user_id", null)?.trim()
        if (loggedInUid.isNullOrBlank()) {
            jobFinished(params, false)
            return false
        }

        BitchatNotificationManager.ensureNotificationChannel(applicationContext)
        val relay = GlobalRelayEngine.getInstance(applicationContext)
        val firestore = FirestoreSyncManager.getInstance(applicationContext)
        relay.start(loggedInUid)
        firestore.startSync(loggedInUid)
        BitchatPushService.ensureStarted(applicationContext, loggedInUid)

        jobScope.launch {
            try {
                firestore.synchronizeOfflineMessagesForUser(loggedInUid)
                relay.synchronizeOfflineMessagesForUser(loggedInUid)
            } catch (_: Exception) {
            } finally {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        jobScope.cancel()
        return true
    }

    companion object {
        private const val JOB_ID_PUSH_KEEPALIVE = 7072

        fun scheduleKeepAliveJob(context: Context) {
            try {
                val appCtx = context.applicationContext
                val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                if (scheduler.allPendingJobs.any { it.id == JOB_ID_PUSH_KEEPALIVE }) return

                val component = ComponentName(appCtx, BitchatPushJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_PUSH_KEEPALIVE, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(5_000L)
                    .setOverrideDeadline(30_000L)
                    .setBackoffCriteria(5_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (_: Exception) {
            }
        }
    }
}
