package it.eremite.autosalute.health

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Una segnalazione: sev 0 = info, 1 = attenzione, 2 = grave. */
data class Finding(val cat: String, val sev: Int, val title: String, val detail: String) {
    fun toJson(): JSONObject = JSONObject().put("c", cat).put("s", sev).put("t", title).put("d", detail)
    companion object {
        fun fromJson(j: JSONObject) = Finding(j.optString("c"), j.optInt("s"), j.optString("t"), j.optString("d"))
    }
}

/** Media/deviazione calcolate al volo. */
class Stat {
    var n = 0; var mean = 0.0; private var m2 = 0.0
    var min = Double.MAX_VALUE; var max = -Double.MAX_VALUE
    fun add(x: Double) {
        if (x.isNaN()) return
        n++; val d = x - mean; mean += d / n; m2 += d * (x - mean)
        if (x < min) min = x; if (x > max) max = x
    }
    val sd: Double get() = if (n > 1) sqrt(m2 / (n - 1)) else 0.0
}

/**
 * Definizione di una grandezza tenuta d'occhio viaggio dopo viaggio.
 * dir: +1 = è un problema se sale, -1 = se scende, 0 = in entrambi i sensi.
 * floor: variazione minima che consideriamo reale (evita falsi allarmi).
 */
class Metric(val key: String, val cat: String, val label: String, val unit: String, val dir: Int, val floor: Double, val what: String)

object Cat {
    const val MOTORE = "Motore"
    const val RAFF = "Raffreddamento"
    const val ELETTR = "Batteria e alternatore"
    const val TURBO = "Turbo e aspirazione"
    const val ALIM = "Alimentazione gasolio"
    const val CONSUMI = "Consumi"
    const val CODICI = "Codici errore"
    val ALL = listOf(CODICI, MOTORE, RAFF, ELETTR, TURBO, ALIM, CONSUMI)
}

object Health {

    val METRICS = listOf(
        Metric("idle_rpm", Cat.MOTORE, "Giri al minimo", "rpm", 0, 25.0, "minimo diverso dal solito"),
        Metric("idle_rpm_sd", Cat.MOTORE, "Stabilità del minimo", "rpm", 1, 6.0, "minimo più irregolare (iniettori, candelette, supporti, EGR)"),
        Metric("idle_load", Cat.MOTORE, "Carico al minimo", "%", 1, 2.0, "il motore fatica di più al minimo (attriti, alternatore, clima)"),
        Metric("idle_fuel", Cat.ALIM, "Consumo al minimo", "L/h", 1, 0.08, "consuma di più da fermo"),
        Metric("rail_1750", Cat.ALIM, "Pressione gasolio a 1750 giri sotto sforzo", "bar", -1, 40.0, "pressione del gasolio più bassa (filtro gasolio, pompa)"),
        Metric("rail_2250", Cat.ALIM, "Pressione gasolio a 2250 giri sotto sforzo", "bar", -1, 40.0, "pressione del gasolio più bassa (filtro gasolio, pompa)"),
        Metric("rail_2850", Cat.ALIM, "Pressione gasolio a 2850 giri sotto sforzo", "bar", -1, 40.0, "pressione del gasolio più bassa (filtro gasolio, pompa)"),
        Metric("boost_1750", Cat.TURBO, "Turbo a 1750 giri sotto sforzo", "bar", -1, 0.05, "il turbo spinge meno (perdite tubi, valvola, turbina)"),
        Metric("boost_2250", Cat.TURBO, "Turbo a 2250 giri sotto sforzo", "bar", -1, 0.05, "il turbo spinge meno (perdite tubi, valvola, turbina)"),
        Metric("boost_2850", Cat.TURBO, "Turbo a 2850 giri sotto sforzo", "bar", -1, 0.05, "il turbo spinge meno (perdite tubi, valvola, turbina)"),
        Metric("maf_1750", Cat.TURBO, "Aria aspirata a 1750 giri", "g/s", -1, 2.0, "entra meno aria (filtro aria sporco, debimetro, EGR)"),
        Metric("maf_2250", Cat.TURBO, "Aria aspirata a 2250 giri", "g/s", -1, 2.0, "entra meno aria (filtro aria sporco, debimetro, EGR)"),
        Metric("maf_2850", Cat.TURBO, "Aria aspirata a 2850 giri", "g/s", -1, 2.0, "entra meno aria (filtro aria sporco, debimetro, EGR)"),
        Metric("maf_idle", Cat.TURBO, "Aria aspirata al minimo", "g/s", 0, 0.8, "aria al minimo anomala (debimetro, valvola EGR)"),
        Metric("intake_delta", Cat.TURBO, "Aria aspirata rispetto all'esterno", "°C", 1, 3.0, "l'aria entra più calda (intercooler meno efficiente)"),
        Metric("coolant_cruise", Cat.RAFF, "Temperatura motore in marcia", "°C", 0, 2.5, "temperatura di marcia diversa dal solito (termostato, liquido, ventola)"),
        Metric("warmup_s", Cat.RAFF, "Tempo di riscaldamento", "s", 1, 90.0, "ci mette di più a scaldarsi (termostato che resta aperto)"),
        Metric("coolant_max", Cat.RAFF, "Temperatura massima motore", "°C", 1, 3.0, "il motore arriva più caldo del solito"),
        Metric("volt_rest", Cat.ELETTR, "Batteria a motore spento", "V", -1, 0.08, "la batteria a riposo è più scarica"),
        Metric("crank_min", Cat.ELETTR, "Batteria durante l'avviamento", "V", -1, 0.25, "la batteria cede di più quando avvii (batteria che invecchia)"),
        Metric("volt_run", Cat.ELETTR, "Tensione di ricarica", "V", 0, 0.45, "l'alternatore carica in modo diverso"),
        Metric("cons", Cat.CONSUMI, "Consumo medio", "L/100km", 1, 0.3, "consuma di più (gomme, filtri, iniettori, stile di guida)"),
    )
    private val byKey = METRICS.associateBy { it.key }

