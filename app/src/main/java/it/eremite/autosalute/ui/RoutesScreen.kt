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

/** Un passaggio su un tratto: da un punto A a un punto B dentro un viaggio. */
class Seg(val trip: Trip, val pts: List<GeoPoint>, val minutes: Double, val km: Double, val litres: Double?, val cells: Set<Long>)

/** Un modo diverso di fare lo stesso tratto. */
class Variant(val letter: String, val segs: MutableList<Seg>, val color: Color) {
    val n get() = segs.size
    val minutes get() = segs.map { it.minutes }.average()
    val km get() = segs.map { it.km }.average()
    val litres: Double? get() = segs.mapNotNull { it.litres }.takeIf { it.isNotEmpty() }?.average()
    val eco: Double? get() = segs.mapNotNull { it.trip.metrics["eco_score"] }.takeIf { it.isNotEmpty() }?.average()
    val sample get() = segs.maxBy { it.pts.size }.pts
}

/** Un tratto da A a B percorso con strade diverse. */
class Pair2(val from: String, val to: String, val variants: List<Variant>, val whole: Boolean) {
    val total get() = variants.sumOf { it.n }
}

private val COLORS = listOf(Color(0xFF3DDC97), Color(0xFF5AA9FF), Color(0xFFFFB020), Color(0xFFFF5A5F), Color(0xFFB57BFF), Color(0xFF4DD0E1))

private fun dist(a: GeoPoint, lat: Double, lon: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat - a.lat); val dLon = Math.toRadians(lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
private fun dist(a: GeoPoint, b: GeoPoint) = dist(a, b.lat, b.lon)

/** Cella di circa 300 metri. */
private fun cellOf(lat: Double, lon: Double): Long = (floor(lat / 0.003).toLong() shl 32) or (floor(lon / 0.004).toLong() and 0xffffffffL)
private fun cellOf(p: GeoPoint) = cellOf(p.lat, p.lon)
private fun neigh(c: Long): List<Long> {
    val la = c shr 32; val lo = (c and 0xffffffffL).toInt().toLong()
    val out = ArrayList<Long>(9)
    for (dl in -1..1) for (dn in -1..1) out.add(((la + dl) shl 32) or ((lo + dn) and 0xffffffffL))
    return out
}
private fun cells(pts: List<GeoPoint>): Set<Long> = pts.map { cellOf(it) }.toSet()
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
    return name ?: "Punto $i"
}

/** Percorso di un viaggio ridotto a celle (senza ripetizioni consecutive), con il punto GPS di ciascuna. */
private class Path(val trip: Trip, val pts: List<GeoPoint>) {
    val cellIdx = ArrayList<Long>()
    val ptIdx = ArrayList<Int>()
    init {
        pts.forEachIndexed { i, p ->
            val c = cellOf(p)
            if (cellIdx.isEmpty() || cellIdx.last() != c) { cellIdx.add(c); ptIdx.add(i) }
        }
    }
    val set: Set<Long> = cellIdx.toSet()
    val near: Set<Long> by lazy { cellIdx.flatMap { neigh(it) }.toSet() }
}

/**
 * Trova i tratti con strade alternative:
 *  - due viaggi corrono insieme, si separano in un punto A, poi si ritrovano in un punto B:
 *    il tratto A→B ha (almeno) due varianti;
 *  - stessi punti di partenza e arrivo con strade diverse (come prima).
 * Poi raccoglie TUTTI i viaggi che passano da A e poi da B e confronta i passaggi.
 */
