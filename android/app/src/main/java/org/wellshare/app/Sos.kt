package org.wellshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/** Location and call helpers for the SOS button. Location is read only when SOS is pressed. */
object Sos {
    const val EMERGENCY_NUMBER = "112"

    fun hasLocation(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canCallDirectly(c: Context) =
        c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Calls straight away when allowed; otherwise opens the dialer with the number filled in. */
    fun callIntent(c: Context, number: String): Intent {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        // Android never lets apps auto-dial emergency numbers, so 112 always opens the dialer.
        return if (canCallDirectly(c) && number != EMERGENCY_NUMBER) Intent(Intent.ACTION_CALL, uri)
        else Intent(Intent.ACTION_DIAL, uri)
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Api.Fix? {
        if (!hasLocation(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val best = try {
            lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.elapsedRealtimeNanos }
        } catch (_: Exception) { null } ?: return null
        return best.toFix()
    }

    /** Asks for a fresh position; calls back once (null after the timeout). */
    @SuppressLint("MissingPermission")
    fun fresh(c: Context, timeoutMs: Long, done: (Api.Fix?) -> Unit) {
        if (!hasLocation(c)) { done(null); return }
        val lm = c.getSystemService(LocationManager::class.java) ?: run { done(null); return }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        val provider = providers.firstOrNull() ?: run { done(null); return }

        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            done(l?.toFix())
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                lm.getCurrentLocation(provider, cancel, Executors.newSingleThreadExecutor()) { finish(it) }
                main.postDelayed({ cancel.cancel(); finish(null) }, timeoutMs)
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { lm.removeUpdates(this); finish(l) }
                    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                main.postDelayed({ lm.removeUpdates(listener); finish(null) }, timeoutMs)
            }
        } catch (_: Exception) {
            finish(null)
        }
    }

    private fun Location.toFix(): Api.Fix {
        val ageSec = ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000_000L).toInt().coerceAtLeast(0)
        return Api.Fix(latitude, longitude, if (hasAccuracy()) accuracy else 0f, ageSec)
    }
}