    fun metric(k: String) = byKey[k]

    /** Analizza un viaggio. hist = viaggi precedenti, dal più recente. */
    fun evaluate(
        cur: Map<String, Double>, hist: List<Map<String, Double>>,
        dtcs: List<String>, pending: List<String>, mil: Boolean
    ): List<Finding> {
        val f = ArrayList<Finding>()
        fun g(k: String) = cur[k]?.takeIf { !it.isNaN() }

        // --- Codici errore ---
        if (mil) f += Finding(Cat.CODICI, 2, "Spia motore accesa", "La centralina ha acceso la spia: fai controllare l'auto al più presto.")
        if (dtcs.isNotEmpty()) f += Finding(Cat.CODICI, 2, "Codici errore: ${dtcs.joinToString()}", "Errori memorizzati dalla centralina. ${dtcs.joinToString("\n") { "$it: " + Dtc.describe(it) }}")
        if (pending.isNotEmpty()) f += Finding(Cat.CODICI, 1, "Codici in sospeso: ${pending.joinToString()}", "La centralina ha visto un problema ma non lo ha ancora confermato: è il primo segnale di qualcosa che sta iniziando. ${pending.joinToString("\n") { "$it: " + Dtc.describe(it) }}")

        // --- Soglie assolute ---
        g("coolant_max")?.let {
            if (it >= 110) f += Finding(Cat.RAFF, 2, "Motore surriscaldato (${it.toInt()}°C)", "Controlla subito il livello del liquido di raffreddamento e la ventola.")
            else if (it >= 104) f += Finding(Cat.RAFF, 1, "Motore molto caldo (${it.toInt()}°C)", "Temperatura sopra il normale: tieni d'occhio il liquido di raffreddamento.")
        }
        val dur = g("dur") ?: 0.0
        g("coolant_cruise")?.let {
            if (dur > 1200 && it < 76) f += Finding(Cat.RAFF, 1, "Motore non arriva in temperatura (${it.toInt()}°C)", "In marcia resta sotto gli 80°C: probabile termostato bloccato aperto. Consuma di più e intasa il filtro antiparticolato.")
        }
        g("oil_max")?.let { if (it >= 128) f += Finding(Cat.MOTORE, 1, "Olio molto caldo (${it.toInt()}°C)", "Temperatura olio alta: controlla il livello olio.") }
        g("volt_rest")?.let {
            if (it < 11.9) f += Finding(Cat.ELETTR, 2, "Batteria scarica (${fmt(it)} V)", "A motore spento la batteria è sotto 11,9 V: rischi di non ripartire.")
            else if (it < 12.25) f += Finding(Cat.ELETTR, 1, "Batteria debole (${fmt(it)} V)", "A riposo dovrebbe stare sopra 12,4 V. Falla controllare se continua.")
        }
        g("crank_min")?.let {
            if (it < 8.5) f += Finding(Cat.ELETTR, 2, "Batteria cede all'avviamento (${fmt(it)} V)", "Durante l'avviamento la tensione scende troppo: batteria a fine vita.")
            else if (it < 9.6) f += Finding(Cat.ELETTR, 1, "Avviamento faticoso (${fmt(it)} V)", "La batteria cede parecchio quando avvii. In inverno peggiora.")
        }
        g("volt_run_max")?.let { mx ->
            if (mx < 13.1 && dur > 900) f += Finding(Cat.ELETTR, 2, "L'alternatore non ha mai caricato (max ${fmt(mx)} V)",
                "In tutto il viaggio la tensione non ha mai superato 13 V: alternatore o cinghia da controllare.")
        }
        g("volt_run_max")?.let { if (it > 15.0) f += Finding(Cat.ELETTR, 1, "Tensione troppo alta (${fmt(it)} V)", "Il regolatore dell'alternatore potrebbe essere difettoso.") }

        // --- Confronto con la storia della TUA auto ---
        val n = hist.size
        if (n < 5) {
            f += Finding("Apprendimento", 0, "Sto imparando la tua auto", "Servono ancora ${5 - n} viaggi per conoscere i valori normali di questa auto. Dopo, ti avviso anche delle piccole variazioni.")
        } else {
            for (m in METRICS) {
                val x = g(m.key) ?: continue
                val past = hist.take(40).mapNotNull { it[m.key]?.takeIf { v -> !v.isNaN() } }
                if (past.size < 5) continue
                val st = Stat().apply { past.forEach { add(it) } }
                val spread = max(st.sd, m.floor)
                val z = (x - st.mean) / spread
                val bad = when (m.dir) { 1 -> z; -1 -> -z; else -> abs(z) }
                if (bad >= 3.0) f += Finding(m.cat, 1, "${m.label}: ${fmt(x)} ${m.unit}", "Di solito è ${fmt(st.mean)} ${m.unit}. Variazione netta: ${m.what}.")
                else if (bad >= 2.0) f += Finding(m.cat, 0, "${m.label}: ${fmt(x)} ${m.unit}", "Un po' fuori dal solito (${fmt(st.mean)} ${m.unit}). Da tenere d'occhio: ${m.what}.")

                // tendenza lenta: confronta ultimi 10 viaggi con i 20 precedenti
                if (past.size >= 25) {
                    val recent = listOf(x) + past.take(9)
                    val older = past.drop(9).take(25)
                    val rm = recent.average(); val om = older.average()
                    val osd = max(Stat().apply { older.forEach { add(it) } }.sd, m.floor)
                    val drift = (rm - om) / osd
                    val badDrift = when (m.dir) { 1 -> drift; -1 -> -drift; else -> abs(drift) }
                    if (badDrift >= 1.5) f += Finding(m.cat, 1, "${m.label} in peggioramento lento", "Negli ultimi viaggi media ${fmt(rm)} ${m.unit}, prima ${fmt(om)} ${m.unit}. È un cambiamento graduale: ${m.what}.")
                }
            }
        }
        return f.distinctBy { it.title }
    }

