package it.eremite.autosalute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.data.Trip
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Somme di un gruppo di viaggi. */
class Totals(trips: List<Trip>) {
    val n = trips.size
    val km = trips.sumOf { it.km }
    private val withFuel = trips.filter { it.fuelL > 0 && !it.fuelL.isNaN() && it.km > 0 }
    val litres = withFuel.sumOf { it.fuelL }
    val l100: Double = withFuel.sumOf { it.km }.let { k -> if (k > 1) litres / k * 100 else Double.NaN }
    val hours = trips.sumOf { it.dur } / 3600.0
    val eco: Double = trips.mapNotNull { it.metrics["eco_score"] }.let { if (it.isEmpty()) Double.NaN else it.average() }
}

private fun cal(t: Long = System.currentTimeMillis()) = Calendar.getInstance(Locale.ITALY).apply { timeInMillis = t }

private fun Calendar.dayStart(): Calendar = apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

@Composable
fun ConsumptionStats(trips: List<Trip>) {
    val now = System.currentTimeMillis()
    val day = 24L * 3600 * 1000
    val today = cal().dayStart().timeInMillis
    val week = cal().dayStart().apply { firstDayOfWeek = Calendar.MONDAY; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }.timeInMillis
        .let { if (it > now) it - 7 * day else it }
    val month = cal().dayStart().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    val prevMonth = cal(month).apply { add(Calendar.MONTH, -1) }.timeInMillis
    val year = cal().dayStart().apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

    // Da quando registriamo questa auto (inizio del giorno del primo viaggio).
    // Un periodo che comincia prima di questa data non è completo: mostriamo "–".
    val since = trips.minOfOrNull { it.start }?.let { cal(it).dayStart().timeInMillis } ?: now
    class Per(val label: String, val from: Long, val list: List<Trip>) { val complete get() = from >= since }
    val periods = listOf(
        Per("Oggi", today, trips.filter { it.start >= today }),
        Per("Questa settimana", week, trips.filter { it.start >= week }),
        Per("Ultimi 7 giorni", now - 7 * day, trips.filter { it.start >= now - 7 * day }),
        Per("Questo mese", month, trips.filter { it.start >= month }),
        Per("Ultimi 30 giorni", now - 30 * day, trips.filter { it.start >= now - 30 * day }),
        Per("Mese scorso", prevMonth, trips.filter { it.start in prevMonth until month }),
        Per("Ultimi 90 giorni", now - 90 * day, trips.filter { it.start >= now - 90 * day }),
        Per("Quest'anno", year, trips.filter { it.start >= year }),
        Per("Da sempre", Long.MAX_VALUE, trips),
    )

    Section("Consumi per periodo")
    Box2(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("", Modifier.weight(1.6f))
            listOf("km", "litri", "L/100", "eco").forEach {
                Text(it, color = C.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
        periods.forEach { per ->
            val t = Totals(per.list)
            val ok = per.complete
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(per.label, color = if (ok) C.text else C.dim, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
                Cell(if (!ok || t.n == 0) "–" else "%.0f".format(t.km))
                Cell(if (!ok || t.litres <= 0) "–" else "%.1f".format(t.litres))
                Cell(if (!ok || t.l100.isNaN()) "–" else "%.1f".format(t.l100), C.ok, true)
                Cell(if (!ok || t.eco.isNaN()) "–" else "%.0f".format(t.eco))
            }
        }
        if (trips.isNotEmpty()) Text(
            "Dati dal " + SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(java.util.Date(since)) +
                ": i periodi che iniziano prima di questa data compaiono quando sono completi.",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
        )
    }

    // gasolio medio al giorno, solo su giorni interi (oggi escluso perché non è finito)
    val fullDays = ((today - since) / day).toInt()
    if (fullDays >= 1) {
        val past = trips.filter { it.start < today }
        val t = Totals(past)
        val drivingDays = past.map { cal(it.start).dayStart().timeInMillis }.toSet().size
        if (t.litres > 0) {
            Section("Gasolio al giorno")
            Box2(Modifier.fillMaxWidth()) {
                val perDay = t.litres / fullDays
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f".format(perDay), color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(" litri al giorno di media", color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text("Circa ${"%.2f".format(perDay * 1.75)} € al giorno e ${"%.0f".format(perDay * 30 * 1.75)} € al mese (gasolio a 1,75 €/L).",
                    color = C.text, fontSize = 13.sp)
                if (drivingDays in 1 until fullDays)
                    Text("Nei $drivingDays giorni in cui hai guidato: ${"%.1f".format(t.litres / drivingDays)} litri e ${"%.0f".format(t.km / drivingDays)} km al giorno.",
                        color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Calcolato su $fullDays giorni interi · ${"%.0f".format(t.km)} km · ${"%.0f".format(t.litres)} litri.",
                    color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // ultime 12 settimane
    val weeks = (11 downTo 0).mapNotNull { i ->
        val from = week - i * 7 * day
        if (from < since) null else Totals(trips.filter { it.start in from until from + 7 * day })
    }
    if (weeks.count { !it.l100.isNaN() } >= 2) {
        Section("Consumo medio settimana per settimana (L/100km)")
        Box2(Modifier.fillMaxWidth()) {
            LineChart(weeks.map { it.l100 }, C.ok, dots = true)
            Text("settimane complete, a destra la più recente", color = C.dim, fontSize = 11.sp)
        }
    }

    // storico di tutti i mesi
    val fmt = SimpleDateFormat("MMMM yyyy", Locale.ITALY)
    val byMonth = trips.groupBy { cal(it.start).apply { set(Calendar.DAY_OF_MONTH, 1) }.dayStart().timeInMillis }
        .toSortedMap(compareByDescending { it })
    if (byMonth.isNotEmpty()) {
        Section("Storico mese per mese")
        Box2(Modifier.fillMaxWidth()) {
            byMonth.forEach { (m, list) ->
                val t = Totals(list)
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    val tag = when { m < since -> " (parziale)"; m == month -> " (in corso)"; else -> "" }
                    Text(fmt.format(m).replaceFirstChar { it.uppercase() } + tag, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
                    Cell("%.0f km".format(t.km))
                    Cell(if (t.litres <= 0) "–" else "%.0f L".format(t.litres))
                    Cell(if (t.l100.isNaN()) "–" else "%.1f".format(t.l100), C.ok, true)
                    Cell("${t.n} v.")
                }
            }
        }
    }
}

@Composable
private fun RowScope.Cell(v: String, color: androidx.compose.ui.graphics.Color = C.text, bold: Boolean = false) {
    Text(v, color = color, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
}
