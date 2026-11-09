package it.eremite.autosalute.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import android.os.Process
import it.eremite.autosalute.data.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow

/** Registra la posizione durante il viaggio (GPS del telefono). */
class GpsTracker(private val ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val buf = ArrayList<GeoPoint>()
    private var last: Location? = null
    var active = false; private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!allowed(ctx)) return false
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this, Looper.getMainLooper())
            active = true
            track.value = emptyList()
            true
        } catch (_: Exception) { false }
    }

    fun stop() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        active = false
    }

    override fun onLocationChanged(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 40) return
        val prev = last
        if (prev != null && l.distanceTo(prev) < 8 && l.time - prev.time < 30_000) return
        last = l
        val p = GeoPoint(l.time, l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6 else Double.NaN)
        synchronized(buf) { buf.add(p) }
        current.value = p
        track.value = (track.value + p).takeLast(3000)
    }

    /** Punti accumulati da salvare. */
    fun drain(): List<GeoPoint> = synchronized(buf) { val l = buf.toList(); buf.clear(); l }

    @Deprecated("vecchie versioni")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        val current = MutableStateFlow<GeoPoint?>(null)
        val track = MutableStateFlow<List<GeoPoint>>(emptyList())

        fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun background(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/** Fotografia della batteria del telefono e del lavoro fatto dall'app. */
class PhoneBattery(val level: Int, val chargeUah: Long, val charging: Boolean, val cpuMs: Long, val ts: Long) {
    companion object {
        fun now(c: Context): PhoneBattery {
            val bm = c.getSystemService(BatteryManager::class.java)
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            return PhoneBattery(
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                charging, Process.getElapsedCpuTime(), System.currentTimeMillis()
            )
        }

        /** Capacità della batteria in mAh (ricavata dal telefono, altrimenti 5000). */
        fun capacityMah(c: Context): Double {
            val b = now(c)
            return if (b.chargeUah > 0 && b.level in 5..100) b.chargeUah / 1000.0 / b.level * 100 else 5000.0
        }

        /**
         * Consumo stimato dell'app tra due fotografie (mAh).
         * CPU ~250 mA per secondo di calcolo, Bluetooth ~6 mA, GPS ~30 mA. Lo schermo non è incluso.
         */
        fun appMah(a: PhoneBattery, b: PhoneBattery, gps: Boolean): Double {
            val h = (b.ts - a.ts) / 3_600_000.0
            val cpuS = (b.cpuMs - a.cpuMs) / 1000.0
            return cpuS * 250.0 / 3600.0 + h * 6.0 + (if (gps) h * 30.0 else 0.0)
        }
    }
}
