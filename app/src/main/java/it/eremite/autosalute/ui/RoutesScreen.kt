package it.eremite.autosalute.ui

import android.content.Context
import android.location.Geocoder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import it.eremite.autosalute.Live
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.GeoPoint
import it.eremite.autosalute.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.*
import org.osmdroid.util.GeoPoint as OsmPoint

/** Un viaggio con il suo percorso GPS. */
class RTrip(val trip: Trip, val pts: List<GeoPoint>, val cells: Set<Long>)

/** Un modo diverso di fare lo stesso tragitto. */
class Variant(val letter: String, val trips: MutableList<RTrip>, val color: Color) {
    val n get() = trips.size
    val minutes get() = trips.map { it.trip.dur / 60 }.average()
    val km get() = trips.map { it.trip.km }.average()
    val litres: Double? get() = trips.map { it.trip.fuelL }.filter { !it.isNaN() && it > 0 }.takeIf { it.isNotEmpty() }?.average()
    val eco: Double? get() = trips.mapNotNull { it.trip.metrics["eco_score"] }.takeIf { it.isNotEmpty() }?.average()
    val sample get() = trips.maxBy { it.pts.size }.pts
}

class Pair2(val from: String, val to: String, val variants: List<Variant>) {
    val total get() = variants.sumOf { it.n }
}

private val COLORS = listOf(Color(0xFF3DDC97), Color(0xFF5AA9FF), Color(0xFFFFB020), Color(0xFFFF5A5F), Color(0xFFB57BFF), Color(0xFF4DD0E1))

