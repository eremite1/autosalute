package it.eremite.autosalute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.Live
import it.eremite.autosalute.health.Eco
import it.eremite.autosalute.health.FapAcc
import it.eremite.autosalute.service.GpsTracker

private const val RPM_MIN = 700.0
private const val RPM_MAX = 4200.0

@Composable
fun EcoScreen() {
    val s by Eco.live.collectAsState()
    val conn by Live.connected.collectAsState()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val zoneCol = when (s.zone) { 0 -> C.ok; 1 -> C.warn; 2 -> C.bad; else -> C.dim }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.padding(top = 24.dp, bottom = 4.dp)) {
            Text("Guida Eco", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.text)
            Text(if (conn) "Tieni il pallino nella zona verde" else "Si attiva quando sei collegato all'auto", color = C.dim, fontSize = 14.sp)
        }

        WarmCard()
        FapBanner()

        // Consiglio grande, leggibile con un'occhiata
        Box(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp))
                .background(zoneCol.copy(alpha = 0.16f)).padding(vertical = 18.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(s.tip, color = zoneCol, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
        }

        // Mappa di efficienza: giri (orizzontale) x carico (verticale)
        Box2(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Dove lavora il motore", color = C.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            EfficiencyMap(s, Modifier.fillMaxWidth().height(230.dp))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("← giri motore →", color = C.dim, fontSize = 11.sp)
                Text("in alto = più sforzo", color = C.dim, fontSize = 11.sp)
            }
        }

        // Numeri principali
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big("Consumo ora", if (s.instL100.isNaN()) (if (s.instLh.isNaN()) "–" else "%.1f".format(s.instLh)) else "%.1f".format(s.instL100.coerceAtMost(99.9)),
                if (s.instL100.isNaN()) "L/h" else "L/100km", Modifier.weight(1f))
            Big("Media viaggio", if (s.tripL100.isNaN()) "–" else "%.1f".format(s.tripL100), "L/100km", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big("Voto guida", s.score?.toString() ?: "–", "/100", Modifier.weight(1f), s.score?.let { C.score(it) })
            Big("In zona verde", if (s.greenPct.isNaN()) "–" else "%.0f".format(s.greenPct), "%", Modifier.weight(1f))
            Big("In rilascio", if (s.coastPct.isNaN()) "–" else "%.0f".format(s.coastPct), "%", Modifier.weight(1f))
        }
        if (s.estimated) Text("Consumi stimati: la centralina non li fornisce direttamente.", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))

        LiveMap()

        Section("Tutti i valori dal vivo")
        LiveGrid()

        Section("Come si guida in zona verde")
        Box2(Modifier.fillMaxWidth()) {
            listOf(
                "Il tuo motore rende al meglio tra 1400 e 2300 giri: la coppia massima è a 1750.",
                "Cambia presto: verso i 2000 giri passa alla marcia superiore.",
                "Accelera deciso ma senza schiacciare a fondo, poi stabilizza.",
                "Quando rallenti lascia il pedale con la marcia inserita: il motore consuma zero.",
                "Sotto i 1150 giri con molto sforzo il motore soffre: scala una marcia.",
            ).forEach { Text("•  $it", color = C.text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Big(label: String, v: String, unit: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.card).padding(12.dp)) {
        Text(label, color = C.dim, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(v, color = color ?: C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun EfficiencyMap(s: Eco.State, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun x(rpm: Double) = ((rpm.coerceIn(RPM_MIN, RPM_MAX) - RPM_MIN) / (RPM_MAX - RPM_MIN) * w).toFloat()
        fun y(load: Double) = (h - load.coerceIn(0.0, 100.0) / 100.0 * h).toFloat()

        // sfondo: zona rossa (giri alti), gialla, verde
        drawRect(C.card2, Offset.Zero, Size(w, h))
        drawRect(C.bad.copy(alpha = 0.18f), Offset(x(2600.0), 0f), Size(w - x(2600.0), h))
        drawRect(C.bad.copy(alpha = 0.18f), Offset(0f, 0f), Size(x(1150.0), y(80.0)))
        drawRect(C.warn.copy(alpha = 0.10f), Offset(x(Eco.RPM_HI), 0f), Size(x(2600.0) - x(Eco.RPM_HI), h))
        drawRect(
            C.ok.copy(alpha = 0.28f), Offset(x(Eco.RPM_LO), y(Eco.LOAD_HI)),
            Size(x(Eco.RPM_HI) - x(Eco.RPM_LO), y(Eco.LOAD_LO) - y(Eco.LOAD_HI))
        )
        drawRect(
            C.ok, Offset(x(Eco.RPM_LO), y(Eco.LOAD_HI)),
            Size(x(Eco.RPM_HI) - x(Eco.RPM_LO), y(Eco.LOAD_LO) - y(Eco.LOAD_HI)), style = Stroke(3f)
        )

        // griglia giri
        val paint = android.graphics.Paint().apply { color = 0xFF8A93A3.toInt(); textSize = 26f; isAntiAlias = true }
        for (r in listOf(1000, 1500, 2000, 2500, 3000, 3500, 4000)) {
            val xx = x(r.toDouble())
            drawLine(C.line, Offset(xx, 0f), Offset(xx, h), 1f)
            drawContext.canvas.nativeCanvas.drawText("${r / 1000.0}k".replace(".0k", "k"), xx + 4f, h - 6f, paint)
        }

        // scia degli ultimi secondi
        val n = s.trail.size
        s.trail.forEachIndexed { i, p ->
            val a = (i + 1f) / n * 0.5f
            drawCircle(C.text.copy(alpha = a), 5f, Offset(x(p.rpm), y(p.load)))
        }
        // pallino attuale
        s.point?.let { p ->
            val col = when (s.zone) { 0 -> C.ok; 1 -> C.warn; 2 -> C.bad; else -> C.dim }
            val c = Offset(x(p.rpm), y(p.load))
            drawCircle(col.copy(alpha = 0.3f), 30f, c)
            drawCircle(col, 16f, c)
            drawCircle(C.bg, 6f, c)
        }
    }
}

@Composable
fun WarmCard() {
    val w by it.eremite.autosalute.health.Warmup.live.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(w.readySince) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(5000) } }
    if (w.phase == -1 && w.coolant.isNaN()) return

    // Una volta pronto, dopo 1 minuto resta solo una piccola etichetta
    if (w.phase == 3 && (!w.wasCold || now - w.readySince > 60_000)) {
        Row(
            Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50)).background(C.card).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(C.ok, 8); Spacer(Modifier.width(8.dp))
            Text("Motore in temperatura" + (if (!w.coolant.isNaN()) " · ${w.coolant.toInt()}°C" else ""), color = C.text, fontSize = 13.sp)
        }
        return
    }

    val col = when {
        w.overRev -> C.bad
        w.phase == 3 -> C.ok
        w.phase == 2 -> C.info
        w.phase == -1 -> C.dim
        else -> C.warn
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(C.card).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (w.phase == 3) "✓" else "🌡", fontSize = 22.sp, color = col)
            Spacer(Modifier.width(10.dp))
            Text(w.title, color = col, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (w.overRev && w.maxRpm != null) {
            Text("Troppi giri a motore freddo: resta sotto i ${w.maxRpm}", color = C.bad, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(w.detail, color = C.text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))

        // barra di avanzamento
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(w.progress.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(col))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                listOfNotNull(
                    if (w.coolant.isNaN()) null else "Liquido ${w.coolant.toInt()}°C",
                    if (w.oil.isNaN()) null else "Olio ${w.oil.toInt()}°C",
                ).joinToString(" · "),
                color = C.dim, fontSize = 13.sp
            )
            val m = w.minutesLeft
            Text(
                when {
                    w.phase == 3 -> "pronto"
                    w.maxRpm != null && m != null && m >= 0.5 -> "max ${w.maxRpm} giri · ~${m.toInt().coerceAtLeast(1)} min"
                    w.maxRpm != null -> "max ${w.maxRpm} giri"
                    else -> ""
                },
                color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}


@Composable
private fun LiveMap() {
    val track by GpsTracker.track.collectAsState()
    val conn by Live.connected.collectAsState()
    if (!conn || track.isEmpty()) return
    val cur = track.last()
    Section("Posizione dal vivo")
    RouteMap(track, Modifier.fillMaxWidth().height(220.dp), follow = true, endLabel = "Sei qui")
    if (!cur.speed.isNaN()) Text("Velocità GPS: ${cur.speed.toInt()} km/h", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}


@Composable
private fun FapBanner() {
    val f by FapAcc.live.collectAsState()
    if (!f.regen) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp)).background(C.warn.copy(alpha = 0.16f)).padding(16.dp)) {
        Text("Pulizia del FAP in corso", color = C.warn, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Da ${((System.currentTimeMillis() - f.since) / 60000).toInt()} min. Se puoi non spegnere il motore e tieni un'andatura costante per 10-15 minuti." +
            (if (!f.dpfTemp.isNaN()) " Scarico a ${f.dpfTemp.toInt()}°C." else ""), color = C.text, fontSize = 14.sp)
    }
}
