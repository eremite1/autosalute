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

    val periods = listOf(
        "Oggi" to trips.filter { it.start >= today },
        "Questa settimana" to trips.filter { it.start >= week },
        "Ultimi 7 giorni" to trips.filter { it.start >= now - 7 * day },
        "Questo mese" to trips.filter { it.start >= month },
        "Ultimi 30 giorni" to trips.filter { it.start >= now - 30 * day },
        "Mese scorso" to trips.filter { it.start in prevMonth until month },
        "Ultimi 90 giorni" to trips.filter { it.start >= now - 90 * day },
        "Quest'anno" to trips.filter { it.start >= year },
        "Da sempre" to trips,
    )

    Section("Consumi per periodo")
    Box2(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("", Modifier.weight(1.6f))
            listOf("km", "litri", "L/100", "eco").forEach {
                Text(it, color = C.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
        periods.forEach { (label, list) ->
            val t = Totals(list)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
                Cell(if (t.n == 0) "–" else "%.0f".format(t.km))
                Cell(if (t.litres <= 0) "–" else "%.1f".format(t.litres))
                Cell(if (t.l100.isNaN()) "–" else "%.1f".format(t.l100), C.ok, true)
                Cell(if (t.eco.isNaN()) "–" else "%.0f".format(t.eco))
            }
        }
    }

    // ultime 12 settimane
    val weeks = (11 downTo 0).map { i ->
        val from = week - i * 7 * day
        Totals(trips.filter { it.start in from until from + 7 * day })
    }
    if (weeks.count { !it.l100.isNaN() } >= 2) {
        Section("Consumo medio settimana per settimana (L/100km)")
        Box2(Modifier.fillMaxWidth()) {
            LineChart(weeks.map { it.l100 }, C.ok, dots = true)
            Text("ultime 12 settimane, a destra la più recente", color = C.dim, fontSize = 11.sp)
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
                    Text(fmt.format(m).replaceFirstChar { it.uppercase() }, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
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
