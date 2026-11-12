package it.eremite.autosalute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.Live
import it.eremite.autosalute.Prefs
import it.eremite.autosalute.Updater
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.Backup
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.GeoPoint
import it.eremite.autosalute.data.Sample
import it.eremite.autosalute.data.Trip
import it.eremite.autosalute.health.Cat
import it.eremite.autosalute.health.Finding
import it.eremite.autosalute.health.Health
import it.eremite.autosalute.health.ECO_LABELS
import it.eremite.autosalute.obd.LABELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALY)

@Composable
fun rememberTrips(): List<Trip> {
    val ctx = LocalContext.current
    val ver by Live.tripsVersion.collectAsState()
    val car by Cars.active.collectAsState()
    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    LaunchedEffect(ver, car) { trips = withContext(Dispatchers.IO) { Db.get(ctx).trips(Cars.activeId(ctx)) } }
    return trips
}

@Composable
fun Header(title: String, sub: String? = null) {
    Column(Modifier.padding(top = 24.dp, bottom = 4.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.text)
        if (sub != null) Text(sub, color = C.dim, fontSize = 14.sp)
    }
}

@Composable
fun ConnChip() {
    val conn by Live.connected.collectAsState()
    val st by Live.status.collectAsState()
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.card).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(if (conn) C.ok else C.dim, 8); Spacer(Modifier.width(8.dp))
        Text(st, color = C.text, fontSize = 13.sp)
    }
}

