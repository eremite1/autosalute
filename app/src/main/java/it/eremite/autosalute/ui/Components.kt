package it.eremite.autosalute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.health.Finding
import it.eremite.autosalute.health.Health

object C {
    val bg = Color(0xFF0E1116)
    val card = Color(0xFF171B22)
    val card2 = Color(0xFF1E232C)
    val line = Color(0xFF2A303B)
    val text = Color(0xFFE8ECF1)
    val dim = Color(0xFF8A93A3)
    val ok = Color(0xFF3DDC97)
    val warn = Color(0xFFFFB020)
    val bad = Color(0xFFFF5A5F)
    val info = Color(0xFF5AA9FF)
    fun sev(s: Int) = when (s) { 2 -> bad; 1 -> warn; else -> info }
    fun score(s: Int) = when { s >= 85 -> ok; s >= 65 -> warn; else -> bad }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.ok, onPrimary = Color(0xFF00281A), background = C.bg, surface = C.card,
            onBackground = C.text, onSurface = C.text, surfaceVariant = C.card2, secondary = C.info
        ),
        content = content
    )
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title.uppercase(), color = C.dim, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun Box2(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(C.card).padding(16.dp), content = content)
}

@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun ScoreRing(score: Int?, label: String) {
    val col = if (score == null) C.dim else C.score(score)
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val st = Stroke(width = 22f, cap = StrokeCap.Round)
            drawArc(C.line, 135f, 270f, false, style = st)
            if (score != null) drawArc(col, 135f, 270f * score / 100f, false, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "–", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = C.text)
            Text(label, color = col, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun FindingCard(f: Finding) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.card).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 5.dp)) { Dot(C.sev(f.sev)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(f.title, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(f.cat, color = C.dim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(f.detail, color = C.text.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** Grafico a linea semplice. Valori NaN = buchi. */
@Composable
fun LineChart(values: List<Double>, color: Color, modifier: Modifier = Modifier, band: Pair<Double, Double>? = null, dots: Boolean = false) {
    val clean = values.filter { !it.isNaN() }
    if (clean.size < 2) {
        Text("Dati insufficienti", color = C.dim, fontSize = 12.sp, modifier = modifier.padding(8.dp)); return
    }
    var lo = clean.min(); var hi = clean.max()
    band?.let { lo = minOf(lo, it.first); hi = maxOf(hi, it.second) }
    if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
    val pad = (hi - lo) * 0.08; lo -= pad; hi += pad
    Column(modifier) {
        Row { Text(Health.fmt(hi - pad), color = C.dim, fontSize = 10.sp) }
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width; val h = size.height
            fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
            band?.let {
                drawRect(color.copy(alpha = 0.10f), topLeft = Offset(0f, y(it.second)), size = Size(w, y(it.first) - y(it.second)))
            }
            val n = values.size
            val p = Path(); var started = false
            values.forEachIndexed { i, v ->
                if (v.isNaN()) { started = false; return@forEachIndexed }
                val x = if (n == 1) 0f else w * i / (n - 1)
                if (!started) { p.moveTo(x, y(v)); started = true } else p.lineTo(x, y(v))
                if (dots) drawCircle(color, 5f, Offset(x, y(v)))
            }
            drawPath(p, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        Row { Text(Health.fmt(lo + pad), color = C.dim, fontSize = 10.sp) }
    }
}