    fun score(f: List<Finding>): Int {
        var s = 100
        for (x in f) s -= when (x.sev) { 2 -> 25; 1 -> 8; else -> if (x.cat == "Apprendimento") 0 else 2 }
        return s.coerceIn(0, 100)
    }

    fun fmt(v: Double): String = when {
        abs(v) >= 100 -> "%.0f".format(v)
        abs(v) >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }
}

/** Raccoglie i dati durante il viaggio e ne fa il riassunto. */
class TripAcc {
    var km = 0.0; var dur = 0.0; var fuel = 0.0; var fuelKnown = false
    var maxSpeed = 0.0; private var movingTime = 0.0
    var engineStarted = false; var engineStartTs = 0L
    var startCoolant = Double.NaN; var warmup = Double.NaN
    var volRest = Double.NaN; var crankMin = Double.NaN
    val dtcs = LinkedHashSet<String>(); val pending = LinkedHashSet<String>(); var mil = false
    private val st = HashMap<String, Stat>()
    private fun s(k: String) = st.getOrPut(k) { Stat() }

    private fun bin(rpm: Double): String? = when {
        rpm in 1500.0..2000.0 -> "1750"
        rpm in 2000.0..2500.0 -> "2250"
        rpm in 2500.0..3200.0 -> "2850"
        else -> null
    }

