package pl.sunsetcheck

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper

object LocationHelper {
    @SuppressLint("MissingPermission")
    fun request(ctx: Context, onResult: (Double, Double) -> Unit, onFail: () -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { onFail(); return }

        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 120_000L) {
            onResult(last.latitude, last.longitude); return
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(providers.first(), null, ctx.mainExecutor) { loc ->
                    if (loc != null) onResult(loc.latitude, loc.longitude)
                    else if (last != null) onResult(last.latitude, last.longitude)
                    else onFail()
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(providers.first(), object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        onResult(location.latitude, location.longitude)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            if (last != null) onResult(last.latitude, last.longitude) else onFail()
        }
    }
}
