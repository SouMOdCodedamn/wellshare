package org.wellshare.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Sync {
    private const val JOB_ID = 4201
    private val PERIOD_MS = TimeUnit.HOURS.toMillis(1)
    private val FLEX_MS = TimeUnit.MINUTES.toMillis(15)

    /** Android keeps roughly 1–2 weeks of usage history, so we always resend the last 7 days. */
    const val DAYS_TO_SEND = 7

    /** Collects the last 7 days and uploads them. Returns true on success. Runs on a background thread. */
    @Synchronized
    fun run(context: Context): Boolean {
        val prefs = Prefs(context)
        val token = prefs.token ?: return true
        val device = prefs.deviceHash ?: return true
        val usageOn = UsageCollector.hasUsageAccess(context)
        val status = JSONObject()
            .put("usage_access", usageOn)
            .put("watcher", Watcher.isEnabled(context))
            .put("notifications", Notify.allowed(context))
        return try {
            sendPendingSos(context, prefs, token, device)
            // Even with usage access off we still check in, so the mentor sees what is switched off.
            val days = if (usageOn) UsageCollector.collect(context, DAYS_TO_SEND) else emptyList()
            val store = SitesStore(context)
            val sites = days.associate { it.day.toString() to store.top(it.day, 10) }
            val r = Api.upload(token, device, days, sites, status, BuildConfig.VERSION_NAME)
            prefs.limits = r.limits
            prefs.sosPhone = r.sosPhone
            r.groupName?.let { prefs.groupName = it }
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastError = if (usageOn) null else "Usage access is turned off. Open WellShare to turn it back on."
            Notify.showSharing(context)
            true
        } catch (e: Api.ApiError) {
            prefs.lastError = e.message
            if (e.code == "INVALID_TOKEN" || e.code == "DEVICE_MISMATCH") {
                prefs.clearRegistration()
                prefs.lastError = e.message
                Notify.cancel(context)
                cancel(context)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            prefs.lastError = "Sync failed: ${e.javaClass.simpleName}"
            false
        }
    }

    /** An SOS pressed while offline is still delivered, if it is less than 30 minutes old. */
    private fun sendPendingSos(context: Context, prefs: Prefs, token: String, device: String) {
        val at = prefs.pendingSosAt
        if (at == 0L) return
        if (System.currentTimeMillis() - at < TimeUnit.MINUTES.toMillis(30)) {
            try {
                Api.sendSos(token, device, Sos.lastKnown(context))
            } catch (e: Api.ApiError) {
                if (e.code == "NETWORK") return
            }
        }
        prefs.pendingSosAt = 0L
    }

    /** About every hour when online; survives reboots. Replaces older, slower schedules. */
    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = js.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == PERIOD_MS) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIOD_MS, FLEX_MS)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = Sync.run(applicationContext)
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