    fun add(v: Map<String, Double>, dt: Double, now: Long) {
        val rpm = v["rpm"] ?: Double.NaN
        val speed = v["speed"] ?: 0.0
        val volt = v["volt"] ?: Double.NaN
        val coolant = v["coolant"] ?: Double.NaN
        val load = v["load"] ?: Double.NaN
        val d = dt.coerceIn(0.0, 5.0)

        if (!engineStarted) {
            if (!volt.isNaN()) {
                if (volRest.isNaN() && (rpm.isNaN() || rpm < 50)) volRest = volt
                crankMin = if (crankMin.isNaN()) volt else minOf(crankMin, volt)
            }
            if (!rpm.isNaN() && rpm > 400) {
                engineStarted = true; engineStartTs = now; startCoolant = coolant
            } else return
        }
        dur += d
        km += speed * d / 3600.0
        if (speed > 3) { movingTime += d }
        maxSpeed = maxOf(maxSpeed, speed)
        v["fuel_rate"]?.let { fuel += it * d / 3600.0; fuelKnown = true }

        if (!coolant.isNaN()) {
            s("coolant").add(coolant)
            if (warmup.isNaN() && !startCoolant.isNaN() && startCoolant < 40 && coolant >= 80)
                warmup = (now - engineStartTs) / 1000.0
            if (speed > 60 && dur > 600) s("coolant_cruise").add(coolant)
        }
        v["oil"]?.let { s("oil").add(it) }
        if (!volt.isNaN() && !rpm.isNaN() && rpm > 700 && dur > 20) s("volt_run").add(volt)

        val warm = !coolant.isNaN() && coolant > 75
        if (speed < 1 && !rpm.isNaN() && rpm in 600.0..1200.0 && warm) {
            s("idle_rpm").add(rpm)
            if (!load.isNaN()) s("idle_load").add(load)
            v["fuel_rate"]?.let { s("idle_fuel").add(it) }
            v["maf"]?.let { s("maf_idle").add(it) }
        }
        val b = if (rpm.isNaN()) null else bin(rpm)
        if (b != null && warm && !load.isNaN()) {
            if (load >= 75) {
                v["boost"]?.let { s("boost_$b").add(it) }
                v["maf"]?.let { s("maf_$b").add(it) }
            }
            if (load >= 60) v["rail"]?.let { s("rail_$b").add(it) }
        }
        if (speed > 60) {
            val i = v["intake"]; val a = v["ambient"]
            if (i != null && a != null) s("intake_delta").add(i - a)
        }
        if (!load.isNaN()) s("load").add(load)
        if (!rpm.isNaN()) s("rpm").add(rpm)
    }

    val avgSpeed: Double get() = if (movingTime > 0) km / (movingTime / 3600.0) else 0.0
    val cons: Double get() = if (fuelKnown && km > 2) fuel / km * 100 else Double.NaN

    fun metrics(): Map<String, Double> {
        val m = HashMap<String, Double>()
        fun put(k: String, stat: String, minN: Int) { st[stat]?.let { if (it.n >= minN) m[k] = it.mean } }
        put("idle_rpm", "idle_rpm", 20); put("idle_load", "idle_load", 20); put("idle_fuel", "idle_fuel", 20)
        put("maf_idle", "maf_idle", 20)
        st["idle_rpm"]?.let { if (it.n >= 20) m["idle_rpm_sd"] = it.sd }
        for (b in listOf("1750", "2250", "2850")) {
            put("boost_$b", "boost_$b", 3); put("maf_$b", "maf_$b", 3); put("rail_$b", "rail_$b", 3)
        }
        put("intake_delta", "intake_delta", 20)
        put("coolant_cruise", "coolant_cruise", 30)
        put("volt_run", "volt_run", 20)
        st["volt_run"]?.let { if (it.n >= 20) { m["volt_run_min"] = it.min; m["volt_run_max"] = it.max } }
        st["coolant"]?.let { if (it.n > 0) m["coolant_max"] = it.max }
        st["oil"]?.let { if (it.n > 0) m["oil_max"] = it.max }
        st["load"]?.let { if (it.n > 0) m["load_avg"] = it.mean }
        st["rpm"]?.let { if (it.n > 0) m["rpm_max"] = it.max }
        if (!warmup.isNaN()) m["warmup_s"] = warmup
        if (!volRest.isNaN()) m["volt_rest"] = volRest
        if (!crankMin.isNaN() && !volRest.isNaN() && crankMin < volRest - 0.3) m["crank_min"] = crankMin
        if (!cons.isNaN()) m["cons"] = cons
        m["dur"] = dur; m["km"] = km
        return m
    }
}
