package org.wellshare.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

object Sync {
    private const val JOB_ID = 4201
    /** Android keeps roughly 1–2 weeks of usage history, so we always resend the last 7 days. */
    const val DAYS_TO_SEND = 7

    /** Collects the last 7 days and uploads them. Returns true on success. Runs on a background thread. */
    @Synchronized
    fun run(context: Context): Boolean {
        val prefs = Prefs(context)
        val token = prefs.token ?: return true
        val device = prefs.deviceHash ?: return true
        if (!UsageCollector.hasUsageAccess(context)) {
            prefs.lastError = "Usage access is turned off. Open the app to turn it back on."
            return true
        }
        return try {
            val days = UsageCollector.collect(context, DAYS_TO_SEND)
            Api.upload(token, device, days, BuildConfig.VERSION_NAME)
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastError = null
            true
        } catch (e: Api.ApiError) {
            prefs.lastError = e.message
            if (e.code == "INVALID_TOKEN" || e.code == "DEVICE_MISMATCH") {
                prefs.clearRegistration()
                prefs.lastError = e.message
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

    /** Every ~4 hours when online; survives reboots. */
    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.HOURS.toMillis(4), TimeUnit.HOURS.toMillis(1))
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
