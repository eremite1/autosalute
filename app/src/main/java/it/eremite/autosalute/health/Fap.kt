package it.eremite.autosalute.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Filtro antiparticolato (FAP/DPF).
 *
 * Come riconosciamo una rigenerazione (la centralina brucia la fuliggine alzando la temperatura dello scarico):
 *  1. se l'auto fornisce la temperatura del FAP o dei gas di scarico: sopra ~450°C per più di un minuto;
 *  2. altrimenti dal comportamento del motore: per scaldare lo scarico la centralina inietta gasolio in più
 *     e chiude la valvola EGR. A parità di velocità e giri il motore lavora molto più del solito
 *     (carico +25% rispetto al suo normale) e, se leggibile, l'EGR resta a zero.
 *
 * Se l'auto fornisce anche la pressione differenziale del FAP, misuriamo quanto è intasato:
 * contropressione divisa per il flusso d'aria, confrontata con il valore subito dopo una rigenerazione.
 */
data class FapLive(
    val regen: Boolean = false,
    val since: Long = 0L,
    val reason: String = "",
    val confidence: String = "",
    val restriction: Double = Double.NaN,
    val dpfTemp: Double = Double.NaN,
    val dp: Double = Double.NaN,
)

class RegenEvent(
    val start: Long, val end: Long, val durS: Double, val kmStart: Double, val kmDuring: Double,
    val method: String, val interrupted: Boolean, val maxTemp: Double,
)

class FapAcc(private val ctx: Context, private val car: Long, private val onStart: () -> Unit) {
    private val prefs = ctx.getSharedPreferences("autosalute", Context.MODE_PRIVATE)
    private val key = "fap_base_$car"
    private val base: JSONObject = try { JSONObject(prefs.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    // finestra mobile di 60 secondi per il confronto
    private val win = ArrayDeque<Triple<Long, Double, Double>>() // (tempo, carico/normale, egr)
    private var regenStart = 0L
    private var regenKm0 = 0.0
    private var regenMax = Double.NaN
    private var calmSince = 0L
    private var method = ""
    private var hotSince = 0L
    val events = ArrayList<RegenEvent>()
    private val restr = Stat()

    private fun bin(speed: Double, rpm: Double): String? = when {
        speed < 1 && rpm in 600.0..1300.0 -> "idle"
        speed in 30.0..50.0 -> "s40"
        speed in 50.0..70.0 -> "s60"
        speed in 70.0..90.0 -> "s80"
        speed in 90.0..110.0 -> "s100"
        speed in 110.0..135.0 -> "s120"
        else -> null
    }

    fun add(v: Map<String, Double>, now: Long, km: Double) {
        val speed = v["speed"] ?: return
        val rpm = v["rpm"] ?: return
        val load = v["load"] ?: return
        val coolant = v["coolant"] ?: Double.NaN
        val egr = v["egr"] ?: Double.NaN
        val temp = listOfNotNull(v["dpf_temp"], v["egt"]).maxOrNull() ?: Double.NaN
        val dp = v["dpf_dp"] ?: Double.NaN
        val maf = v["maf"] ?: Double.NaN
        val warm = !coolant.isNaN() && coolant >= 70
        val b = bin(speed, rpm)

        // contropressione normalizzata (solo in marcia regolare a motore caldo)
        if (!dp.isNaN() && !maf.isNaN() && maf > 15 && speed > 50 && warm) restr.add(dp / maf * 100)

        // confronto con il comportamento normale di questa auto
        var ratio = Double.NaN
        if (b != null && warm) {
            val o = base.optJSONObject(b)
            val n = o?.optInt("n") ?: 0
            val mean = o?.optDouble("m") ?: Double.NaN
            if (n >= 150 && !mean.isNaN() && mean > 3) ratio = load / mean
            // aggiorna la media solo fuori da una rigenerazione
            if (regenStart == 0L) {
                val nn = minOf(n + 1, 3000)
                val m = if (n == 0 || mean.isNaN()) load else mean + (load - mean) / nn
                base.put(b, JSONObject().put("n", nn).put("m", m))
            }
        }
        win.addLast(Triple(now, ratio, egr))
        while (win.size > 2 && now - win.first().first > 60_000) win.removeFirst()

        // --- segnali di rigenerazione ---
        val byTemp = !temp.isNaN() && temp >= 450
        if (byTemp) { if (hotSince == 0L) hotSince = now } else hotSince = 0L
        val rs = win.mapNotNull { it.second.takeIf { r -> !r.isNaN() } }
        val egrs = win.mapNotNull { it.third.takeIf { e -> !e.isNaN() } }
        val longEnough = now - win.first().first >= 50_000 && rs.size >= 15
        val highLoad = longEnough && rs.average() >= 1.25
        val egrClosed = egrs.size >= 3 && egrs.all { it < 3 }
        val egrKnown = egrs.isNotEmpty()
        val byBehaviour = highLoad && (!egrKnown || egrClosed)

        if (regenStart == 0L) {
            val start = (hotSince > 0 && now - hotSince > 60_000) || byBehaviour
            if (start && warm) {
                regenStart = now; regenKm0 = km; regenMax = temp; calmSince = 0L
                method = when {
                    hotSince > 0 -> "temperatura scarico"
                    egrKnown -> "consumo e valvola EGR"
                    else -> "consumo del motore"
                }
                onStart()
            }
        } else {
            if (!temp.isNaN()) regenMax = if (regenMax.isNaN()) temp else maxOf(regenMax, temp)
            val calm = if (!temp.isNaN()) temp < 380 else (rs.isNotEmpty() && rs.average() < 1.1 && (!egrKnown || egrs.any { it > 5 }))
            if (calm) { if (calmSince == 0L) calmSince = now } else calmSince = 0L
            if (calmSince > 0 && now - calmSince > 60_000) close(calmSince, km, false)
        }

        live.value = FapLive(
            regen = regenStart > 0, since = regenStart, reason = method,
            confidence = if (method == "temperatura scarico") "sicura" else "stimata",
            restriction = if (restr.n > 5) restr.mean else Double.NaN, dpfTemp = temp, dp = dp,
        )
    }

    private fun close(end: Long, km: Double, interrupted: Boolean) {
        val d = (end - regenStart) / 1000.0
        // le "rigenerazioni" troppo brevi a comportamento sono quasi sempre salite o carichi: le scartiamo
        if ((interrupted && d >= 120) || d >= 180 || method == "temperatura scarico") {
            events.add(RegenEvent(regenStart, end, d, regenKm0, km - regenKm0, method, interrupted, regenMax))
        }
        regenStart = 0L; calmSince = 0L
    }

    /** A fine viaggio: se la rigenerazione era in corso, è stata interrotta spegnendo il motore. */
    fun finish(now: Long, km: Double) {
        if (regenStart > 0) close(now, km, true)
        prefs.edit().putString(key, base.toString()).apply()
        live.value = FapLive()
    }

    fun metrics(): Map<String, Double> = if (restr.n > 10) mapOf("dpf_restr" to restr.mean) else emptyMap()

    companion object {
        val live = MutableStateFlow(FapLive())
    }
}
