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
import it.eremite.autosalute.data.Backup
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Maint
import it.eremite.autosalute.data.MaintNotify
import it.eremite.autosalute.data.Sample
import it.eremite.autosalute.data.Trip
import it.eremite.autosalute.health.Finding
import it.eremite.autosalute.health.Health
import it.eremite.autosalute.health.Dtc
import it.eremite.autosalute.health.FapAcc
import it.eremite.autosalute.health.TripAcc
import it.eremite.autosalute.health.Eco
import it.eremite.autosalute.health.EcoAcc
import it.eremite.autosalute.health.Warmup
import it.eremite.autosalute.health.WarmState
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
        val n = note("Cerco il lettore OBD2…")
        try {
            val type = if (GpsTracker.allowed(this))
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(1, n, type)
        } catch (e: Exception) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
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
            Live.log("Lettore non raggiungibile: ${e.message}")
            DiagCtl.fail("Non riesco a collegarmi al lettore OBD2. Controlla che sia inserito e che il quadro sia acceso, poi riprova.")
            return false
        }
        if (Prefs.type(this) == "auto") Prefs.setType(this, type)
        Live.log("Collegato via ${if (type == "ble") "Bluetooth LE" else "Bluetooth classico"}")
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autosalute:viaggio").apply { acquire() }
        try {
            val elm = Elm(link)
            update("Dialogo con la centralina…")
            if (!elm.init()) {
                Live.log("La centralina non risponde (quadro spento?)")
                DiagCtl.fail("Il lettore è collegato ma la centralina non risponde: gira la chiave sul quadro (o avvia il motore) e riprova.")
                return false
            }
            Live.connected.value = true
            update("Collegato all'auto: sto registrando")
            Recorder(this, elm) { update(it) }.run()
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
            if (reason != "manuale" && !Prefs.autostart(ctx)) return
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
class Recorder(private val ctx: Context, private val elm: Elm, private val status: (String) -> Unit) {
    private val db = Db.get(ctx)
    private val acc = TripAcc()
    private val eco = EcoAcc()
    private var lastWarmTitle = ""
    private val warm = Warmup { msg ->
        App.notify(ctx, App.CH_TRIP, 70, "Motore pronto", msg)
        Live.log(msg)
    }
    private val sent = HashSet<String>()
    private val gps = GpsTracker(ctx)
    private var fap: FapAcc? = null
    private lateinit var ext: RenaultExt
    private var odoSet = false
    private var car = 1L
    private var vin: String? = null
    private lateinit var sup: Set<Int>
    private val liveFindings = ArrayList<Finding>()
    private var lowVoltSince = 0L
    private var lastCharge = System.currentTimeMillis()

    suspend fun run() {
        sup = elm.supported()
        Live.log("La centralina fornisce ${sup.size} parametri")
        vin = try { elm.vin() } catch (e: IOException) { throw e } catch (_: Exception) { null }
        val proto = elm.protocol()
        // impronta della centralina: serve a riconoscere le auto che non comunicano il telaio
        val fp = if (vin != null) null else {
            val calib = try { elm.info09(0x04) } catch (e: IOException) { throw e } catch (_: Exception) { null }
            proto + "|" + sup.sorted().joinToString(",").hashCode().toString(16) + "|" + (calib ?: "")
        }
        car = resolveCar(vin, fp)
        ext = RenaultExt(elm)
        ext.probe(proto, vin)
        val start = System.currentTimeMillis()
        val tripId = db.startTrip(start, car)
        val bat0 = PhoneBattery.now(ctx)
        fap = FapAcc(ctx, car) {
            App.notify(ctx, App.CH_ALARM, 95, "Rigenerazione FAP in corso",
                "Il filtro antiparticolato si sta pulendo. Se puoi, non spegnere il motore per 10-15 minuti e mantieni un'andatura costante.")
            Live.log("Rigenerazione FAP iniziata")
        }
        if (gps.start()) Live.log("GPS attivo") else Live.log("GPS non attivo (permesso posizione mancante)")
        if (DiagCtl.requested) runDiag()
        try { acc.dtcs.addAll(elm.dtcs("03")); acc.pending.addAll(elm.dtcs("07")) } catch (e: IOException) { throw e } catch (_: Exception) {}
        elm.monitor()?.let { acc.mil = it.first }
        if (acc.dtcs.isNotEmpty() || acc.mil) alarm("dtc", "Codice errore: ${acc.dtcs.joinToString()}",
            acc.dtcs.joinToString("\n") { c -> "$c: " + Dtc.describe(c) } +
                (if (acc.mil) "\nSpia motore accesa." else "\nSpia motore spenta: guasto memorizzato, non per forza attuale."), 2, "Codici errore")
        else if (acc.pending.isNotEmpty()) alarm("pend", "Problema in sospeso", "Codici in sospeso: ${acc.pending.joinToString()}. Potrebbe essere l'inizio di un guasto.", 1, "Codici errore")

        val fuelPid = 0x5E in sup
        if (!fuelPid) Live.log("Consumo non fornito dalla centralina: lo stimo")
        Eco.live.value = Eco.State()
        Warmup.live.value = WarmState()
        val fast = PIDS.filter { it.pid in FAST && it.pid in sup }
        val slow = PIDS.filter { it.pid !in FAST && it.pid in sup }
        val values = HashMap<String, Double>()
        val buf = ArrayList<Sample>()
        var cycle = 0; var fails = 0; var lastSample = 0L; var lastT = System.currentTimeMillis()
        Live.alerts.value = emptyList()

        try {
            while (currentCoroutineContext().isActive) {
                if (DiagCtl.requested) runDiag()
                var got = 0
                for (p in fast) elm.read(p)?.let { values[p.key] = it; got++ }
                if (cycle % 8 == 0) for (p in slow) elm.read(p)?.let { values[p.key] = it; got++ }
                if (cycle % 4 == 0 || !acc.engineStarted) elm.voltage()?.let { values["volt"] = it; got++ }
                if (ext.available) {
                    if (cycle % 4 == 1) ext.readFap(values)
                    if (cycle % 60 == 2) {
                        ext.readSlow(values)
                        values["odo_ecu"]?.let { o -> if (!odoSet && o > 1000) { odoSet = true; Maint.setOdometer(ctx, o, System.currentTimeMillis(), car) } }
                    }
                }
                if (cycle > 0 && cycle % 500 == 0) checkCodes()
                if (got == 0) { fails++; if (fails >= 4) { Live.log("La centralina non risponde più"); break }; delay(700); continue }
                fails = 0
                val m = values["map"]; val b = values["baro"]
                if (m != null && b != null) values["boost"] = (m - b) / 100.0
                if (!fuelPid) {
                    val r = values["rpm"]; val l = values["load"]
                    if (r != null && l != null) values["fuel_rate"] = Eco.estimateFuel(r, l)
                }
                val now = System.currentTimeMillis()
                val dt = (now - lastT) / 1000.0
                acc.add(values, dt, now)
                if (acc.engineStarted) { eco.add(values, dt, !fuelPid); fap?.add(values, now, acc.km) }
                warm.add(values, now, dt, acc.engineStarted)
                Warmup.live.value.let { w ->
                    val t = when (w.phase) {
                        -1 -> "Collegato: in attesa dell'avviamento"
                        3 -> "Collegato all'auto: sto registrando"
                        else -> w.title + (w.maxRpm?.let { " · max $it giri" } ?: "")
                    }
                    if (t != lastWarmTitle) { lastWarmTitle = t; status(t) }
                }
                lastT = now
                Live.values.value = HashMap(values)
                liveChecks(values, now)
                if (now - lastSample >= 1000 && acc.engineStarted) {
                    fun v(k: String) = values[k] ?: Double.NaN
                    buf.add(Sample(now, v("rpm"), v("speed"), v("load"), v("coolant"), v("intake"), v("maf"), v("boost"), v("rail"), v("fuel_rate"), v("volt"), v("oil")))
                    lastSample = now
                    if (buf.size >= 30) { db.addSamples(tripId, buf); buf.clear(); db.addPoints(tripId, gps.drain()) }
                }
                cycle++
            }
        } catch (e: IOException) {
            Live.log("Collegamento chiuso: ${e.message}")
        } finally {
            finish(tripId, start, buf, bat0)
        }
    }

    private suspend fun runDiag() {
        DiagCtl.requested = false
        status("Diagnosi in corso…")
        try {
            Diagnosis(ctx, elm, sup, vin, car, ext).run()
            App.notify(ctx, App.CH_TRIP, 80, "Diagnosi completata", "Apri AutoSalute per leggere il report.")
        } catch (e: IOException) {
            DiagCtl.state.value = DiagState(error = "Collegamento perso durante la diagnosi: riprova.")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagCtl.state.value = DiagState(error = "Diagnosi interrotta: ${e.message}")
        }
        lastWarmTitle = ""
    }

    /** Riconosce l'auto dal numero di telaio, così i dati di auto diverse non si mescolano. */
    private fun resolveCar(vin: String?, fp: String?): Long {
        val active = Cars.activeId(ctx)
        if (vin == null) {
            if (fp == null) return active
            Live.log("Telaio non disponibile: riconosco l'auto dalla centralina")
            db.carByFp(fp)?.let { c ->
                if (c.id != active) {
                    Cars.select(ctx, c.id)
                    App.notify(ctx, App.CH_TRIP, 90, "Auto riconosciuta: ${c.name}", "Ho selezionato il suo profilo in automatico.")
                }
                return c.id
            }
            val act = db.car(active)
            if (act != null && act.vin == null && db.carFp(active) == null) { db.setCarFp(active, fp); return active }
            val id = db.addCar("Auto nuova " + java.text.SimpleDateFormat("d MMM", java.util.Locale.ITALY).format(java.util.Date()), null)
            db.setCarFp(id, fp)
            Cars.select(ctx, id)
            App.notify(ctx, App.CH_TRIP, 90, "Nuova auto rilevata",
                "Questa centralina è diversa da quella delle tue auto: i dati vanno in un profilo separato. Puoi rinominarlo in Imposta.")
            return id
        }
        Live.log("Telaio: $vin")
        db.carByVin(vin)?.let { c ->
            if (c.id != active) {
                Cars.select(ctx, c.id)
                App.notify(ctx, App.CH_TRIP, 90, "Auto riconosciuta: ${c.name}", "Ho selezionato il suo profilo in automatico.")
            }
            return c.id
        }
        val act = db.car(active)
        if (act != null && act.vin == null) { db.setCarVin(active, vin); return active }
        val id = db.addCar("Auto ${vin.takeLast(6)}", vin)
        Cars.select(ctx, id)
        App.notify(ctx, App.CH_TRIP, 90, "Nuova auto rilevata",
            "Telaio $vin: i suoi dati vanno in un profilo separato, senza mescolarsi con le altre auto. Puoi rinominarla in Imposta.")
        return id
    }

    private suspend fun checkCodes() {
        val d = elm.dtcs("03"); val p = elm.dtcs("07")
        val newD = d.filter { it !in acc.dtcs }; val newP = p.filter { it !in acc.pending }
        acc.dtcs.addAll(d); acc.pending.addAll(p)
        if (newD.isNotEmpty()) alarm("dtc_" + newD.joinToString(), "Nuovo codice errore", newD.joinToString("\n") { "$it: " + Dtc.describe(it) }, 2, "Codici errore")
        if (newP.isNotEmpty()) alarm("pend_" + newP.joinToString(), "Nuovo problema in sospeso", newP.joinToString(), 1, "Codici errore")
        elm.monitor()?.let { if (it.first && !acc.mil) { acc.mil = true; alarm("mil", "Spia motore accesa", "La centralina ha acceso la spia motore.", 2, "Codici errore") } }
    }

    private fun liveChecks(v: Map<String, Double>, now: Long) {
        val c = v["coolant"]; val volt = v["volt"]; val rpm = v["rpm"] ?: 0.0
        if (c != null && c >= 108) alarm("hot", "Motore troppo caldo: ${c.toInt()}°C", "Accosta appena puoi e lascia il motore al minimo. Controlla il liquido di raffreddamento a motore freddo.", 2, "Raffreddamento")
        v["oil"]?.let { if (it >= 132) alarm("oil", "Olio molto caldo: ${it.toInt()}°C", "Riduci l'andatura.", 1, "Motore") }
        if (volt != null && rpm > 800) {
            // Alternatore "intelligente": a batteria carica può stare a 12,4-12,8 V e ricarica in rilascio.
            // È un guasto solo se la tensione resta bassa a lungo e non sale mai oltre 13 V.
            if (volt >= 13.0) lastCharge = now
            if (volt < 12.2) { if (lowVoltSince == 0L) lowVoltSince = now } else lowVoltSince = 0L
            if (lowVoltSince > 0 && now - lowVoltSince > 120_000 && now - lastCharge > 600_000)
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

    private fun finish(tripId: Long, start: Long, buf: List<Sample>, bat0: PhoneBattery) {
        val usedGps = gps.active
        gps.stop()
        try {
            if (buf.isNotEmpty()) db.addSamples(tripId, buf)
            db.addPoints(tripId, gps.drain())
            if (!acc.engineStarted && acc.dtcs.isEmpty()) { db.deleteTrip(tripId); Live.log("Quadro acceso senza avviare: niente viaggio"); return }
            val bat1 = PhoneBattery.now(ctx)
            val mah = PhoneBattery.appMah(bat0, bat1, usedGps)
            val batt = HashMap<String, Double>()
            batt["app_mah"] = mah
            batt["app_batt_pct"] = mah / PhoneBattery.capacityMah(ctx) * 100
            if (!bat0.charging && !bat1.charging && bat0.level >= bat1.level) batt["phone_batt_drop"] = (bat0.level - bat1.level).toDouble()
            fap?.let { f ->
                f.finish(System.currentTimeMillis(), acc.km)
                f.events.forEach { e -> db.addRegen(car, e) }
                if (f.events.any { it.interrupted }) App.notify(ctx, App.CH_TRIP, 96, "Rigenerazione FAP interrotta",
                    "Hai spento il motore mentre il filtro si stava pulendo. Ogni tanto succede; se capita spesso fai un tratto di 20 minuti a velocità costante.")
            }
            val metrics = acc.metrics() + eco.metrics() + batt + (fap?.metrics() ?: emptyMap())
            val hist = db.trips(car, 60).filter { it.id != tripId }.map { it.metrics }
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
            try { MaintNotify.check(ctx, car) } catch (_: Exception) {}
            Backup.auto(ctx)
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
        if (!Prefs.autostart(ctx)) return
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
