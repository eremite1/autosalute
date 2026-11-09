package it.eremite.autosalute.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import it.eremite.autosalute.data.Cars
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.data.GeoPoint
import it.eremite.autosalute.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.util.GeoPoint as OsmPoint

private var osmReady = false
private fun initOsm(c: Context) {
    if (osmReady) return
    Configuration.getInstance().load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    Configuration.getInstance().userAgentValue = c.packageName
    osmReady = true
}

/** Mappa OpenStreetMap con il percorso. follow = segue l'ultima posizione. */
@Composable
fun RouteMap(points: List<GeoPoint>, modifier: Modifier = Modifier, follow: Boolean = false, endLabel: String = "Arrivo") {
    val ctx = LocalContext.current
    remember { initOsm(ctx) }
    var fitted by remember { mutableStateOf(false) }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier.clip(RoundedCornerShape(14.dp)), update = { m ->
        m.overlays.clear()
        val pts = points.map { OsmPoint(it.lat, it.lon) }
        if (pts.size >= 2) {
            m.overlays.add(Polyline(m).apply {
                setPoints(pts)
                outlinePaint.color = 0xFF2E9E6B.toInt()
                outlinePaint.strokeWidth = 12f
            })
            m.overlays.add(Marker(m).apply {
                position = pts.first(); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Partenza"
            })
        }
        if (pts.isNotEmpty()) m.overlays.add(Marker(m).apply {
            position = pts.last(); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = endLabel
        })
        if (pts.isNotEmpty()) {
            if (follow || pts.size == 1) {
                m.controller.setCenter(pts.last())
                if (!fitted) { m.controller.setZoom(16.0); fitted = true }
            } else if (!fitted) {
                m.post {
                    try { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 60) } catch (_: Exception) { m.controller.setCenter(pts.last()) }
                }
                fitted = true
            }
        }
        m.invalidate()
    })
}

fun openInMaps(c: Context, p: GeoPoint, label: String) {
    val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(label)})")
    try { c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

/** "Dove hai parcheggiato": ultima posizione registrata con l'auto selezionata. */
@Composable
fun ParkedCard() {
    val ctx = LocalContext.current
    val tv by Live.tripsVersion.collectAsState()
    val car by Cars.active.collectAsState()
    val conn by Live.connected.collectAsState()
    var p by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(tv, car) { p = withContext(Dispatchers.IO) { Db.get(ctx).lastPoint(Cars.activeId(ctx)) } }
    val pt = p ?: return
    if (conn) return
    Section("Dove hai parcheggiato")
    Box2(Modifier.fillMaxWidth()) {
        RouteMap(listOf(pt), Modifier.fillMaxWidth().height(170.dp), endLabel = "La tua auto")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALY).format(Date(pt.ts)), color = C.dim, fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { openInMaps(ctx, pt, "La mia auto") }) { Text("Portami qui") }
        }
    }
}
