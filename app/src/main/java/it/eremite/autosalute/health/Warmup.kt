package it.eremite.autosalute.health

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Riscaldamento del motore dopo l'avviamento.
 * phase: -1 motore spento, 0 attendi qualche secondo, 1 parti con calma,
 *        2 quasi pronto, 3 pronto: puoi spingere.
 */
data class WarmState(
    val phase: Int = -1,
    val title: String = "Avvia il motore",
    val detail: String = "",
    val coolant: Double = Double.NaN,
    val oil: Double = Double.NaN,
    val maxRpm: Int? = null,
    val progress: Float = 0f,
    val minutesLeft: Double? = null,
    val waitLeft: Int = 0,
    val overRev: Boolean = false,
    val readySince: Long = 0L,
    val wasCold: Boolean = false,
)

class Warmup(private val onReady: (String) -> Unit) {
    private var startTs = 0L
    private var startCoolant = Double.NaN
    private var hot80Since = 0.0       // secondi di marcia con liquido >= 80
    private var readyTs = 0L
    private val hist = ArrayDeque<Pair<Long, Double>>()   // (tempo, temperatura liquido)

    fun add(v: Map<String, Double>, now: Long, dt: Double, running: Boolean) {
        val rpm = v["rpm"] ?: Double.NaN
        val c = v["coolant"] ?: Double.NaN
        val oil = v["oil"] ?: Double.NaN
        val speed = v["speed"] ?: 0.0
        val load = v["load"] ?: 0.0

        if (!running || rpm.isNaN() || rpm < 400) {
            live.value = WarmState(coolant = c, oil = oil, detail = if (c.isNaN()) "" else "Temperatura motore ${c.toInt()}°C")
            return
        }
        if (startTs == 0L) { startTs = now; startCoolant = c }
        val t = (now - startTs) / 1000.0
        val wasCold = !startCoolant.isNaN() && startCoolant < 70

        if (!c.isNaN()) {
            hist.addLast(now to c)
            while (hist.size > 2 && now - hist.first().first > 120_000) hist.removeFirst()
            if (c >= 80 && rpm > 600) hot80Since += dt.coerceIn(0.0, 5.0)
        }

        // Pochi secondi di attesa solo a freddo: l'olio raggiunge turbo e alberi
        val wait = when {
            startCoolant.isNaN() -> 10
            startCoolant < 5 -> 30
            startCoolant < 15 -> 20
            startCoolant < 70 -> 10
            else -> 0
        }
        val oilKnown = !oil.isNaN()
        val ready = !c.isNaN() && c >= 80 && (
            if (oilKnown) oil >= 75 else (!wasCold || hot80Since >= 240)
        )

        val st: WarmState = when {
            t < wait && speed < 3 -> WarmState(
                phase = 0, title = "Aspetta ${(wait - t).toInt().coerceAtLeast(1)} secondi",
                detail = "Lascia che l'olio arrivi ovunque, poi parti con calma. Non serve scaldarlo da fermo.",
                waitLeft = (wait - t).toInt().coerceAtLeast(1), maxRpm = 1500
            )
            ready -> WarmState(
                phase = 3, title = "Motore pronto: puoi spingere",
                detail = if (oilKnown) "Liquido ${c.toInt()}°C, olio ${oil.toInt()}°C." else "Liquido ${c.toInt()}°C e olio ormai in temperatura."
            )
            c.isNaN() -> WarmState(phase = 1, title = "Parti con calma", detail = "Temperatura non disponibile: guida dolce i primi 10 minuti.", maxRpm = 2300)
            c < 50 -> WarmState(phase = 1, title = "Motore freddo: guida dolce", detail = "Accelera piano e resta sotto i 2000 giri. Niente pedale a fondo.", maxRpm = 2000)
            c < 70 -> WarmState(phase = 1, title = "Si sta scaldando", detail = "Accelerazioni dolci, sotto i 2300 giri.", maxRpm = 2300)
            c < 80 -> WarmState(phase = 2, title = "Quasi pronto", detail = "Puoi salire fino a 2800 giri, ancora senza tirare le marce.", maxRpm = 2800)
            else -> WarmState(phase = 2, title = "Quasi pronto", detail = "Il liquido è caldo, l'olio ci mette qualche minuto in più. Fino a 3000 giri.", maxRpm = 3000)
        }

        // Avanzamento e minuti stimati
        val base = if (startCoolant.isNaN()) 10.0 else minOf(startCoolant, 79.0)
        val cPart = if (c.isNaN()) 0.0 else ((c - base) / (80.0 - base)).coerceIn(0.0, 1.0)
        val oPart = when {
            oilKnown -> ((oil - base) / (75.0 - base)).coerceIn(0.0, 1.0)
            !wasCold -> 1.0
            else -> (hot80Since / 240.0).coerceIn(0.0, 1.0)
        }
        val progress = if (st.phase == 3) 1f else (cPart * 0.75 + oPart * 0.25).toFloat()

        var minutes: Double? = null
        if (st.phase in 1..2 && !c.isNaN() && hist.size > 5) {
            val (t0, c0) = hist.first()
            val rate = (c - c0) / ((now - t0) / 1000.0)
            val toCoolant = if (c < 80 && rate > 0.02) (80 - c) / rate else if (c < 80) Double.NaN else 0.0
            val toOil = if (oilKnown) 0.0 else ((240 - hot80Since).coerceAtLeast(0.0))
            if (!toCoolant.isNaN()) minutes = (toCoolant + toOil) / 60.0
        }

        val over = st.maxRpm?.let { rpm > it } == true || (st.phase in 0..1 && load > 85)

        if (st.phase == 3 && readyTs == 0L) {
            readyTs = now
            if (wasCold) onReady("Motore in temperatura dopo ${(t / 60).toInt()} minuti: ora puoi spingere.")
        }

        live.value = st.copy(
            coolant = c, oil = oil, progress = progress, minutesLeft = minutes,
            overRev = over && speed > 3, readySince = readyTs, wasCold = wasCold
        )
    }

    companion object {
        val live = MutableStateFlow(WarmState())
    }
}
