package it.eremite.autosalute.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.DiagReport
import it.eremite.autosalute.obd.DiagCtl
import it.eremite.autosalute.obd.DiagGroups
import it.eremite.autosalute.obd.DiagItem
import it.eremite.autosalute.obd.DiagState
import it.eremite.autosalute.obd.Diagnosis
import it.eremite.autosalute.service.ObdService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val rFmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.ITALY)

@Composable
fun DiagScreen(close: () -> Unit) {
    val ctx = LocalContext.current
    val st by DiagCtl.state.collectAsState()
    val tv by Live.tripsVersion.collectAsState()
    var reports by remember { mutableStateOf<List<DiagReport>>(emptyList()) }
    var open by remember { mutableStateOf<DiagReport?>(null) }
    LaunchedEffect(tv, st.reportId) { reports = withContext(Dispatchers.IO) { Db.get(ctx).diags() } }
    val cars = rememberCars()

    // A diagnosi finita apre il report appena creato
    LaunchedEffect(st.reportId) {
        val id = st.reportId ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { Db.get(ctx).diags().firstOrNull { it.id == id } }
        if (r != null) { open = r; DiagCtl.state.value = DiagState() }
    }

    open?.let { r ->
        BackHandler { open = null }
        ReportView(r, cars.firstOrNull { it.id == r.car }?.name, back = { open = null }, delete = {
            Db.get(ctx).deleteDiag(r.id); open = null; Live.tripsVersion.value++
        })
        return
    }
    BackHandler(onBack = close)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = close, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Indietro") }
        Header("Diagnosi completa", "Controllo generale di qualsiasi auto con presa OBD2")

        val busy = st.running || st.waiting
        Box2(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (!busy) {
                listOf(
                    "1. Inserisci il lettore OBD2 nella presa dell'auto (sotto il volante).",
                    "2. Avvia il motore e lascialo al minimo. Col solo quadro acceso la diagnosi è parziale.",
                    "3. Per la massima precisione fallo a motore caldo, senza clima e luci accese.",
                    "4. Premi Avvia e non accelerare per circa un minuto.",
                ).forEach { Text(it, color = C.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                Spacer(Modifier.height(12.dp))
                if (Prefs.mac(ctx) == null) Text("Prima associa il lettore in Imposta.", color = C.warn)
                else Button(onClick = {
                    DiagCtl.requested = true
                    DiagCtl.state.value = DiagState(waiting = true, step = "Mi collego all'auto…")
                    ObdService.start(ctx, "manuale")
                }, modifier = Modifier.fillMaxWidth()) { Text("Avvia diagnosi", fontSize = 16.sp) }
            } else {
                Text(st.step, color = C.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                if (st.waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Non spegnere il motore e non accelerare.", color = C.dim, fontSize = 12.sp)
            }
            st.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = C.warn, fontSize = 13.sp)
            }
        }

        if (busy && st.items.isNotEmpty()) {
            Section("Risultati in arrivo")
            st.items.forEach { DiagRow(it) }
        }

        if (reports.isNotEmpty()) {
            Section("Report salvati")
            reports.forEach { r ->
                val score = r.json.optInt("score", -1)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
                        .clickable { open = r }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(C.card2), contentAlignment = Alignment.Center) {
                        Text(if (score >= 0) "$score" else "–", color = if (score >= 0) C.score(score) else C.dim, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cars.firstOrNull { it.id == r.car }?.name ?: "Auto", color = C.text, fontWeight = FontWeight.SemiBold)
                        Text(rFmt.format(Date(r.ts)) + " · " + r.json.optString("verdict"), color = C.dim, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagRow(i: DiagItem) {
    val col = when (i.sev) { 2 -> C.bad; 1 -> C.warn; 0 -> C.ok; else -> C.info }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 5.dp)) { Dot(col) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(i.title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (i.detail.isNotBlank()) Text(i.detail, color = C.text.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            if (i.action.isNotBlank()) Text("→ " + i.action, color = col, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ReportView(r: DiagReport, carName: String?, back: () -> Unit, delete: () -> Unit) {
    val ctx = LocalContext.current
    val score = r.json.optInt("score")
    val arr = r.json.optJSONArray("items")
    val items = if (arr == null) emptyList() else (0 until arr.length()).map { DiagItem.from(arr.getJSONObject(it)) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = back, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Diagnosi") }
        Header(carName ?: "Report diagnosi", rFmt.format(Date(r.ts)))
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            ScoreRing(score, r.json.optString("verdict"))
        }
        val nBad = items.count { it.sev == 2 }; val nWarn = items.count { it.sev == 1 }; val nOk = items.count { it.sev == 0 }
        Text("$nBad problemi · $nWarn da controllare · $nOk tutto ok", color = C.dim, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        if (!r.json.optBoolean("running", true)) Text("Diagnosi fatta a motore spento: le prove del minimo non sono incluse.", color = C.warn, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val txt = Diagnosis.toText(r.json, carName)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Diagnosi auto").putExtra(Intent.EXTRA_TEXT, txt), "Condividi il report"))
            }, modifier = Modifier.weight(1f)) { Text("Condividi report") }
            OutlinedButton(onClick = { confirm = true }) { Text("Elimina") }
        }
        for (g in DiagGroups.ORDER) {
            val gi = items.filter { it.group == g }.sortedByDescending { it.sev }
            if (gi.isEmpty()) continue
            Section(g)
            gi.forEach { DiagRow(it) }
        }
        Text("La diagnosi legge la centralina del motore tramite OBD2: non vede tutto (freni, airbag, carrozzeria) e non sostituisce un meccanico.",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Eliminare questo report?") },
        confirmButton = { TextButton(onClick = { confirm = false; delete() }) { Text("Elimina", color = C.bad) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } }
    )
}
