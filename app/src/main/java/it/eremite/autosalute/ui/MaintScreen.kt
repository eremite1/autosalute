package it.eremite.autosalute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.Live
import it.eremite.autosalute.data.Backup
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.Due
import it.eremite.autosalute.data.Maint
import it.eremite.autosalute.data.MaintEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dFmt = SimpleDateFormat("d MMM yyyy", Locale.ITALY)
private fun kmFmt(v: Double) = "%,.0f".format(Locale.ITALY, v)

@Composable
fun MaintScreen() {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    val carSel by Cars.active.collectAsState()
    var ver by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<MaintEntry>>(emptyList()) }
    var dues by remember { mutableStateOf<List<Due>>(emptyList()) }
    var odo by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(ver, tv, carSel) {
        withContext(Dispatchers.IO) {
            val car = Cars.activeId(ctx)
            val e = Db.get(ctx).maint(car)
            entries = e; dues = Maint.dues(ctx, car, e); odo = Maint.odometer(ctx, car)
        }
    }
    var editing by remember { mutableStateOf<MaintEntry?>(null) }
    var odoDialog by remember { mutableStateOf(false) }
    var intervalFor by remember { mutableStateOf<Due?>(null) }
    var showUnknown by remember { mutableStateOf(false) }

    editing?.let { e ->
        BackHandler { editing = null }
        EntryForm(e, odo) { saved -> editing = null; if (saved) { ver++; Backup.auto(ctx) } }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Header("Manutenzione", "Interventi fatti e prossime scadenze")

        // contachilometri
        Box2(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Contachilometri", color = C.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(odo?.let { kmFmt(it) + " km" } ?: "non impostato", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { odoDialog = true }) { Text("Aggiorna") }
            }
            Text(
                if (odo == null) "Scrivi i km del cruscotto: poi l'app li aggiorna da sola con i viaggi registrati."
                else "Si aggiorna da solo con i viaggi. Ogni tanto correggilo con il valore del cruscotto.",
                color = C.dim, fontSize = 12.sp
            )
        }

        Button(onClick = { editing = MaintEntry(0, System.currentTimeMillis(), odo ?: 0.0, "", emptyList(), 0.0, "", "") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("+  Registra un intervento") }

        // scadenze
        val known = dues.filter { it.status >= 0 }
        val unknown = dues.filter { it.status < 0 }
        Section("Prossime scadenze")
        if (known.isEmpty()) Text("Registra il primo intervento (per esempio l'ultimo tagliando) e qui vedrai cosa scade e quando.",
            color = C.dim, fontSize = 13.sp)
        known.forEach { d -> DueRow(d) { if (d.part.key != "stagionali") intervalFor = d } }
        if (unknown.isNotEmpty()) {
            TextButton(onClick = { showUnknown = !showUnknown }) {
                Text(if (showUnknown) "Nascondi voci mai registrate" else "Mostra voci mai registrate (${unknown.size})", color = C.dim)
            }
            if (showUnknown) unknown.forEach { d -> DueRow(d) { intervalFor = d } }
        }

        // spese
        if (entries.isNotEmpty()) {
            val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val thisYear = entries.filter { java.util.Calendar.getInstance().apply { timeInMillis = it.ts }.get(java.util.Calendar.YEAR) == y }.sumOf { it.cost }
            Section("Registro interventi")
            Text("Spesa quest'anno: ${"%.0f".format(thisYear)} € · totale registrato: ${"%.0f".format(entries.sumOf { it.cost })} €",
                color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            entries.forEach { e ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
                        .clickable { editing = e }.padding(14.dp)
                ) {
                    Row {
                        Text(e.type.ifEmpty { "Intervento" }, color = C.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (e.cost > 0) Text("${"%.0f".format(e.cost)} €", color = C.text)
                    }
                    Text(dFmt.format(Date(e.ts)) + (if (e.km > 0) " · ${kmFmt(e.km)} km" else "") + (if (e.place.isNotBlank()) " · ${e.place}" else ""),
                        color = C.dim, fontSize = 13.sp)
                    if (e.parts.isNotEmpty()) Text(e.parts.mapNotNull { Maint.part(it)?.label }.joinToString(", "),
                        color = C.text.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (e.notes.isNotBlank()) Text(e.notes, color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text("Gli intervalli sono indicativi per il 1.5 dCi: tocca una voce per adattarla al tuo libretto di manutenzione.",
            color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
    }

    if (odoDialog) NumberDialog("Km sul cruscotto", odo?.toInt()?.toString() ?: "", "km",
        onDismiss = { odoDialog = false }) { v ->
        v?.let { Maint.setOdometer(ctx, it.toDouble()); ver++; Backup.auto(ctx) }; odoDialog = false
    }

    intervalFor?.let { d -> IntervalDialog(d, onDismiss = { intervalFor = null }) { km, m ->
        Maint.setInterval(ctx, d.part, km, m); intervalFor = null; ver++; Backup.auto(ctx)
    } }
}

@Composable
private fun DueRow(d: Due, onClick: () -> Unit) {
    val col = when (d.status) { 2 -> C.bad; 1 -> C.warn; 0 -> C.ok; else -> C.dim }
    val left = listOfNotNull(
        d.kmLeft?.let { if (it <= 0) "superato di ${kmFmt(-it)} km" else "tra ${kmFmt(it)} km" },
        d.daysLeft?.let {
            when {
                it <= 0 -> "scaduto da ${-it} giorni"
                it < 60 -> "tra $it giorni"
                else -> "tra ${it / 30} mesi"
            }
        }
    ).joinToString(" · ")
    val every = listOfNotNull(d.km?.let { "${kmFmt(it.toDouble())} km" }, d.months?.let { "$it mesi" }).joinToString(" o ")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.card)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(col); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.part.label, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    d.status < 0 -> "Mai registrato" + (if (every.isNotEmpty()) " · ogni $every" else "")
                    else -> listOfNotNull(left.ifEmpty { null }, d.lastTs?.let { "ultimo ${dFmt.format(Date(it))}" }).joinToString(" · ")
                },
                color = if (d.status >= 1) col else C.dim, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun NumberDialog(title: String, initial: String, unit: String, onDismiss: () -> Unit, onOk: (Int?) -> Unit) {
    var txt by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(txt, { txt = it.filter { c -> c.isDigit() } }, suffix = { Text(unit) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { onOk(txt.toIntOrNull()) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun IntervalDialog(d: Due, onDismiss: () -> Unit, onOk: (Int?, Int?) -> Unit) {
    var km by remember { mutableStateOf(d.km?.toString() ?: "") }
    var mo by remember { mutableStateOf(d.months?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.part.label) },
        text = {
            Column {
                Text("Ogni quanto va fatto? Lascia vuoto un campo per non usarlo.", color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(km, { km = it.filter { c -> c.isDigit() } }, label = { Text("Chilometri") }, suffix = { Text("km") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(mo, { mo = it.filter { c -> c.isDigit() } }, label = { Text("Tempo") }, suffix = { Text("mesi") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(onClick = { onOk(km.toIntOrNull()?.takeIf { it > 0 }, mo.toIntOrNull()?.takeIf { it > 0 }) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EntryForm(start: MaintEntry, odo: Double?, done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var ts by remember { mutableLongStateOf(start.ts) }
    var km by remember { mutableStateOf(if (start.km > 0) start.km.toInt().toString() else "") }
    var type by remember { mutableStateOf(start.type) }
    var parts by remember { mutableStateOf(start.parts.toSet()) }
    var cost by remember { mutableStateOf(if (start.cost > 0) "%.2f".format(Locale.ITALY, start.cost) else "") }
    var place by remember { mutableStateOf(start.place) }
    var notes by remember { mutableStateOf(start.notes) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    val seasonal = type == "Cambio gomme stagionale"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = { done(false) }, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Annulla") }
        Header(if (start.id > 0) "Modifica intervento" else "Nuovo intervento")

        Section("Tipo di intervento")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Maint.TYPES.forEach { t ->
                FilterChip(selected = type == t.label, onClick = {
                    type = t.label
                    if (start.id == 0L || parts.isEmpty()) parts = t.preset.toSet()
                }, label = { Text(t.label) })
            }
        }

        Section("Quando")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f).padding(top = 8.dp)) { Text(dFmt.format(Date(ts))) }
            OutlinedTextField(km, { km = it.filter { c -> c.isDigit() } }, label = { Text("Km") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }

        if (seasonal) {
            Section("Cosa hai montato")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = "gomme_invernali" in parts, onClick = { parts = setOf("gomme_invernali") }, label = { Text("Gomme invernali") })
                FilterChip(selected = "gomme_estive" in parts, onClick = { parts = setOf("gomme_estive") }, label = { Text("Gomme estive") })
            }
        } else {
            Section("Cosa è stato fatto")
            Box2(Modifier.fillMaxWidth()) {
                Maint.PARTS.filter { it.key != "gomme_invernali" && it.key != "gomme_estive" }.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { parts = if (p.key in parts) parts - p.key else parts + p.key },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = p.key in parts, onCheckedChange = { parts = if (it) parts + p.key else parts - p.key })
                        Text(p.label, color = C.text, fontSize = 14.sp)
                    }
                }
            }
        }

        Section("Dettagli")
        OutlinedTextField(cost, { cost = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Costo") }, suffix = { Text("€") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(place, { place = it }, label = { Text("Officina") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(notes, { notes = it }, label = { Text("Note (marca olio, gomme, ricambi…)") }, minLines = 3, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val k = km.toDoubleOrNull() ?: 0.0
                val e = MaintEntry(start.id, ts, k, type.ifEmpty { "Intervento" }, parts.toList(),
                    cost.replace(',', '.').toDoubleOrNull() ?: 0.0, place.trim(), notes.trim())
                Db.get(ctx).saveMaint(e, Cars.activeId(ctx))
                // i km dell'intervento aggiornano il contachilometri se sono il dato più recente
                if (k > 0 && ts >= Maint.odoTs(ctx) - 60_000) Maint.setOdometer(ctx, k, ts)
                done(true)
            },
            enabled = type.isNotEmpty() || parts.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Salva intervento") }
        if (start.id > 0) TextButton(onClick = { confirmDel = true }, modifier = Modifier.fillMaxWidth()) { Text("Elimina intervento", color = C.bad) }
        if (odo != null && km.isEmpty()) Text("Suggerimento: i km attuali stimati sono ${kmFmt(odo)}.", color = C.dim, fontSize = 12.sp)
        Spacer(Modifier.height(32.dp))
    }

    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = ts)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { ts = it + 12 * 3600_000L }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Annulla") } }
        ) { DatePicker(st) }
    }
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false },
        confirmButton = { TextButton(onClick = { Db.get(ctx).deleteMaint(start.id); confirmDel = false; done(true) }) { Text("Elimina", color = C.bad) } },
        dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("Annulla") } },
        title = { Text("Eliminare l'intervento?") }
    )
}

/** Riquadro per la schermata Salute quando c'è qualcosa in scadenza. */
@Composable
fun MaintAlert(goMaint: () -> Unit) {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    val carSel by Cars.active.collectAsState()
    var due by remember { mutableStateOf<List<Due>>(emptyList()) }
    LaunchedEffect(tv, carSel) { due = withContext(Dispatchers.IO) { Maint.dues(ctx).filter { it.status >= 1 } } }
    if (due.isEmpty()) return
    val bad = due.any { it.status == 2 }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
            .clickable(onClick = goMaint).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (bad) C.bad else C.warn); Spacer(Modifier.width(8.dp))
            Text("Manutenzione: ${due.size} ${if (due.size == 1) "scadenza" else "scadenze"}", color = C.text, fontWeight = FontWeight.SemiBold)
        }
        Text(due.take(3).joinToString(" · ") { it.part.label }, color = C.dim, fontSize = 13.sp)
    }
}
