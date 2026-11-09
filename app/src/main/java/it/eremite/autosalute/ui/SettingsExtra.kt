package it.eremite.autosalute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import it.eremite.autosalute.data.Backup
import it.eremite.autosalute.data.Car
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.service.GpsTracker
import it.eremite.autosalute.service.PhoneBattery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberCars(): List<Car> {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    val car by Cars.active.collectAsState()
    var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
    LaunchedEffect(tv, car) { cars = withContext(Dispatchers.IO) { Db.get(ctx).cars() } }
    return cars
}

/** Nome dell'auto selezionata con menu per cambiarla. */
@Composable
fun CarSwitcher(goSetup: () -> Unit) {
    val ctx = LocalContext.current
    val cars = rememberCars()
    val active by Cars.active.collectAsState()
    val id = if (active == 0L) Cars.activeId(ctx) else active
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 24.dp)) {
        Row(Modifier.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(cars.firstOrNull { it.id == id }?.name ?: "La tua auto", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.text)
            Text("  ▾", fontSize = 20.sp, color = C.dim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            cars.forEach { c ->
                DropdownMenuItem(text = { Text((if (c.id == id) "✓  " else "     ") + c.name) }, onClick = { Cars.select(ctx, c.id); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Gestisci auto…") }, onClick = { open = false; goSetup() })
        }
    }
}

@Composable
fun CarsBox(act: MainActivity) {
    val cars = rememberCars()
    val active by Cars.active.collectAsState()
    val id = if (active == 0L) Cars.activeId(act) else active
    var rename by remember { mutableStateOf<Car?>(null) }
    var delete by remember { mutableStateOf<Car?>(null) }
    var adding by remember { mutableStateOf(false) }
    Box2(Modifier.fillMaxWidth()) {
        Text("Ogni auto ha viaggi, statistiche, manutenzione e diagnosi separati. L'app riconosce l'auto dal numero di telaio e passa al profilo giusto da sola.",
            color = C.dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        cars.forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (c.id == id) C.card2 else C.card).clickable { Cars.select(act, c.id) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = c.id == id, onClick = { Cars.select(act, c.id) })
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = C.text, fontWeight = FontWeight.SemiBold)
                    Text(c.vin?.let { "Telaio $it" } ?: "Telaio non ancora letto", color = C.dim, fontSize = 11.sp)
                }
                TextButton(onClick = { rename = c }) { Text("Modifica") }
            }
        }
        TextButton(onClick = { adding = true }) { Text("+ Aggiungi auto") }
    }

    if (adding) NameDialog("Nuova auto", "") { n ->
        adding = false
        if (n != null) { val nid = Db.get(act).addCar(n, null); Cars.select(act, nid); Backup.auto(act) }
    }
    rename?.let { c ->
        CarEditDialog(c, canDelete = cars.size > 1,
            onDismiss = { rename = null },
            onSave = { n, clearVin -> Db.get(act).renameCar(c.id, n); if (clearVin) Db.get(act).setCarVin(c.id, null); rename = null; Live.tripsVersion.value++; Backup.auto(act) },
            onDelete = { rename = null; delete = c })
    }
    delete?.let { c ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Eliminare ${c.name}?") },
            text = { Text("Verranno cancellati tutti i suoi viaggi, la manutenzione e le diagnosi. Non si può annullare.") },
            confirmButton = {
                TextButton(onClick = {
                    Db.get(act).deleteCar(c.id)
                    if (c.id == id) Cars.select(act, Db.get(act).cars().first().id) else Live.tripsVersion.value++
                    delete = null; Backup.auto(act)
                }) { Text("Elimina", color = C.bad) }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Annulla") } }
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, done: (String?) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { done(null) },
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, label = { Text("Nome (es. Panda di Marco)") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { done(t.trim().ifEmpty { null }) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = { done(null) }) { Text("Annulla") } }
    )
}

@Composable
private fun CarEditDialog(c: Car, canDelete: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit, onDelete: () -> Unit) {
    var t by remember { mutableStateOf(c.name) }
    var clearVin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica auto") },
        text = {
            Column {
                OutlinedTextField(t, { t = it }, label = { Text("Nome") }, singleLine = true)
                if (c.vin != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearVin, onCheckedChange = { clearVin = it })
                        Text("Dimentica il telaio ${c.vin} (se era sbagliato)", fontSize = 13.sp)
                    }
                }
                if (canDelete) TextButton(onClick = onDelete) { Text("Elimina questa auto e i suoi dati", color = C.bad) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t.trim().ifEmpty { c.name }, clearVin) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun GpsBox(act: MainActivity) {
    val r by act.refresh
    val fine = remember(r) { GpsTracker.allowed(act) }
    val bg = remember(r) { GpsTracker.background(act) }
    Box2(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (fine && bg) C.ok else if (fine) C.warn else C.dim); Spacer(Modifier.width(8.dp))
            Text(
                when {
                    fine && bg -> "Percorso registrato in ogni viaggio"
                    fine -> "Manca \"Consenti sempre\""
                    else -> "Posizione non consentita"
                }, color = C.text, fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            if (fine && bg) "Vedi il percorso di ogni viaggio, la posizione dal vivo nella scheda Eco e dove hai parcheggiato."
            else "Serve il permesso alla posizione con l'opzione \"Consenti sempre\", perché l'app registra anche a schermo spento.",
            color = C.dim, fontSize = 12.sp
        )
        if (!(fine && bg)) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { act.askLocation() }) { Text(if (!fine) "Consenti posizione" else "Imposta \"Consenti sempre\"") }
        }
    }
}

@Composable
fun BatteryBox(act: MainActivity) {
    val trips = rememberTrips()
    val now = System.currentTimeMillis()
    val week = trips.filter { it.start > now - 7L * 24 * 3600 * 1000 }
    val mahWeek = week.sumOf { it.metrics["app_mah"] ?: 0.0 }
    val withBatt = trips.filter { it.metrics["app_mah"] != null }
    val cap = remember { PhoneBattery.capacityMah(act) }
    Box2(Modifier.fillMaxWidth()) {
        if (withBatt.isEmpty()) {
            Text("Il consumo viene misurato da ora in poi, viaggio per viaggio.", color = C.dim, fontSize = 13.sp)
        } else {
            val perTrip = withBatt.take(20).map { it.metrics["app_mah"]!! }.average()
            val perDay = mahWeek / 7.0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("%.1f%%".format(perDay / cap * 100), color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("al giorno (media 7 gg)", color = C.dim, fontSize = 11.sp)
                }
                Column {
                    Text("${perTrip.toInt()} mAh", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("per viaggio", color = C.dim, fontSize = 11.sp)
                }
                Column {
                    Text("${mahWeek.toInt()} mAh", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("ultimi 7 giorni", color = C.dim, fontSize = 11.sp)
                }
            }
            val drops = withBatt.mapNotNull { it.metrics["phone_batt_drop"] }
            if (drops.isNotEmpty()) Text("Durante i viaggi senza ricarica il telefono perde in media ${"%.0f".format(drops.average())}% (tutto il telefono, schermo compreso).",
                color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Text("Stima basata sul lavoro dell'app (calcolo, Bluetooth, GPS), schermo escluso. Batteria del telefono: ${cap.toInt()} mAh.",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        TextButton(onClick = { act.openAppInfo() }) { Text("Dati ufficiali di Android") }
    }
}
