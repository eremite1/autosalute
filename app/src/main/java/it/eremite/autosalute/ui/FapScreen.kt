package it.eremite.autosalute.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.Live
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.Maint
import it.eremite.autosalute.health.FapAcc
import it.eremite.autosalute.health.RegenEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALY)

class FapSummary(val events: List<RegenEvent>, val kmSinceLast: Double?, val avgInterval: Double?, val interrupted: Int,
                 val restrNow: Double?, val restrClean: Double?)

suspend fun loadFap(ctx: android.content.Context): FapSummary = withContext(Dispatchers.IO) {
    val car = Cars.activeId(ctx)
    val db = Db.get(ctx)
    val ev = db.regens(car)
    val done = ev.filter { !it.interrupted }
    val last = done.firstOrNull()
    val kmSince = last?.let { db.kmSince(it.end, car) }
    // km percorsi tra una pulizia e la successiva (a, più recente; b, precedente)
    val intervals = done.zipWithNext { a, b -> db.kmSince(b.start - 1, car) - db.kmSince(a.start - 1, car) + a.kmStart - b.kmStart }
        .filter { it > 20 }
    // contropressione: ultimo viaggio vs primo viaggio dopo l'ultima rigenerazione
    val trips = db.trips(car, 200).filter { it.metrics["dpf_restr"] != null }
    val now = trips.firstOrNull()?.metrics?.get("dpf_restr")
    val clean = last?.let { l -> trips.lastOrNull { it.start >= l.end }?.metrics?.get("dpf_restr") }
    FapSummary(ev, kmSince, if (intervals.isEmpty()) null else intervals.average(), ev.take(20).count { it.interrupted }, now, clean)
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
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(C.card).clickable(onClick = open).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (live.regen) C.warn else if ((s?.interrupted ?: 0) >= 3) C.warn else C.ok); Spacer(Modifier.width(8.dp))
            Text("Filtro antiparticolato (FAP)", color = C.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("›", color = C.dim, fontSize = 20.sp)
        }
        val sum = s
        Text(
            when {
                live.regen -> "Rigenerazione in corso: non spegnere il motore se puoi"
                sum == null || sum.events.isEmpty() -> "Nessuna rigenerazione registrata ancora"
                sum.kmSinceLast != null -> "Ultima pulizia ${sum.kmSinceLast.toInt()} km fa" +
                    (sum.avgInterval?.let { " · di solito ogni ${it.toInt()} km" } ?: "")
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
    var s by remember { mutableStateOf<FapSummary?>(null) }
    LaunchedEffect(tv) { s = loadFap(ctx) }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = close, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Indietro") }
        Header("Filtro antiparticolato", "Pulizie automatiche (rigenerazioni) e stato del filtro")

        if (live.regen) Box2(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Rigenerazione in corso", color = C.warn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Da ${((System.currentTimeMillis() - live.since) / 60000).toInt()} minuti (rilevata da: ${live.reason}). " +
                "Se puoi continua a guidare a velocità costante per 10-15 minuti. Spegnere ora la interrompe.", color = C.text, fontSize = 14.sp)
        }

        val sum = s
        Section("Riepilogo")
        Box2(Modifier.fillMaxWidth()) {
            if (sum == null || sum.events.isEmpty()) {
                Text("Ancora nessuna rigenerazione registrata. Di solito il 1.5 dCi ne fa una ogni 300-1000 km: l'app le riconosce da sola mentre guidi.",
                    color = C.dim, fontSize = 13.sp)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Big2("Dall'ultima", sum.kmSinceLast?.let { "${it.toInt()} km" } ?: "–")
                    Big2("Intervallo medio", sum.avgInterval?.let { "${it.toInt()} km" } ?: "–")
                    Big2("Interrotte (ult. 20)", "${sum.interrupted}")
                }
                Spacer(Modifier.height(8.dp))
                val adv = when {
                    sum.interrupted >= 3 -> "Molte rigenerazioni interrotte: la fuliggine si accumula. Fai ogni tanto 20 minuti di extraurbano o autostrada a velocità costante."
                    sum.avgInterval != null && sum.avgInterval < 250 -> "Il filtro si riempie in fretta (pulizia ogni meno di 250 km): tanti tragitti brevi, oppure EGR/iniettori da controllare."
                    else -> "Rigenerazioni regolari: il filtro lavora bene."
                }
                Text(adv, color = C.text, fontSize = 13.sp)
            }
        }

        Section("Stato del filtro")
        Box2(Modifier.fillMaxWidth()) {
            val now = sum?.restrNow; val clean = sum?.restrClean
            if (now == null) {
                Text("La tua auto non comunica con i codici standard la pressione del filtro, quindi l'intasamento non si misura direttamente. " +
                    "Lo stato si ricava da quanto spesso e quanto bene si pulisce (sopra).", color = C.dim, fontSize = 13.sp)
            } else {
                val pct = if (clean != null && clean > 0) ((now / clean - 1) * 100).coerceAtLeast(0.0) else null
                Text("Contropressione: ${"%.2f".format(now)}" + (pct?.let { " · +${it.toInt()}% rispetto a filtro pulito" } ?: ""),
                    color = C.text, fontWeight = FontWeight.SemiBold)
                Text(when {
                    pct == null -> "Serve una rigenerazione per avere il riferimento di filtro pulito."
                    pct < 40 -> "Filtro poco carico."
                    pct < 90 -> "Filtro a metà: la prossima pulizia arriverà nei prossimi viaggi."
                    else -> "Filtro molto carico: favorisci un tratto a velocità costante."
                }, color = C.dim, fontSize = 13.sp)
            }
            if (!live.dpfTemp.isNaN()) Text("Temperatura scarico/FAP ora: ${live.dpfTemp.toInt()}°C", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        if (sum != null && sum.events.isNotEmpty()) {
            Section("Rigenerazioni")
            sum.events.take(50).forEach { e ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(if (e.interrupted) C.warn else C.ok); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (e.interrupted) "Interrotta" else "Completata") + " · ${(e.durS / 60).toInt()} min · ${e.kmDuring.toInt()} km",
                            color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(fFmt.format(Date(e.start)) + (if (!e.maxTemp.isNaN()) " · max ${e.maxTemp.toInt()}°C" else "") + " · rilevata da ${e.method}",
                            color = C.dim, fontSize = 12.sp)
                    }
                }
            }
        }
        Text("Come funziona: quando il filtro è pieno di fuliggine la centralina la brucia alzando la temperatura dello scarico (rigenerazione attiva, 10-20 minuti). " +
            "In autostrada può bruciare anche da sola (passiva, non rilevabile). Se l'auto non fornisce la temperatura dello scarico, l'app riconosce la rigenerazione " +
            "dal motore che a parità di velocità lavora molto più del solito: è una stima, le rigenerazioni brevi possono sfuggire.",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Big2(label: String, v: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = C.dim, fontSize = 11.sp)
    }
}