private fun dist(a: GeoPoint, lat: Double, lon: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat - a.lat); val dLon = Math.toRadians(lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** Celle di circa 300 metri attraversate dal percorso: servono a capire se due percorsi sono "la stessa strada". */
private fun cells(pts: List<GeoPoint>): Set<Long> =
    pts.map { (floor(it.lat / 0.003).toLong() shl 32) or (floor(it.lon / 0.004).toLong() and 0xffffffffL) }.toSet()

private fun jaccard(a: Set<Long>, b: Set<Long>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

private fun placeName(ctx: Context, p: GeoPoint, i: Int): String {
    val key = "place_%.3f_%.3f".format(Locale.US, p.lat, p.lon)
    val pr = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
    pr.getString(key, null)?.let { return it }
    val name = try {
        @Suppress("DEPRECATION")
        Geocoder(ctx, Locale.ITALY).getFromLocation(p.lat, p.lon, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(a.thoroughfare, a.locality ?: a.subAdminArea).joinToString(", ").ifEmpty { null }
        }
    } catch (_: Exception) { null }
    if (name != null) pr.edit().putString(key, name).apply()
    return name ?: "Luogo $i"
}

/** Raggruppa i viaggi per partenza-arrivo, poi per strada percorsa. */
suspend fun loadRoutes(ctx: Context): List<Pair2> = withContext(Dispatchers.IO) {
    val db = Db.get(ctx)
    val car = Cars.activeId(ctx)
    val trips = db.trips(car, 400).filter { it.km >= 2 }
    val rts = trips.mapNotNull { t ->
        val p = db.points(t.id)
        if (p.size < 10) null else RTrip(t, p, cells(p))
    }
    // luoghi: partenze e arrivi entro 500 metri sono lo stesso posto
    val places = ArrayList<GeoPoint>()
    fun place(p: GeoPoint): Int {
        val i = places.indexOfFirst { dist(it, p.lat, p.lon) < 500 }
        if (i >= 0) return i
        places.add(p); return places.size - 1
    }
    val groups = rts.groupBy { place(it.pts.first()) to place(it.pts.last()) }.filter { (k, v) -> k.first != k.second && v.size >= 2 }
    groups.map { (k, list) ->
        val vars = ArrayList<Variant>()
        for (r in list.sortedByDescending { it.trip.start }) {
            val v = vars.firstOrNull { v -> v.trips.any { jaccard(it.cells, r.cells) >= 0.55 } }
            if (v != null) v.trips.add(r)
            else vars.add(Variant(('A' + vars.size).toString(), mutableListOf(r), COLORS[vars.size % COLORS.size]))
        }
        Pair2(placeName(ctx, places[k.first], k.first + 1), placeName(ctx, places[k.second], k.second + 1), vars.sortedByDescending { it.n })
    }.sortedByDescending { it.total }
}

@Composable
fun RoutesScreen(close: () -> Unit) {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    var pairs by remember { mutableStateOf<List<Pair2>?>(null) }
    var open by remember { mutableStateOf<Pair2?>(null) }
    LaunchedEffect(tv) { pairs = loadRoutes(ctx) }
    open?.let { p ->
        BackHandler { open = null }
        PairDetail(p) { open = null }
        return
    }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = close, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Viaggi") }
        Header("Confronto percorsi", "Stessa partenza e stesso arrivo, strade diverse: quale conviene?")
        val list = pairs
        when {
            list == null -> Text("Calcolo in corso…", color = C.dim, modifier = Modifier.padding(top = 16.dp))
            list.isEmpty() -> Text(
                "Servono almeno 2 viaggi con GPS tra gli stessi due posti. Controlla che in Imposta → Posizione GPS sia attivo \"Consenti sempre\".",
                color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)
            )
            else -> list.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(C.card).clickable { open = p }.padding(14.dp)
                ) {
                    Text("${p.from}  →  ${p.to}", color = C.text, fontWeight = FontWeight.SemiBold)
                    Text("${p.total} viaggi · ${p.variants.size} ${if (p.variants.size == 1) "percorso" else "percorsi diversi"}", color = C.dim, fontSize = 13.sp)
                    if (p.variants.size >= 2) {
                        val fast = p.variants.filter { it.n >= 1 }.minBy { it.minutes }
                        val cheap = p.variants.filter { it.litres != null }.minByOrNull { it.litres!! }
                        Text("Più veloce: ${fast.letter}" + (cheap?.let { " · Consuma meno: ${it.letter}" } ?: ""), color = C.ok, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PairDetail(p: Pair2, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = back, modifier = Modifier.padding(top = 12.dp)) { Text("‹ Percorsi") }
        Header(p.to, "da ${p.from}")
        MultiRouteMap(p.variants.map { it.sample to it.color }, Modifier.fillMaxWidth().height(300.dp).padding(top = 12.dp))
        val fastest = p.variants.minBy { it.minutes }
        val cheapest = p.variants.filter { it.litres != null }.minByOrNull { it.litres!! }
        Section("Percorsi")
        p.variants.forEach { v ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(v.color))
                    Spacer(Modifier.width(8.dp))
                    Text("Percorso ${v.letter}", color = C.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${v.n} ${if (v.n == 1) "volta" else "volte"}", color = C.dim, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Cell2("Tempo", "${v.minutes.roundToInt()} min", v === fastest && p.variants.size > 1)
                    Cell2("Strada", "%.1f km".format(v.km), false)
                    Cell2("Gasolio", v.litres?.let { "%.2f L".format(it) } ?: "–", v === cheapest && p.variants.size > 1)
                    Cell2("Voto eco", v.eco?.let { "${it.roundToInt()}" } ?: "–", false)
                }
            }
        }
        if (p.variants.size >= 2) {
            Section("In sintesi")
            Box2(Modifier.fillMaxWidth()) {
                val slow = p.variants.maxBy { it.minutes }
                Text("Il percorso ${fastest.letter} è il più veloce: risparmi circa ${(slow.minutes - fastest.minutes).roundToInt()} minuti rispetto al ${slow.letter}.",
                    color = C.text, fontSize = 14.sp)
                if (cheapest != null) {
                    val worst = p.variants.filter { it.litres != null }.maxBy { it.litres!! }
                    val diff = worst.litres!! - cheapest.litres!!
                    Text("Il percorso ${cheapest.letter} consuma meno: ${"%.2f".format(diff)} litri in meno a viaggio rispetto al ${worst.letter} " +
                        "(circa ${"%.2f".format(diff * 1.75)} € con il gasolio a 1,75 €/L).", color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Text("Più volte fai un percorso, più i numeri sono affidabili: il traffico cambia da un giorno all'altro.",
                    color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Cell2(label: String, v: String, best: Boolean) {
    Column {
        Text(v, color = if (best) C.ok else C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(if (best) "$label ★" else label, color = if (best) C.ok else C.dim, fontSize = 11.sp)
    }
}

/** Mappa con più percorsi colorati. */
@Composable
fun MultiRouteMap(routes: List<Pair<List<GeoPoint>, Color>>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember {
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = ctx.packageName
    }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView(factory = { map }, modifier = modifier.clip(RoundedCornerShape(14.dp)), update = { m ->
        m.overlays.clear()
        val all = ArrayList<OsmPoint>()
        routes.forEach { (pts, col) ->
            val op = pts.map { OsmPoint(it.lat, it.lon) }
            all.addAll(op)
            if (op.size >= 2) m.overlays.add(Polyline(m).apply {
                setPoints(op); outlinePaint.color = col.toArgb(); outlinePaint.strokeWidth = 11f
            })
        }
        if (all.size >= 2) m.post { try { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), false, 60) } catch (_: Exception) {} }
        m.invalidate()
    })
}
