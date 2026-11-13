package it.eremite.autosalute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.Live
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.Trip
import it.eremite.autosalute.health.FapAcc
import it.eremite.autosalute.health.RegenEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALY)

class FapSummary(
    val events: List<RegenEvent>, val ecu: Boolean,
    val soot: Double?, val sootTs: Long?, val kmRegen: Double?, val trigger: Double,
    val avgInterval: Double?, val interrupted: Int, val sootSeries: List<Double>,
    val restrNow: Double?, val restrClean: Double?, val oilDil: Double?,
)

suspend fun loadFap(ctx: android.content.Context): FapSummary = withContext(Dispatchers.IO) {
    val car = Cars.activeId(ctx)
    val db = Db.get(ctx)
    val ev = db.regens(car)
    val trips: List<Trip> = db.trips(car, 400)
    val withSoot = trips.filter { it.metrics["soot_end"] != null }
    val ecu = withSoot.isNotEmpty() || ev.any { it.method == "centralina" || it.method == "contatore centralina" }
    val last = withSoot.firstOrNull()
    val kmReg = last?.metrics?.get("km_regen_end")?.let { k -> k + db.kmSince(last.end, car) }
    // soglia di partenza delle pulizie imparata dalla tua auto (fuliggine all'inizio delle rigenerazioni attive)
    val starts = ev.filter { it.type == "attiva" && !it.soot0.isNaN() && it.soot0 > 5 }.map { it.soot0 }
    val trigger = if (starts.size >= 2) starts.average() else 24.0
    val intervals = if (ecu) ev.filter { it.type == "attiva" && !it.interrupted && it.kmStart > 20 }.map { it.kmStart }
    else {
        val done = ev.filter { !it.interrupted }
        done.zipWithNext { a, b -> db.kmSince(b.start - 1, car) - db.kmSince(a.start - 1, car) + a.kmStart - b.kmStart }.filter { it > 20 }
    }
    val rTrips = trips.filter { it.metrics["dpf_restr"] != null }
    val lastDone = ev.firstOrNull { !it.interrupted }
    FapSummary(
        ev, ecu, last?.metrics?.get("soot_end"), last?.end, kmReg, trigger,
        if (intervals.isEmpty()) null else intervals.average(), ev.take(20).count { it.interrupted },
        withSoot.take(60).reversed().map { it.metrics["soot_end"]!! },
        rTrips.firstOrNull()?.metrics?.get("dpf_restr"),
        lastDone?.let { l -> rTrips.lastOrNull { it.start >= l.end }?.metrics?.get("dpf_restr") },
        trips.firstNotNullOfOrNull { it.metrics["oil_dilution"] },
    )
}

private fun typeLabel(e: RegenEvent): Pair<String, Color> = when {
    e.type == "a posteriori" -> "Fatta mentre l'app era spenta" to C.info
    e.interrupted -> "Interrotta" to C.warn
    e.type == "passiva" -> "Passiva (autostrada)" to C.ok
    e.type == "statica" -> "Statica (officina)" to C.info
    else -> "Attiva" to C.ok
}

/** Riquadro nella schermata Salute. */
@Composable
fun FapCard(open: () -> Unit) {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    val car by Cars.active.collectAsState()
    val live by FapAcc.live.collectAsState()
    var s by remember { mutableStateOf<FapSummary?>(null) }
    LaunchedEffect(tv, car) { s = loadFap(ctx) }
    val sum = s
    val soot = if (live.ecu && !live.soot.isNaN()) live.soot else sum?.soot
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(C.card).clickable(onClick = open).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val col = when {
                live.regen -> C.warn
                soot != null && soot >= 45 -> C.bad
                soot != null && soot >= 32 -> C.warn
                (sum?.interrupted ?: 0) >= 3 -> C.warn
                else -> C.ok
            }
            Dot(col); Spacer(Modifier.width(8.dp))
            Text("Filtro antiparticolato (FAP)", color = C.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("›", color = C.dim, fontSize = 20.sp)
        }
        Text(
            when {
                live.regen -> "Pulizia ${live.type.ifEmpty { "" }} in corso: non spegnere il motore se puoi"
                soot != null -> "Fuliggine ${"%.1f".format(soot)} g" + (sum?.kmRegen?.let { " · ultima pulizia ${it.toInt()} km fa" } ?: "")
                sum == null || sum.events.isEmpty() -> "Nessuna rigenerazione registrata ancora"
                else -> "${sum.events.size} rigenerazioni registrate"
            }, color = if (live.regen) C.warn else C.dim, fontSize = 13.sp
        )
    }
}

