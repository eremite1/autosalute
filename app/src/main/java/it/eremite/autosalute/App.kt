package it.eremite.autosalute

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.eremite.autosalute.health.Finding
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Collegamento auto", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Allarmi urgenti", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_TRIP, "Resoconto viaggi", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CH_SERVICE = "servizio"
        const val CH_ALARM = "allarmi"
        const val CH_TRIP = "viaggi"

        @SuppressLint("MissingPermission")
        fun notify(ctx: Context, ch: String, id: Int, title: String, text: String) {
            val n = NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(if (ch == CH_ALARM) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(Prefs.openAppIntent(ctx))
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
        }
    }
}

/** Stato condiviso tra il servizio in sottofondo e lo schermo. */
object Live {
    val status = MutableStateFlow("Non collegato")
    val connected = MutableStateFlow(false)
    val values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val alerts = MutableStateFlow<List<Finding>>(emptyList())
    val tripsVersion = MutableStateFlow(0)
    val logLines = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    fun log(s: String) {
        val l = "${fmt.format(Date())}  $s"
        logLines.value = (listOf(l) + logLines.value).take(80)
    }
}