// ------------------------------------------------------------------ SALUTE
@Composable
fun HomeScreen(act: MainActivity, goMaint: () -> Unit, openDiag: () -> Unit, openFap: () -> Unit, goSetup: () -> Unit) {
    val trips = rememberTrips()
    val live by Live.alerts.collectAsState()
    val conn by Live.connected.collectAsState()
    val last = trips.firstOrNull()
    val findings: List<Finding> = (live + (last?.findings ?: emptyList())).distinctBy { it.title }.sortedByDescending { it.sev }
    val score = when {
        conn && live.isNotEmpty() -> Health.score(live)
        last != null -> last.score
        else -> null
    }
    val label = when {
        score == null -> "nessun dato"
        score >= 85 -> "In ottima salute"
        score >= 65 -> "Qualcosa da controllare"
        else -> "Serve attenzione"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        CarSwitcher(goSetup)
        Text(last?.let { "Ultimo viaggio: " + dayFmt.format(Date(it.start)) } ?: "Nessun viaggio ancora", color = C.dim, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp)); ConnChip()
        Button(onClick = openDiag, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.card2, contentColor = C.text)) {
            Text("🔍  Diagnosi completa dell'auto", fontWeight = FontWeight.SemiBold)
        }
        UpdateBox(always = false)
        MaintAlert(goMaint)
        FapCard(openFap)
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { ScoreRing(score, label) }

        if (Prefs.mac(act) == null) {
            Box2(Modifier.fillMaxWidth()) {
                Text("Primo passo", fontWeight = FontWeight.Bold, color = C.text)
                Text("Associa il lettore Vgate: poi l'app partirà da sola ogni volta che accendi il quadro.", color = C.dim, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = goSetup) { Text("Vai alle impostazioni") }
            }
        }

        Section("Sistemi dell'auto")
        val byCat = findings.groupBy { it.cat }
        Cat.ALL.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cat ->
                    val worst = byCat[cat]?.maxOf { it.sev } ?: -1
                    val (col, txt) = when {
                        last == null && !conn -> C.dim to "in attesa"
                        worst >= 2 -> C.bad to "problema"
                        worst == 1 -> C.warn to "da controllare"
                        worst == 0 -> C.info to "lieve variazione"
                        else -> C.ok to "regolare"
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.card).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(col); Spacer(Modifier.width(8.dp))
                            Text(txt, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(cat, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (findings.isNotEmpty()) {
            Section("Cosa ho notato")
            findings.forEach { FindingCard(it) }
        } else if (last != null) {
            Section("Cosa ho notato")
            Text("Nessuna anomalia nell'ultimo viaggio. 👍", color = C.dim)
        }

        if (last != null) {
            Section("Ultimo viaggio")
            TripSummary(last)
        }
        ParkedCard()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TripSummary(t: Trip) {
    Box2(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Km", "%.1f".format(t.km)); Stat("Durata", "${(t.dur / 60).toInt()} min")
            Stat("Media", "${t.avgSpeed.toInt()} km/h")
            Stat("Consumo", if (t.cons.isNaN()) "–" else "%.1f".format(t.cons))
        }
    }
}

@Composable
private fun Stat(label: String, v: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = C.dim, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ LIVE
@Composable
fun LiveGrid() {
    val vals by Live.values.collectAsState()
    val order = listOf("rpm", "speed", "coolant", "volt", "boost", "load", "rail", "maf", "fuel_rate", "intake", "oil",
        "ambient", "pedal", "throttle", "egr", "egr_err", "baro", "map", "fuel_level", "volt_ecu", "runtime", "dist_clear", "dist_mil")
    val keys = order.filter { vals.containsKey(it) } + vals.keys.filter { it !in order }
    Column(Modifier.fillMaxWidth()) {
        if (keys.isEmpty()) Text("Nessun dato ancora.", color = C.dim)
        keys.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val (lab, unit) = LABELS[k] ?: (k to "")
                    val v = vals[k] ?: Double.NaN
                    val col = liveColor(k, v)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.card).padding(14.dp)) {
                        Text(lab, color = C.dim, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(if (v.isNaN()) "–" else Health.fmt(v), color = col, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Text(unit, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun liveColor(k: String, v: Double) = when (k) {
    "coolant" -> if (v >= 108) C.bad else if (v >= 103) C.warn else C.text
    "volt" -> if (v < 11.9 || v > 15.2) C.bad else if (v < 12.3) C.warn else C.text
    "oil" -> if (v >= 130) C.bad else C.text
    else -> C.text
}

// ------------------------------------------------------------------ VIAGGI
@Composable
fun TripsScreen() {
    val trips = rememberTrips()
    var open by remember { mutableStateOf<Trip?>(null) }
    open?.let { t ->
        BackHandler { open = null }
        TripDetail(t) { open = null }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            val km = trips.sumOf { it.km }
            Header("Viaggi", "${trips.size} viaggi · ${"%.0f".format(km)} km registrati")
            Spacer(Modifier.height(12.dp))
        }
        if (trips.isEmpty()) item { Text("Ancora nessun viaggio.", color = C.dim) }
        items(trips, key = { it.id }) { t ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
                    .clickable { open = t }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(C.card2), contentAlignment = Alignment.Center) {
                    Text("${t.score}", color = C.score(t.score), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(dayFmt.format(Date(t.start)), color = C.text, fontWeight = FontWeight.SemiBold)
                    Text("${"%.1f".format(t.km)} km · ${(t.dur / 60).toInt()} min" + (if (t.cons.isNaN()) "" else " · ${"%.1f".format(t.cons)} L/100km") + (t.metrics["eco_score"]?.let { " · eco ${it.toInt()}" } ?: ""),
                        color = C.dim, fontSize = 13.sp)
                }
                val w = t.findings.count { it.sev >= 1 }
                if (w > 0) Text("⚠ $w", color = C.warn, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TripDetail(t: Trip, back: () -> Unit) {
    val ctx = LocalContext.current
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var route by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    LaunchedEffect(t.id) {
        samples = withContext(Dispatchers.IO) { Db.get(ctx).samples(t.id) }
        route = withContext(Dispatchers.IO) { Db.get(ctx).points(t.id) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = back, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Viaggi") }
        Header(dayFmt.format(Date(t.start)), "Salute del viaggio: ${t.score}/100")
        Spacer(Modifier.height(12.dp)); TripSummary(t)
        if (route.isNotEmpty()) {
            Section("Percorso")
            RouteMap(route, Modifier.fillMaxWidth().height(260.dp))
        }
        t.metrics["app_mah"]?.let { mah ->
            Text("Batteria usata dall'app in questo viaggio: circa ${mah.toInt()} mAh (${"%.1f".format(t.metrics["app_batt_pct"] ?: 0.0)}%)",
                color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (t.dtcs.isNotEmpty()) { Section("Codici errore"); t.dtcs.forEach { Text(it, color = C.bad) } }
        if (t.findings.isNotEmpty()) { Section("Segnalazioni"); t.findings.sortedByDescending { it.sev }.forEach { FindingCard(it) } }
        val charts = listOf<Triple<String, String, (Sample) -> Double>>(
            Triple("Velocità", "km/h", { x: Sample -> x.speed }), Triple("Giri motore", "rpm", { x: Sample -> x.rpm }),
            Triple("Temperatura motore", "°C", { x: Sample -> x.coolant }), Triple("Batteria", "V", { x: Sample -> x.volt }),
            Triple("Pressione turbo", "bar", { x: Sample -> x.boost }), Triple("Pressione gasolio", "bar", { x: Sample -> x.rail }),
            Triple("Flusso aria", "g/s", { x: Sample -> x.maf }), Triple("Consumo istantaneo", "L/h", { x: Sample -> x.fuel }),
            Triple("Temperatura olio", "°C", { x: Sample -> x.oil }), Triple("Aria aspirata", "°C", { x: Sample -> x.intake }),
        )
        val step = maxOf(1, samples.size / 400)
        val thin = samples.filterIndexed { i, _ -> i % step == 0 }
        charts.forEach { (name, unit, f) ->
            val vs = thin.map(f)
            if (vs.count { !it.isNaN() } > 2) {
                Section("$name ($unit)")
                Box2(Modifier.fillMaxWidth()) { LineChart(vs, C.ok) }
            }
        }
        Section("Valori misurati")
        Box2(Modifier.fillMaxWidth()) {
            t.metrics.entries.sortedBy { it.key }.forEach { (k, v) ->
                val lab = Health.metric(k)?.let { it.label + " (" + it.unit + ")" } ?: ECO_LABELS[k] ?: k
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(lab, color = C.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(Health.fmt(v), color = C.text, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ TENDENZE
@Composable
fun TrendsScreen() {
    val trips = rememberTrips()
    val chrono = trips.reversed().takeLast(60)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Header("Statistiche", "Consumi nel tempo e salute dei componenti")
        ConsumptionStats(trips)
        Section("Tendenze dei componenti")
        Text("Come cambia la tua auto viaggio dopo viaggio. La fascia è il tuo valore normale.", color = C.dim, fontSize = 13.sp)
        if (chrono.size < 2) Text("Servono almeno 2 viaggi.", color = C.dim, modifier = Modifier.padding(top = 16.dp))
        Cat.ALL.filter { it != Cat.CODICI }.forEach { cat ->
            val ms = Health.METRICS.filter { m -> m.cat == cat && chrono.count { it.metrics[m.key] != null } >= 2 }
            if (ms.isEmpty()) return@forEach
            Section(cat)
            ms.forEach { m ->
                val vs = chrono.map { it.metrics[m.key] ?: Double.NaN }
                val clean = vs.filter { !it.isNaN() }
                val mean = clean.average()
                val sd = kotlin.math.sqrt(clean.map { (it - mean) * (it - mean) }.average())
                val last = clean.last()
                Box2(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(m.label, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("${Health.fmt(last)} ${m.unit}", color = C.ok, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text("Normale: ${Health.fmt(mean)} ${m.unit}", color = C.dim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LineChart(vs, C.info, band = (mean - 2 * sd) to (mean + 2 * sd), dots = true)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ IMPOSTAZIONI
@Composable
fun SetupScreen(act: MainActivity) {
    val r by act.refresh
    val logs by Live.logLines.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var sentinel by remember(r) { mutableStateOf(Prefs.sentinel(act)) }
    val mac = remember(r) { Prefs.mac(act) }
    val batt = remember(r) { act.batteryOk() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Header("Impostazioni")

        Section("Le tue auto")
        CarsBox(act)

        Section("1 · Lettore OBD2")
        Box2(Modifier.fillMaxWidth()) {
            if (mac == null) {
                Text("Accendi il quadro dell'auto (così il Vgate si accende), poi premi il pulsante e scegli il lettore dall'elenco.", color = C.dim, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { act.associate() }) { Text("Associa il lettore") }
            } else {
                Text(Prefs.name(act), color = C.text, fontWeight = FontWeight.Bold)
                val t = when (Prefs.type(act)) { "ble" -> "Bluetooth LE"; "classic" -> "Bluetooth classico"; else -> "tipo da scoprire" }
                Text("$mac · $t", color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act.startNow() }) { Text("Collega ora") }
                    OutlinedButton(onClick = { act.stopNow() }) { Text("Ferma") }
                    TextButton(onClick = { act.forget() }) { Text("Cambia", color = C.dim) }
                }
            }
        }

        Section("2 · Avvio automatico")
        val auto = remember(r) { Prefs.autostart(act) }
        Box2(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Avvio automatico in sottofondo", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (auto) "L'app parte da sola quando accendi il quadro." else "Spento: l'app registra solo se premi \"Collega ora\".",
                        color = C.dim, fontSize = 12.sp)
                }
                Switch(checked = auto, onCheckedChange = { act.setAutostart(it) })
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (batt) C.ok else C.warn); Spacer(Modifier.width(8.dp))
                Text(if (batt) "Risparmio batteria disattivato per l'app" else "Android potrebbe bloccare l'avvio automatico",
                    color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
            if (!batt) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { act.askBattery() }) { Text("Consenti in sottofondo") }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sorveglianza continua", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Da attivare solo se l'app non parte da sola: resta sempre in ascolto (notifica fissa, consumo minimo).",
                        color = C.dim, fontSize = 12.sp)
                }
                Switch(checked = sentinel, onCheckedChange = {
                    sentinel = it; Prefs.setSentinel(act, it)
                    if (it) act.startNow() else act.stopNow()
                })
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { act.askPermissions() }) { Text("Ricontrolla i permessi") }
        }

        Section("Posizione GPS")
        GpsBox(act)

        Section("Batteria del telefono")
        BatteryBox(act)

        Section("Backup su Google Drive")
        BackupBox(act)

        Section("Aggiornamenti")
        UpdateBox(always = true)

        Section("Dati")
        Box2(Modifier.fillMaxWidth()) {
            Text("Cancella lo storico dei viaggi (la manutenzione resta).", color = C.dim, fontSize = 13.sp)
            TextButton(onClick = { confirm = true }) { Text("Cancella tutti i viaggi", color = C.bad) }
        }

        Section("Registro tecnico")
        Box2(Modifier.fillMaxWidth()) {
            if (logs.isEmpty()) Text("Vuoto", color = C.dim, fontSize = 12.sp)
            logs.take(40).forEach { Text(it, color = C.dim, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { Db.get(act).clear(Cars.activeId(act)); Live.tripsVersion.value++; confirm = false }) { Text("Cancella", color = C.bad) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
        title = { Text("Cancellare tutto?") },
        text = { Text("Perderai lo storico e l'app dovrà reimparare i valori normali della tua auto.") }
    )
}

// ------------------------------------------------------------------ AGGIORNAMENTI
@Composable
fun UpdateBox(always: Boolean) {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (!always && st !is Updater.State.Available && st !is Updater.State.Downloading && st !is Updater.State.Ready) return
    Box2(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        when (val s = st) {
            is Updater.State.Available -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(C.info); Spacer(Modifier.width(8.dp))
                    Text("Aggiornamento disponibile", color = C.text, fontWeight = FontWeight.Bold)
                }
                Text("Versione 1.0.${s.info.code} (hai la ${Updater.currentName(ctx)})", color = C.dim, fontSize = 13.sp)
                if (s.info.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.info.notes, color = C.text.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { scope.launch { Updater.download(ctx, s.info) } }) { Text("Scarica e installa") }
            }
            is Updater.State.Downloading -> {
                Text("Scarico la versione 1.0.${s.info.code}… ${s.pct}%", color = C.text)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is Updater.State.Ready -> {
                Text("Pronto da installare", color = C.text, fontWeight = FontWeight.Bold)
                if (!Updater.canInstall(ctx)) Text("Android chiederà di consentire l'installazione da AutoSalute: attiva l'interruttore, torna indietro e premi di nuovo Installa.", color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { Updater.install(ctx) }) { Text("Installa") }
            }
            else -> {
                Text("Versione installata: ${Updater.currentName(ctx)}", color = C.text)
                val msg = when (s) {
                    is Updater.State.Checking -> "Controllo in corso…"
                    is Updater.State.UpToDate -> "Hai già l'ultima versione."
                    is Updater.State.Error -> s.msg
                    else -> "L'app controlla da sola ogni volta che la apri."
                }
                Text(msg, color = if (s is Updater.State.Error) C.warn else C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { Updater.check(ctx) } }) { Text("Controlla aggiornamenti") }
            }
        }
    }
}


@Composable
fun BackupBox(act: MainActivity) {
    val bv by Backup.status.collectAsState()
    val r by act.refresh
    val target = remember(bv, r) { Backup.target(act) }
    val last = remember(bv, r) { Backup.lastOk(act) }
    val err = remember(bv, r) { Backup.lastError(act) }
    Box2(Modifier.fillMaxWidth()) {
        if (target == null) {
            Text("Salva viaggi, statistiche e manutenzione sul tuo Google Drive. Se cambi telefono non perdi niente.",
                color = C.text, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("Premi il pulsante, nel menu in alto a sinistra scegli Drive (il tuo account Google) e premi Salva.",
                color = C.dim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { act.chooseBackup() }) { Text("Attiva backup su Drive") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (err != null) C.warn else C.ok); Spacer(Modifier.width(8.dp))
                Text(if (err != null) "Ultimo backup non riuscito" else "Backup attivo", color = C.text, fontWeight = FontWeight.SemiBold)
            }
            Text(
                (if (last > 0) "Ultimo salvataggio: " + dayFmt.format(Date(last)) else "Nessun salvataggio ancora") +
                    ". Si aggiorna da solo dopo ogni viaggio.",
                color = C.dim, fontSize = 12.sp
            )
            if (err != null) Text("Errore: $err. Se continua, premi \"Cambia file\" e scegli di nuovo Drive.", color = C.warn, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { act.backupNow() }) { Text("Salva ora") }
                OutlinedButton(onClick = { act.chooseBackup() }) { Text("Cambia file") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Telefono nuovo? Installa l'app e ripristina dal file su Drive:", color = C.dim, fontSize = 12.sp)
        TextButton(onClick = { act.chooseRestore() }) { Text("Ripristina da un backup") }
    }
}
