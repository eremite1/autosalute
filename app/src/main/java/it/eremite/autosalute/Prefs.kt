package it.eremite.autosalute

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import it.eremite.autosalute.ui.MainActivity

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("autosalute", Context.MODE_PRIVATE)

    fun mac(c: Context): String? = p(c).getString("mac", null)
    fun name(c: Context): String = p(c).getString("name", null) ?: "Lettore OBD2"
    fun type(c: Context): String = p(c).getString("type", "auto") ?: "auto"
    fun sentinel(c: Context): Boolean = p(c).getBoolean("sentinel", false)
    fun lastFail(c: Context): Long = p(c).getLong("lastFail", 0)
    fun autostart(c: Context): Boolean = p(c).getBoolean("autostart", true)
    fun setAutostart(c: Context, v: Boolean) = p(c).edit().putBoolean("autostart", v).apply()

    fun setDevice(c: Context, mac: String, name: String?, type: String) =
        p(c).edit().putString("mac", mac).putString("name", name).putString("type", type).apply()
    fun setType(c: Context, t: String) = p(c).edit().putString("type", t).apply()
    fun setSentinel(c: Context, v: Boolean) = p(c).edit().putBoolean("sentinel", v).apply()
    fun setLastFail(c: Context, t: Long) = p(c).edit().putLong("lastFail", t).apply()

    fun openAppIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
