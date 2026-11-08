package it.eremite.autosalute.health

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Guida efficiente per il 1.5 dCi K9K 95 CV (coppia massima 220 Nm a 1750 giri).
 * Zona verde: 1400-2300 giri con carico 35-85%, dove il motore consuma meno
 * per ogni cavallo prodotto.
 */
object Eco {
    const val RPM_LO = 1400.0
    const val RPM_HI = 2300.0
    const val LOAD_LO = 35.0
    const val LOAD_HI = 85.0

    data class Point(val rpm: Double, val load: Double)

    data class State(
        val point: Point? = null,
        val trail: List<Point> = emptyList(),
        val zone: Int = -1,              // 0 verde, 1 giallo, 2 rosso, -1 fermo
        val tip: String = "In attesa di partire",
        val instL100: Double = Double.NaN,
        val instLh: Double = Double.NaN,
        val tripL100: Double = Double.NaN,
        val score: Int? = null,
        val greenPct: Double = Double.NaN,
        val coastPct: Double = Double.NaN,
        val estimated: Boolean = false,
        val kmPer1000: Double = Double.NaN,
    )

    val live = MutableStateFlow(State())

    /** Stima del consumo quando la centralina non lo fornisce (L/h). */
    fun estimateFuel(rpm: Double, load: Double): Double {
        if (rpm < 300) return 0.0
        val gPerS = (load / 100.0) * 0.038 * (rpm / 30.0)
        return gPerS * 3600.0 / 835.0
    }

    fun zoneOf(rpm: Double, load: Double, speed: Double, coasting: Boolean): Int = when {
        speed < 3 -> -1
        coasting -> 0
        rpm > 3000 || load > 95 -> 2
        rpm in RPM_LO..RPM_HI && load in LOAD_LO..LOAD_HI -> 0
        rpm > 2600 -> 2
        rpm < 1150 && load > 80 -> 2
        else -> 1
    }
}

/** Accumula il punteggio di guida del viaggio e prepara i consigli in tempo reale. */
class EcoAcc {
    private var moving = 0.0; private var green = 0.0; private var yellow = 0.0; private var red = 0.0
    private var coast = 0.0; private var harsh = 0.0
    private var km = 0.0; private var fuel = 0.0
    private val trail = ArrayDeque<Eco.Point>()
    private var smoothL100 = Double.NaN

    fun add(v: Map<String, Double>, dt0: Double, estimated: Boolean) {
        val dt = dt0.coerceIn(0.0, 5.0)
        val rpm = v["rpm"] ?: return
        val load = v["load"] ?: return
        val speed = v["speed"] ?: 0.0
        val lh = v["fuel_rate"] ?: Double.NaN
        val pedal = v["pedal"] ?: Double.NaN
        val coasting = speed > 15 && rpm > 1000 && (load < 8 || (!lh.isNaN() && lh < 0.15))
        val zone = Eco.zoneOf(rpm, load, speed, coasting)

        if (speed >= 3) {
            moving += dt
            when (zone) { 0 -> green += dt; 1 -> yellow += dt; 2 -> red += dt }
            if (coasting) coast += dt
            if ((!pedal.isNaN() && pedal > 85) || load > 97) harsh += dt
        }
        km += speed * dt / 3600.0
        if (!lh.isNaN()) fuel += lh * dt / 3600.0

        trail.addLast(Eco.Point(rpm, load)); while (trail.size > 40) trail.removeFirst()

        val inst = if (speed > 5 && !lh.isNaN()) lh / speed * 100.0 else Double.NaN
        smoothL100 = if (inst.isNaN()) Double.NaN else if (smoothL100.isNaN()) inst else smoothL100 * 0.7 + inst * 0.3
        val ratio = if (rpm > 600 && speed > 5) speed / rpm * 1000.0 else Double.NaN
        val topGear = !ratio.isNaN() && ratio > 40.0

        val tip = when {
            speed < 3 && rpm > 400 -> if (rpm > 1100) "Da fermo: lascia il motore al minimo" else "Fermo al minimo"
            speed < 3 -> "In attesa di partire"
            coasting -> "Ottimo: in rilascio il gasolio è a zero"
            rpm > 3000 -> "Giri troppo alti: cambia marcia"
            rpm > Eco.RPM_HI && load < 70 && !topGear -> "Passa alla marcia superiore"
            rpm > Eco.RPM_HI && load >= 70 -> "Accelera con più dolcezza"
            rpm < 1150 && load > 80 -> "Motore in sofferenza: scala una marcia"
            load > 92 && !pedal.isNaN() && pedal > 85 -> "Pedale a fondo: alleggerisci il piede"
            zone == 0 -> "Perfetto: zona di massima efficienza"
            load < Eco.LOAD_LO && rpm > 1800 && !topGear -> "Puoi salire di marcia"
            load < Eco.LOAD_LO -> "Andatura leggera: va bene così"
            else -> "Quasi in zona verde"
        }

        val score = if (moving < 30) null else
            (100.0 * (green + coast * 0.5 + yellow * 0.6) / moving - 40.0 * harsh / moving).toInt().coerceIn(0, 100)

        Eco.live.value = Eco.State(
            point = Eco.Point(rpm, load), trail = trail.toList(), zone = zone, tip = tip,
            instL100 = smoothL100, instLh = lh,
            tripL100 = if (km > 0.5 && fuel > 0) fuel / km * 100 else Double.NaN,
            score = score, greenPct = if (moving > 0) green / moving * 100 else Double.NaN,
            coastPct = if (moving > 0) coast / moving * 100 else Double.NaN,
            estimated = estimated, kmPer1000 = ratio,
        )
    }

    fun metrics(): Map<String, Double> {
        if (moving < 60) return emptyMap()
        val s = Eco.live.value
        return buildMap {
            s.score?.let { put("eco_score", it.toDouble()) }
            put("eco_green", green / moving * 100)
            put("eco_red", red / moving * 100)
            put("eco_coast", coast / moving * 100)
        }
    }
}

val ECO_LABELS = mapOf(
    "eco_score" to "Voto guida eco (/100)",
    "eco_green" to "Tempo in zona verde (%)",
    "eco_red" to "Tempo in zona rossa (%)",
    "eco_coast" to "Tempo in rilascio (%)",
)