@Composable
fun FapScreen(close: () -> Unit) {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    val live by FapAcc.live.collectAsState()
    val conn by Live.connected.collectAsState()
    var s by remember { mutableStateOf<FapSummary?>(null) }
    LaunchedEffect(tv) { s = loadFap(ctx) }
    BackHandler(onBack = close)
    val sum = s
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = close, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Indietro") }
        Header("Filtro antiparticolato", if (sum?.ecu == true) "Dati letti dalla centralina Renault" else "Dati stimati dal comportamento del motore")

        if (live.regen) Box2(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Rigenerazione ${live.type} in corso", color = C.warn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Da ${((System.currentTimeMillis() - live.since) / 60000).toInt()} minuti" +
                (if (!live.dpfTemp.isNaN()) " · filtro a ${live.dpfTemp.toInt()}°C" else "") +
                ". Se puoi continua a velocità costante per 10-15 minuti: spegnere ora la interrompe.", color = C.text, fontSize = 14.sp)
        }

        // --- stato del filtro ---
        Section("Stato del filtro")
        Box2(Modifier.fillMaxWidth()) {
            val soot = if (conn && live.ecu && !live.soot.isNaN()) live.soot else sum?.soot
            if (soot != null && sum != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f".format(soot), color = C.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(" g di fuliggine", color = C.dim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                SootBar(soot, sum.trigger)
                val pct = (soot / sum.trigger * 100).toInt()
                Text(
                    when {
                        soot >= 45 -> "Molto intasato: serve una rigenerazione forzata in officina."
                        soot >= sum.trigger * 1.3 -> "Sopra la soglia di pulizia: fai 20-30 minuti a velocità costante."
                        pct >= 85 -> "Quasi pieno ($pct%): la pulizia partirà a breve."
                        else -> "Pieno al $pct% rispetto alla soglia di pulizia della tua auto (~${sum.trigger.toInt()} g)."
                    }, color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
                )
                if (!conn && sum.sootTs != null) Text("Valore di fine viaggio, ${fFmt.format(Date(sum.sootTs))}", color = C.dim, fontSize = 11.sp)
                val kr = if (conn && live.ecu && !live.kmRegen.isNaN()) live.kmRegen else sum.kmRegen
                kr?.let { Text("Km dall'ultima pulizia riuscita: ${it.toInt()}", color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (conn && !live.dp.isNaN()) Text("Pressione differenziale ora: ${live.dp.toInt()} mbar", color = C.dim, fontSize = 12.sp)
            } else if (sum?.restrNow != null) {
                val now = sum.restrNow; val clean = sum.restrClean
                val pct = if (clean != null && clean > 0) ((now / clean - 1) * 100).coerceAtLeast(0.0) else null
                Text("Contropressione: ${"%.2f".format(now)}" + (pct?.let { " · +${it.toInt()}% rispetto a filtro pulito" } ?: ""),
                    color = C.text, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Valori in arrivo: si leggono durante il prossimo viaggio.", color = C.dim, fontSize = 13.sp)
            }
            sum?.oilDil?.let { d ->
                if (d in 0.0..30.0) Text("Gasolio nell'olio: ${"%.1f".format(d)}%" + if (d >= 5) " – alto, valuta di anticipare il cambio olio" else " – nella norma",
                    color = if (d >= 5) C.warn else C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (sum != null && sum.sootSeries.size >= 3) {
            Section("Fuliggine viaggio dopo viaggio (g)")
            Box2(Modifier.fillMaxWidth()) {
                LineChart(sum.sootSeries, C.warn, dots = true)
                Text("Sale piano mentre guidi, crolla quando il filtro si pulisce.", color = C.dim, fontSize = 11.sp)
            }
        }

        // --- riepilogo ---
        if (sum != null && sum.events.isNotEmpty()) {
            Section("Riepilogo pulizie")
            Box2(Modifier.fillMaxWidth()) {
                val month = sum.events.filter { it.start > System.currentTimeMillis() - 30L * 24 * 3600 * 1000 }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Big2("Attive (30 gg)", "${month.count { it.type == "attiva" && !it.interrupted }}")
                    Big2("Passive (30 gg)", "${month.count { it.type == "passiva" }}")
                    Big2("Interrotte (30 gg)", "${month.count { it.interrupted }}")
                }
                Spacer(Modifier.height(8.dp))
                sum.avgInterval?.let { Text("Una pulizia attiva ogni ${it.toInt()} km in media (normale 300-1000 km).", color = C.text, fontSize = 13.sp) }
                Text(when {
                    sum.interrupted >= 3 -> "Molte pulizie interrotte: fai ogni tanto 20 minuti di extraurbano o autostrada senza spegnere."
                    sum.avgInterval != null && sum.avgInterval < 250 -> "Il filtro si riempie in fretta: tanti tragitti brevi, oppure EGR o iniettori da controllare."
                    else -> "Il filtro si pulisce regolarmente."
                }, color = C.dim, fontSize = 13.sp)
            }

            Section("Cronologia")
            sum.events.take(80).forEach { e ->
                val (lab, col) = typeLabel(e)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(col); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lab + if (e.durS > 0) " · ${(e.durS / 60).toInt()} min" else "", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val det = listOfNotNull(
                            fFmt.format(Date(e.start)),
                            if (e.kmDuring > 0.5) "${e.kmDuring.toInt()} km durante" else null,
                            if (!e.soot0.isNaN() && !e.soot1.isNaN()) "fuliggine ${"%.1f".format(e.soot0)} → ${"%.1f".format(e.soot1)} g" else null,
                            if (!e.maxTemp.isNaN()) "max ${e.maxTemp.toInt()}°C" else null,
                            if (e.type == "attiva" && e.kmStart > 20) "dopo ${e.kmStart.toInt()} km" else null,
                            if (e.method != "centralina" && e.method != "contatore centralina") "stimata" else null,
                        )
                        Text(det.joinToString(" · "), color = C.dim, fontSize = 12.sp)
                    }
                }
            }
        }

        Text(
            "Attiva: la centralina alza la temperatura dello scarico per bruciare la fuliggine (10-20 minuti). " +
                "Passiva: in autostrada lo scarico è già abbastanza caldo e la fuliggine brucia da sola. " +
                "Statica: pulizia forzata in officina da fermo. Interrotta: il motore è stato spento prima della fine.",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SootBar(g: Double, trigger: Double) {
    val max = 50.0
    Canvas(Modifier.fillMaxWidth().height(18.dp).padding(top = 6.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(C.line, Offset.Zero, Size(w, h), CornerRadius(h / 2, h / 2))
        val fx = (g / max).coerceIn(0.0, 1.0).toFloat() * w
        val col = when { g >= 45 -> C.bad; g >= trigger * 1.3 -> C.warn; else -> C.ok }
        drawRoundRect(col, Offset.Zero, Size(fx.coerceAtLeast(h), h), CornerRadius(h / 2, h / 2))
        val tx = (trigger / max).toFloat() * w
        drawLine(C.text, Offset(tx, -4f), Offset(tx, h + 4f), 3f)
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0 g", color = C.dim, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text("| soglia pulizia ~${trigger.toInt()} g", color = C.dim, fontSize = 10.sp)
        Text("   50 g", color = C.dim, fontSize = 10.sp)
    }
}

@Composable
private fun Big2(label: String, v: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = C.dim, fontSize = 11.sp)
    }
}
