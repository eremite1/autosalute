package it.eremite.autosalute.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import it.eremite.autosalute.App
import it.eremite.autosalute.Live
import it.eremite.autosalute.Prefs
import it.eremite.autosalute.R
import it.eremite.autosalute.Updater
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.Sample
import it.eremite.autosalute.data.Trip
import it.eremite.autosalute.health.Finding
import it.eremite.autosalute.health.Health
import it.eremite.autosalute.health.TripAcc
import it.eremite.autosalute.obd.*
import kotlinx.coroutines.*
import java.io.IOException

class ObdService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel(); stopSelf(); return START_NOT_STICKY
        }
        startForeground(1, note("Cerco il lettore OBD2…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        running = true
        if (job?.isActive != true) job = scope.launch { loop() }
        return if (Prefs.sentinel(this)) START_STICKY else START_NOT_STICKY
    }

    private fun note(text: String): Notification =
        NotificationCompat.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AutoSalute")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(Prefs.openAppIntent(this))
            .build()

    private fun update(text: String) {
        Live.status.value = text
        getSystemService(android.app.NotificationManager::class.java).notify(1, note(text))
    }

    private suspend fun loop() {
        val mac = Prefs.mac(this) ?: run { Live.log("Nessun lettore associato"); stopSelf(); return }
        var tries = 0
        while (currentCoroutineContext().isActive) {
            val ok = try { session(mac) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Live.log("Errore: ${e.message}"); false
            }
            Live.connected.value = false
            if (ok) tries = 0 else tries++
            if (Prefs.sentinel(this)) {
                update(if (ok) "Viaggio salvato. In attesa dell'auto" else "In attesa dell'auto")
                delay(if (ok) 90_000 else 25_000)
            } else {
                if (ok || tries >= 6) {
                    if (!ok) Prefs.setLastFail(this, System.currentTimeMillis())
                    break
                }
                update("Riprovo a collegarmi…"); delay(8_000)
            }
        }
        Live.status.value = "Non collegato"
        try {
            Updater.check(this, quiet = true, minGapMs = 12 * 3600_000L)?.let {
                App.notify(this, App.CH_TRIP, 60, "Aggiornamento AutoSalute disponibile",
                    "Versione 1.0.${it.code}. Apri l'app e premi \"Scarica e installa\".")
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    /** Un viaggio completo. Restituisce true se è riuscito a collegarsi. */
    @SuppressLint("WakelockTimeout")
    private suspend fun session(mac: String): Boolean {
        update("Collegamento al lettore…")
        val (link, type) = try { openLink(this, mac, Prefs.type(this)) } catch (e: IOException) {
            Live.log("Lettore non raggiungibile: ${e.message}"); return false
        }
        if (Prefs.type(this) == "auto") Prefs.setType(this, type)
        Live.log("Collegato via ${if (type == "ble") "Bluetooth LE" else "Bluetooth classico"}")
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autosalute:viaggio").apply { acquire() }
        try {
            val elm = Elm(link)
            update("Dialogo con la centralina…")
            if (!elm.init()) { Live.log("La centralina non risponde (quadro spento?)"); return false }
            Live.connected.value = true
            update("Collegato all'auto: sto registrando")
            Recorder(this, elm).run()
            return true
        } finally {
            link.close()
            wake?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        Live.connected.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile var running = false

        fun start(ctx: Context, reason: String) {
            if (running || Prefs.mac(ctx) == null) return
            if (reason != "manuale" && System.currentTimeMillis() - Prefs.lastFail(ctx) < 120_000) return
            try {
                ctx.startForegroundService(Intent(ctx, ObdService::class.java))
                Live.log("Avvio servizio ($reason)")
            } catch (e: Exception) { Live.log("Avvio non consentito: ${e.message}") }
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, ObdService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
        }
    }
}

/** Registra i dati del viaggio e controlla gli allarmi in tempo reale. */
class Recorder(private val ctx: Context, private val elm: Elm) {
    private val db = Db.get(ctx)
    private val acc = TripAcc()
    private val sent = HashSet<String>()
    private val liveFindings = ArrayList<Finding>()
    private var lowVoltSince = 0L

    suspend fun run() {
        val sup = elm.supported()
        Live.log("La centralina fornisce ${sup.size} parametri")
        val start = System.currentTimeMillis()
        val tripId = db.startTrip(start)
        try { acc.dtcs.addAll(elm.dtcs("03")); acc.pending.addAll(elm.dtcs("07")) } catch (e: IOException) { throw e } catch (_: Exception) {}
        elm.monitor()?.let { acc.mil = it.first }
        if (acc.dtcs.isNotEmpty() || acc.mil) alarm("dtc", "Codici errore presenti", "La centralina ha memorizzato: ${acc.dtcs.joinToString()}. Apri l'app per i dettagli.", 2, "Codici errore")
        else if (acc.pending.isNotEmpty()) alarm("pend", "Problema in sospeso", "Codici in sospeso: ${acc.pending.joinToString()}. Potrebbe essere l'inizio di un guasto.", 1, "Codici errore")

        val fast = PIDS.filter { it.pid in FAST && it.pid in sup }
        val slow = PIDS.filter { it.pid !in FAST && it.pid in sup }
        val values = HashMap<String, Double>()
        val buf = ArrayList<Sample>()
        var cycle = 0; var fails = 0; var lastSample = 0L; var lastT = System.currentTimeMillis()
        Live.alerts.value = emptyList()

        try {
            while (currentCoroutineContext().isActive) {
                var got = 0
                for (p in fast) elm.read(p)?.let { values[p.key] = it; got++ }
                if (cycle % 8 == 0) for (p in slow) elm.read(p)?.let { values[p.key] = it; got++ }
                if (cycle % 4 == 0 || !acc.engineStarted) elm.voltage()?.let { values["volt"] = it; got++ }
                if (cycle > 0 && cycle % 500 == 0) checkCodes()
                if (got == 0) { fails++; if (fails >= 4) { Live.log("La centralina non risponde più"); break }; delay(700); continue }
                fails = 0
                val m = values["map"]; val b = values["baro"]
                if (m != null && b != null) values["boost"] = (m - b) / 100.0
                val now = System.currentTimeMillis()
                acc.add(values, (now - lastT) / 1000.0, now); lastT = now
                Live.values.value = HashMap(values)
                liveChecks(values, now)
                if (now - lastSample >= 1000 && acc.engineStarted) {
                    fun v(k: String) = values[k] ?: Double.NaN
                    buf.add(Sample(now, v("rpm"), v("speed"), v("load"), v("coolant"), v("intake"), v("maf"), v("boost"), v("rail"), v("fuel_rate"), v("volt"), v("oil")))
                    lastSample = now
                    if (buf.size >= 30) { db.addSamples(tripId, buf); buf.clear() }
                }
                cycle++
            }
        } catch (e: IOException) {
            Live.log("Collegamento chiuso: ${e.message}")
        } finally {
            finish(tripId, start, buf)
        }
    }

    private suspend fun checkCodes() {
        val d = elm.dtcs("03"); val p = elm.dtcs("07")
        val newD = d.filter { it !in acc.dtcs }; val newP = p.filter { it !in acc.pending }
        acc.dtcs.addAll(d); acc.pending.addAll(p)
        if (newD.isNotEmpty()) alarm("dtc_" + newD.joinToString(), "Nuovo codice errore", "Durante la guida: ${newD.joinToString()}", 2, "Codici errore")
        if (newP.isNotEmpty()) alarm("pend_" + newP.joinToString(), "Nuovo problema in sospeso", newP.joinToString(), 1, "Codici errore")
        elm.monitor()?.let { if (it.first && !acc.mil) { acc.mil = true; alarm("mil", "Spia motore accesa", "La centralina ha acceso la spia motore.", 2, "Codici errore") } }
    }

    private fun liveChecks(v: Map<String, Double>, now: Long) {
        val c = v["coolant"]; val volt = v["volt"]; val rpm = v["rpm"] ?: 0.0
        if (c != null && c >= 108) alarm("hot", "Motore troppo caldo: ${c.toInt()}°C", "Accosta appena puoi e lascia il motore al minimo. Controlla il liquido di raffreddamento a motore freddo.", 2, "Raffreddamento")
        v["oil"]?.let { if (it >= 132) alarm("oil", "Olio molto caldo: ${it.toInt()}°C", "Riduci l'andatura.", 1, "Motore") }
        if (volt != null && rpm > 800) {
            if (volt < 12.6) { if (lowVoltSince == 0L) lowVoltSince = now } else lowVoltSince = 0L
            if (lowVoltSince > 0 && now - lowVoltSince > 40_000)
                alarm("alt", "Alternatore non carica (${"%.1f".format(volt)} V)", "La batteria si sta scaricando con motore acceso: controlla alternatore e cinghia.", 2, "Batteria e alternatore")
            if (volt > 15.3) alarm("over", "Tensione troppo alta (${"%.1f".format(volt)} V)", "Possibile guasto del regolatore dell'alternatore.", 1, "Batteria e alternatore")
        }
    }

    private fun alarm(key: String, title: String, text: String, sev: Int, cat: String) {
        if (!sent.add(key)) return
        liveFindings.add(Finding(cat, sev, title, text))
        Live.alerts.value = liveFindings.toList()
        App.notify(ctx, if (sev >= 2) App.CH_ALARM else App.CH_TRIP, 100 + sent.size, title, text)
        Live.log("Allarme: $title")
    }

    private fun finish(tripId: Long, start: Long, buf: List<Sample>) {
        try {
            if (buf.isNotEmpty()) db.addSamples(tripId, buf)
            if (!acc.engineStarted && acc.dtcs.isEmpty()) { db.deleteTrip(tripId); Live.log("Quadro acceso senza avviare: niente viaggio"); return }
            val metrics = acc.metrics()
            val hist = db.trips(60).filter { it.id != tripId }.map { it.metrics }
            val findings = (Health.evaluate(metrics, hist, acc.dtcs.toList(), acc.pending.toList(), acc.mil) +
                liveFindings.filter { it.cat != "Codici errore" }).distinctBy { it.title }
            val score = Health.score(findings)
            db.finishTrip(
                Trip(tripId, start, System.currentTimeMillis(), acc.km, acc.dur, acc.avgSpeed, acc.maxSpeed,
                    if (acc.fuelKnown) acc.fuel else Double.NaN, acc.cons, score, metrics, findings,
                    (acc.dtcs + acc.pending.map { "$it (in sospeso)" }).toList())
            )
            db.prune()
            Live.tripsVersion.value++
            val warn = findings.count { it.sev >= 1 }
            App.notify(
                ctx, App.CH_TRIP, 50, "Viaggio salvato · salute $score/100",
                "${"%.1f".format(acc.km)} km in ${(acc.dur / 60).toInt()} min. " +
                    if (warn == 0) "Tutto regolare." else "$warn cose da controllare: apri l'app."
            )
        } catch (e: Exception) { Live.log("Errore salvataggio: ${e.message}") }
    }
}

/** Android ci sveglia quando il lettore (associato) è nelle vicinanze. */
class PresenceService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Live.log("Il telefono vede il lettore OBD2")
        ObdService.start(this, "lettore rilevato")
    }
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {}
}

/** Riattiva la sorveglianza dopo un riavvio del telefono. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Watch.arm(ctx)
        if (Prefs.sentinel(ctx)) ObdService.start(ctx, "riavvio")
    }
}

/** Sveglia di riserva: ricerca Bluetooth LE a basso consumo del lettore. */
class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ObdService.start(ctx, "segnale Bluetooth")
    }
}

object Watch {
    @SuppressLint("MissingPermission")
    fun arm(ctx: Context) {
        val mac = Prefs.mac(ctx) ?: return
        try {
            val cdm = ctx.getSystemService(android.companion.CompanionDeviceManager::class.java)
            @Suppress("DEPRECATION") cdm?.startObservingDevicePresence(mac)
        } catch (e: Exception) { Live.log("Sorveglianza di sistema non disponibile: ${e.message}") }
        if (Prefs.type(ctx) != "classic") try {
            val sc = ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return
            val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
            val set = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            sc.startScan(listOf(filter), set, pending(ctx))
        } catch (e: Exception) { Live.log("Ricerca di riserva non attiva: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    fun disarm(ctx: Context) {
        val mac = Prefs.mac(ctx) ?: return
        try {
            val cdm = ctx.getSystemService(android.companion.CompanionDeviceManager::class.java)
            @Suppress("DEPRECATION") cdm?.stopObservingDevicePresence(mac)
        } catch (_: Exception) {}
        try { ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(pending(ctx)) } catch (_: Exception) {}
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, BleScanReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}
