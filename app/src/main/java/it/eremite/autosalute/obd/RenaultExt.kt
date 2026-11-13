package it.eremite.autosalute.obd

import it.eremite.autosalute.Live

/**
 * Parametri riservati Renault/Dacia del motore diesel K9K (1.5 dCi), letti dalla centralina motore
 * (indirizzo 7E0) con il servizio 22. Fonte: elenco pubblico github.com/MeganeClubUkraine/k9k_pids.
 * Se la centralina non li conosce risponde "7F 22 31" e il valore viene semplicemente ignorato.
 */
class RenaultExt(private val elm: Elm) {
    var available = false; private set
    private val dead = HashSet<String>()

    private class Did(val id: String, val key: String, val nb: Int, val f: (IntArray) -> Double)

    private val fap = listOf(
        Did("242C", "soot_g", 2) { (it[0] * 256 + it[1]) / 100.0 },
        Did("24A9", "km_regen", 3) { (it[0] * 65536 + it[1] * 256 + it[2]).toDouble() },
        Did("246D", "regen_cmd", 1) { it[0].toDouble() },
        Did("2432", "dpf_set_temp", 2) { ((it[0] * 256 + it[1]) - 2730) / 10.0 },
        Did("2442", "dpf_in_temp", 2) { ((it[0] * 256 + it[1]) - 2730) / 10.0 },
        Did("2542", "dpf_dp_mbar", 2) { ((it[0] * 256 + it[1]) - 32768).toDouble() },
    )
    private val slow = listOf(
        Did("24A5", "soot_after", 2) { (it[0] * 256 + it[1]) / 100.0 },
        Did("2481", "regen_count", 1) { it[0].toDouble() },
        Did("2485", "regen_fail", 1) { it[0].toDouble() },
        Did("2487", "regen_last_min", 2) { (it[0] * 256 + it[1]) / 600.0 },
        Did("2488", "regen_hours", 2) { (it[0] * 256 + it[1]) / 3600.0 },
        Did("2434", "regen_static", 1) { (it[0] and 7).toDouble() },
        Did("24BB", "km_dpf", 3) { (it[0] * 65536 + it[1] * 256 + it[2]).toDouble() },
        Did("2006", "odo_ecu", 3) { (it[0] * 65536 + it[1] * 256 + it[2]).toDouble() },
        Did("2294", "oil_dilution", 4) {
            val v = (it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()
            v.toInt().toDouble() / 214748364.8
        },
        Did("2052", "glow_cmd", 1) { it[0] / 2.55 },
    )

    private suspend fun read(d: Did): Double? {
        if (d.id in dead) return null
        val r = elm.query("22" + d.id, 1500)
        val hex = elm.clean(r)
        if (hex.contains("7F22") || hex.contains("NODATA") || hex.contains("ERROR")) { dead.add(d.id); return null }
        val b = elm.bytesAfter(hex, "62" + d.id, d.nb) ?: return null
        return try { d.f(b) } catch (_: Exception) { null }
    }

    /** Prova a parlare con la centralina Renault. Solo per auto CAN (2008 in poi). */
    suspend fun probe(protocol: String, vin: String?): Boolean {
        val renaultVin = vin == null || vin.startsWith("VF1") || vin.startsWith("UU1") || vin.startsWith("VF6") || vin.startsWith("VFA")
        if (!protocol.contains("15765") || !renaultVin) return false
        try {
            elm.query("ATSH7E0")
            available = read(fap[0]) != null || read(fap[1]) != null
            if (!available) { elm.query("ATSH7DF"); dead.clear() }
        } catch (e: java.io.IOException) { throw e } catch (_: Exception) { available = false }
        Live.log(if (available) "Dati Renault del FAP disponibili" else "Dati Renault del FAP non disponibili su questa auto")
        return available
    }

    suspend fun readFap(into: MutableMap<String, Double>) {
        if (!available) return
        for (d in fap) read(d)?.let { into[d.key] = it }
    }

    suspend fun readSlow(into: MutableMap<String, Double>) {
        if (!available) return
        for (d in slow) read(d)?.let { into[d.key] = it }
    }
}
