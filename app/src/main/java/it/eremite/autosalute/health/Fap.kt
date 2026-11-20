package it.eremite.autosalute.health

import android.content.Context
import it.eremite.autosalute.Live
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
    val ecu: Boolean = false,
    val soot: Double = Double.NaN,
    val kmRegen: Double = Double.NaN,
    val type: String = "",
    val requested: Boolean = false,
)

class RegenEvent(
    val start: Long, val end: Long, val durS: Double, val kmStart: Double, val kmDuring: Double,
    val method: String, val interrupted: Boolean, val maxTemp: Double,
    val type: String = "attiva", val soot0: Double = Double.NaN, val soot1: Double = Double.NaN,
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

    // ---- dati veri della centralina Renault ----
    private var ecuMode = false
    private var ecuActiveOffSince = 0L
    private var ecuType = "attiva"
    private var soot0 = Double.NaN
    private var kmRegen0 = Double.NaN
    private var regenTripKm0 = 0.0
    private var cnt0 = Double.NaN
    private var sootTripStart = Double.NaN
    private var sootLast = Double.NaN
    private var sootMax = Double.NaN
    private var passiveStart = 0L
    private var passiveKm0 = 0.0
    private var countChecked = false
    private var lastCount = Double.NaN
    private var lastKmRegen = Double.NaN
    private var oilDil = Double.NaN
    private var restCmd: Double? = null
    private var candSince = 0L
    private var reqNow = false
    private var logged = false
    private var cmdFirst = Double.NaN
    private var setFirst = Double.NaN
    private var tempMaxTrip = Double.NaN
    private var sootPrev: Double? = null

    /** Usa i valori della centralina Renault. Restituisce false se non disponibili. */
    private fun addEcu(v: Map<String, Double>, now: Long, km: Double): Boolean {
        val soot = v["soot_g"]; val cmd = v["regen_cmd"]; val set = v["dpf_set_temp"]
        if (soot == null && cmd == null) return false
        ecuMode = true
        val temp = v["dpf_in_temp"] ?: Double.NaN
        val kmReg = v["km_regen"] ?: Double.NaN
        val cnt = v["regen_count"]
        v["oil_dilution"]?.let { oilDil = it }
        if (soot != null) {
            if (sootTripStart.isNaN()) sootTripStart = soot
            sootLast = soot
        }
        if (!kmReg.isNaN()) lastKmRegen = kmReg
        if (cnt != null) lastCount = cnt

        // rigenerazioni fatte mentre l'app non era collegata (contatore della centralina)
        if (!countChecked && cnt != null) {
            countChecked = true
            val prev = prefs.getFloat("fap_cnt_$car", -1f).toDouble()
            if (prev >= 0) {
                val n = ((cnt - prev + 256) % 256).toInt()
                if (n in 1..10) repeat(n) {
                    events.add(RegenEvent(now, now, 0.0, Double.NaN, 0.0, "contatore centralina", false, Double.NaN, "a posteriori"))
                }
            }
        }

        val coolant = v["coolant"] ?: Double.NaN
        val speed = v["speed"] ?: 0.0
        if (!temp.isNaN()) tempMaxTrip = if (tempMaxTrip.isNaN()) temp else maxOf(tempMaxTrip, temp)
        if (!logged && (cmd != null || set != null)) {
            logged = true; cmdFirst = cmd ?: Double.NaN; setFirst = set ?: Double.NaN
            Live.log("FAP centralina: comando=${cmd ?: "-"} temp.richiesta=${set ?: "-"} temp.filtro=${if (temp.isNaN()) "-" else temp.toInt()} " +
                "fuliggine=${soot ?: "-"} km.da.pulizia=${if (kmReg.isNaN()) "-" else kmReg.toInt()} statica=${v["regen_static"] ?: "-"}")
        }
        // Valore "a riposo" del comando: quello letto a motore freddo, quando una pulizia è impossibile.
        if (cmd != null && !coolant.isNaN() && coolant < 50 && regenStart == 0L) {
            restCmd = cmd; prefs.edit().putFloat("fap_rest_cmd_$car", cmd.toFloat()).apply()
        }
        val rest = restCmd ?: prefs.getFloat("fap_rest_cmd_$car", 0f).toDouble()
        val static = (v["regen_static"] ?: 0.0) > 0 && speed < 1
        val requested = (cmd != null && cmd != rest) || static
        // Una pulizia è IN CORSO solo se il filtro è davvero caldo (oltre 450°C per un minuto).
        // Se l'auto non dà la temperatura, serve la richiesta della centralina per 3 minuti in marcia a motore caldo.
        val tempKnown = !temp.isNaN()
        if (tempKnown && temp >= 450) { if (hotSince == 0L) hotSince = now } else hotSince = 0L
        val cand = static || (tempKnown && hotSince > 0 && now - hotSince >= 60_000) ||
            (!tempKnown && requested && !coolant.isNaN() && coolant >= 70 && speed > 30)
        if (cand) { if (candSince == 0L) candSince = now } else candSince = 0L
        val running = cand && (tempKnown || static || now - candSince >= 180_000)
        reqNow = requested && regenStart == 0L

        if (regenStart == 0L) {
            if (running) {
                regenStart = if (candSince > 0) candSince else now; regenTripKm0 = km; regenMax = temp; method = "centralina"
                ecuType = if (static) "statica" else "attiva"
                soot0 = sootPrev ?: soot ?: Double.NaN; kmRegen0 = kmReg; cnt0 = cnt ?: Double.NaN; ecuActiveOffSince = 0L
                passiveStart = 0L
                onStart()
            } else if (soot != null) {
                // combustione passiva: la fuliggine cala senza pulizia comandata (autostrada)
                sootMax = if (sootMax.isNaN()) soot else maxOf(sootMax, soot)
                if (sootMax - soot >= 0.3 && passiveStart == 0L && !requested) { passiveStart = now; passiveKm0 = km }
            }
        } else {
            if (!temp.isNaN()) regenMax = if (regenMax.isNaN()) temp else maxOf(regenMax, temp)
            val stillOn = if (tempKnown) temp >= 400 else requested
            if (!stillOn) {
                if (ecuActiveOffSince == 0L) ecuActiveOffSince = now
                if (now - ecuActiveOffSince > 60_000) {
                    val dropped = !soot0.isNaN() && soot != null && soot <= soot0 * 0.7
                    val reset = !kmRegen0.isNaN() && !kmReg.isNaN() && kmReg < kmRegen0
                    val counted = !cnt0.isNaN() && cnt != null && cnt != cnt0
                    closeEcu(ecuActiveOffSince, km, interrupted = !(dropped || reset || counted), soot = soot)
                    sootMax = soot ?: Double.NaN
                }
            } else ecuActiveOffSince = 0L
        }
        if (soot != null) sootPrev = soot

        live.value = FapLive(
            regen = regenStart > 0, since = regenStart, reason = "centralina", confidence = "sicura",
            restriction = Double.NaN, dpfTemp = temp, dp = v["dpf_dp_mbar"] ?: Double.NaN,
            ecu = true, soot = soot ?: sootLast, kmRegen = kmReg, type = if (regenStart > 0) ecuType else "",
            requested = reqNow,
        )
        return true
    }

    private fun closeEcu(end: Long, km: Double, interrupted: Boolean, soot: Double?) {
        val d = (end - regenStart) / 1000.0
        // kmStart = km percorsi dalla pulizia precedente (dato della centralina)
        events.add(RegenEvent(regenStart, end, d, kmRegen0, km - regenTripKm0, "centralina", interrupted, regenMax,
            ecuType, soot0, soot ?: Double.NaN))
        regenStart = 0L; ecuActiveOffSince = 0L
    }

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
        if (addEcu(v, now, km)) return
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
        if (ecuMode) {
            if (regenStart > 0) {
                if (now - regenStart >= 120_000) closeEcu(now, km, true, if (sootLast.isNaN()) null else sootLast)
                else regenStart = 0L
            }
            if (passiveStart > 0 && !sootMax.isNaN() && !sootLast.isNaN() && sootMax - sootLast >= 0.3)
                events.add(RegenEvent(passiveStart, now, (now - passiveStart) / 1000.0, passiveKm0, km - passiveKm0,
                    "centralina", false, Double.NaN, "passiva", sootMax, sootLast))
            val e = prefs.edit()
            if (!lastCount.isNaN()) e.putFloat("fap_cnt_$car", lastCount.toFloat())
            e.apply()
            live.value = FapLive()
            return
        }
        if (regenStart > 0) close(now, km, true)
        prefs.edit().putString(key, base.toString()).apply()
        live.value = FapLive()
    }

    fun metrics(): Map<String, Double> {
        val m = HashMap<String, Double>()
        if (restr.n > 10) m["dpf_restr"] = restr.mean
        if (!sootTripStart.isNaN()) m["soot_start"] = sootTripStart
        if (!sootLast.isNaN()) m["soot_end"] = sootLast
        if (!lastKmRegen.isNaN()) m["km_regen_end"] = lastKmRegen
        if (!oilDil.isNaN()) m["oil_dilution"] = oilDil
        if (!cmdFirst.isNaN()) m["regen_cmd_first"] = cmdFirst
        if (!setFirst.isNaN()) m["dpf_set_first"] = setFirst
        if (!tempMaxTrip.isNaN()) m["dpf_temp_max"] = tempMaxTrip
        return m
    }

    companion object {
        val live = MutableStateFlow(FapLive())
    }
}