suspend fun loadRoutes(ctx: Context): List<Pair2> = withContext(Dispatchers.Default) {
    val db = Db.get(ctx)
    val car = Cars.activeId(ctx)
    val trips = db.trips(car, 250).filter { it.km >= 1.5 }
    val paths = trips.mapNotNull { t ->
        val p = db.points(t.id)
        if (p.size < 10) null else Path(t, p)
    }

    // 1) coppie di punti A→B dove due viaggi si separano e poi si ritrovano
    val anchors = ArrayList<Triple<GeoPoint, GeoPoint, Boolean>>()
    fun addAnchor(a: GeoPoint, b: GeoPoint, whole: Boolean) {
        if (dist(a, b) < 800) return
        if (anchors.none { dist(it.first, a) < 600 && dist(it.second, b) < 600 }) anchors.add(Triple(a, b, whole))
    }
    for (i in paths.indices) for (j in paths.indices) {
        if (i == j) continue
        val pi = paths[i]; val pj = paths[j]
        if (pi.set.intersect(pj.near).size < 3) continue
        val posJ = HashMap<Long, Int>()
        pj.cellIdx.forEachIndexed { k, c -> for (n in neigh(c)) posJ.putIfAbsent(n, k) }
        var lastShared = -1
        var k = 0
        while (k < pi.cellIdx.size) {
            val shared = posJ.containsKey(pi.cellIdx[k])
            if (shared) {
                if (lastShared >= 0 && k - lastShared >= 3) {
                    // tratto diverso tra lastShared e k in i: controlla che j vada da A a B per un'altra strada
                    val ja = posJ[pi.cellIdx[lastShared]]!!; val jb = posJ[pi.cellIdx[k]]!!
                    if (jb > ja) {
                        val segI = pi.cellIdx.subList(lastShared, k + 1).toSet()
                        val segJ = pj.cellIdx.subList(ja, jb + 1).toSet()
                        if (jaccard(segI, segJ) < 0.5) addAnchor(pi.pts[pi.ptIdx[lastShared]], pi.pts[pi.ptIdx[k]], false)
                    }
                }
                lastShared = k
            }
            k++
        }
    }
    // 2) stessi punti di partenza e arrivo
    for (i in paths.indices) for (j in i + 1 until paths.size) {
        val a = paths[i].pts; val b = paths[j].pts
        if (dist(a.first(), b.first()) < 500 && dist(a.last(), b.last()) < 500) addAnchor(a.first(), a.last(), true)
    }

    // 3) per ogni coppia A→B, estrai il passaggio da tutti i viaggi che ci passano
    val samplesCache = HashMap<Long, List<it.eremite.autosalute.data.Sample>>()
    fun segOf(p: Path, a: GeoPoint, b: GeoPoint): Seg? {
        val ia = p.pts.indexOfFirst { dist(it, a) < 350 }
        if (ia < 0) return null
        var ib = -1
        for (x in ia + 1 until p.pts.size) if (dist(p.pts[x], b) < 350) { ib = x; break }
        if (ib < 0) return null
        val sub = p.pts.subList(ia, ib + 1)
        // una sosta lunga dentro il tratto lo rende non confrontabile
        if (sub.zipWithNext().any { (x, y) -> y.ts - x.ts > 300_000 }) return null
        var km = 0.0
        for (x in 1 until sub.size) km += dist(sub[x - 1], sub[x]) / 1000.0
        val minutes = (sub.last().ts - sub.first().ts) / 60000.0
        if (km < 0.8 || minutes <= 0.3) return null
        val smp = samplesCache.getOrPut(p.trip.id) { db.samples(p.trip.id) }
        val inSeg = smp.filter { it.ts in sub.first().ts..sub.last().ts && !it.fuel.isNaN() }
        val litres = if (inSeg.size >= 10) {
            var l = 0.0
            for (x in 1 until inSeg.size) l += inSeg[x].fuel * ((inSeg[x].ts - inSeg[x - 1].ts).coerceAtMost(5000)) / 3_600_000.0
            l
        } else if (!p.trip.fuelL.isNaN() && p.trip.fuelL > 0 && p.trip.km > 0) p.trip.fuelL * km / p.trip.km else null
        return Seg(p.trip, sub, minutes, km, litres, cells(sub))
    }

    val out = ArrayList<Pair2>()
    var idx = 1
    for ((a, b, whole) in anchors) {
        val segs = paths.mapNotNull { segOf(it, a, b) }
        if (segs.size < 2) continue
        val vars = ArrayList<Variant>()
        for (s in segs.sortedByDescending { it.trip.start }) {
            val v = vars.firstOrNull { v -> v.segs.any { jaccard(it.cells, s.cells) >= 0.55 } }
            if (v != null) v.segs.add(s)
            else vars.add(Variant(('A' + vars.size).toString(), mutableListOf(s), COLORS[vars.size % COLORS.size]))
        }
        if (vars.size < 2) continue
        out.add(Pair2(placeName(ctx, a, idx++), placeName(ctx, b, idx++), vars.sortedByDescending { it.n }, whole))
    }
    // togli i doppioni (stesso nome di partenza e arrivo)
    out.distinctBy { it.from + "→" + it.to }.sortedByDescending { it.total }
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
        Header("Confronto percorsi", "Tratti che hai fatto con strade diverse: quale conviene?")
        val list = pairs
        when {
            list == null -> Text("Calcolo in corso…", color = C.dim, modifier = Modifier.padding(top = 16.dp))
            list.isEmpty() -> Text(
                "Ancora nessun tratto fatto con strade diverse. Appena due viaggi si separano e poi si ritrovano (o vai nello stesso posto per strade diverse), il confronto compare qui. Controlla che in Imposta → Posizione GPS sia attivo \"Consenti sempre\".",
                color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)
            )
            else -> list.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(C.card).clickable { open = p }.padding(14.dp)
                ) {
                    Text("${p.from}  →  ${p.to}", color = C.text, fontWeight = FontWeight.SemiBold)
                    Text("${p.total} passaggi · ${p.variants.size} strade diverse" + if (p.whole) "" else " · tratto di viaggi più lunghi", color = C.dim, fontSize = 13.sp)
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
        Header("Fino a ${p.to}", "da ${p.from}")
        MultiRouteMap(p.variants.map { it.sample to it.color }, Modifier.fillMaxWidth().height(300.dp).padding(top = 12.dp))
        val fastest = p.variants.minBy { it.minutes }
        val cheapest = p.variants.filter { it.litres != null }.minByOrNull { it.litres!! }
        Section("Strade a confronto")
        p.variants.forEach { v ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(v.color))
                    Spacer(Modifier.width(8.dp))
                    Text("Strada ${v.letter}", color = C.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                Text("La strada ${fastest.letter} è la più veloce: risparmi circa ${(slow.minutes - fastest.minutes).roundToInt()} minuti rispetto alla ${slow.letter}.",
                    color = C.text, fontSize = 14.sp)
                if (cheapest != null) {
                    val worst = p.variants.filter { it.litres != null }.maxBy { it.litres!! }
                    val diff = worst.litres!! - cheapest.litres!!
                    Text("La strada ${cheapest.letter} consuma meno: ${"%.2f".format(diff)} litri in meno a passaggio rispetto alla ${worst.letter} " +
                        "(circa ${"%.2f".format(diff * 1.75)} € con il gasolio a 1,75 €/L).", color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Text("Più passaggi ci sono, più i numeri sono affidabili: il traffico cambia da un giorno all'altro. I tratti dentro viaggi più lunghi sono misurati da quando passi dal primo punto a quando arrivi al secondo.",
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
