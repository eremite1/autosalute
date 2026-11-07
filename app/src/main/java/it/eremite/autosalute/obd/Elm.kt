package it.eremite.autosalute.obd

import it.eremite.autosalute.Live
import kotlinx.coroutines.delay

/** Un parametro OBD (modo 01). */
class Pid(val pid: Int, val key: String, val label: String, val unit: String, val nb: Int, val dec: (IntArray) -> Double)

val PIDS = listOf(
    Pid(0x0C, "rpm", "Giri motore", "rpm", 2) { (it[0] * 256 + it[1]) / 4.0 },
    Pid(0x0D, "speed", "Velocità", "km/h", 1) { it[0].toDouble() },
    Pid(0x04, "load", "Carico motore", "%", 1) { it[0] * 100.0 / 255 },
    Pid(0x10, "maf", "Flusso aria", "g/s", 2) { (it[0] * 256 + it[1]) / 100.0 },
    Pid(0x0B, "map", "Pressione collettore", "kPa", 1) { it[0].toDouble() },
    Pid(0x23, "rail", "Pressione gasolio", "bar", 2) { (it[0] * 256 + it[1]) / 10.0 },
    Pid(0x49, "pedal", "Pedale acceleratore", "%", 1) { it[0] * 100.0 / 255 },
    Pid(0x5E, "fuel_rate", "Consumo istantaneo", "L/h", 2) { (it[0] * 256 + it[1]) / 20.0 },
    Pid(0x11, "throttle", "Farfalla", "%", 1) { it[0] * 100.0 / 255 },
    Pid(0x05, "coolant", "Temp. liquido motore", "°C", 1) { it[0] - 40.0 },
    Pid(0x0F, "intake", "Temp. aria aspirata", "°C", 1) { it[0] - 40.0 },
    Pid(0x5C, "oil", "Temp. olio", "°C", 1) { it[0] - 40.0 },
    Pid(0x46, "ambient", "Temp. esterna", "°C", 1) { it[0] - 40.0 },
    Pid(0x33, "baro", "Pressione atmosferica", "kPa", 1) { it[0].toDouble() },
    Pid(0x2F, "fuel_level", "Livello gasolio", "%", 1) { it[0] * 100.0 / 255 },
    Pid(0x42, "volt_ecu", "Tensione centralina", "V", 2) { (it[0] * 256 + it[1]) / 1000.0 },
    Pid(0x2C, "egr", "EGR comandata", "%", 1) { it[0] * 100.0 / 255 },
    Pid(0x2D, "egr_err", "Errore EGR", "%", 1) { it[0] * 100.0 / 128 - 100 },
    Pid(0x1F, "runtime", "Tempo da avvio", "s", 2) { (it[0] * 256 + it[1]).toDouble() },
    Pid(0x31, "dist_clear", "Km da cancellazione codici", "km", 2) { (it[0] * 256 + it[1]).toDouble() },
    Pid(0x21, "dist_mil", "Km con spia accesa", "km", 2) { (it[0] * 256 + it[1]).toDouble() },
)

/** Parametri letti a ogni giro (veloci); gli altri ogni 8 giri. */
val FAST = setOf(0x0C, 0x0D, 0x04, 0x10, 0x0B, 0x23, 0x49, 0x5E, 0x11)

val LABELS: Map<String, Pair<String, String>> =
    PIDS.associate { it.key to (it.label to it.unit) } + mapOf(
        "volt" to ("Batteria" to "V"),
        "boost" to ("Pressione turbo" to "bar")
    )

/** Dialogo con il chip ELM327. */
class Elm(private val link: ObdLink) {

    private suspend fun raw(c: String, t: Long = 1500): String = link.send(c, t)

    suspend fun init(): Boolean {
        raw("ATZ", 3000); delay(600)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) raw(c)
        var r = clean(raw("0100", 10_000))
        if (!r.contains("4100")) {
            Live.log("Ricerca automatica fallita, provo CAN 500k")
            raw("ATSP6")
            r = clean(raw("0100", 6000))
        }
        val ok = r.contains("4100")
        if (ok) try { Live.log("Protocollo: " + raw("ATDP").trim()) } catch (_: Exception) {}
        return ok
    }

    /** Toglie spazi/scritte e restituisce le righe esadecimali. */
    private fun lines(r: String): List<String> =
        r.uppercase().split('\r', '\n').map { it.replace(" ", "").replace("SEARCHING...", "") }
            .filter { it.isNotEmpty() }

    private fun clean(r: String): String = lines(r).joinToString("") { l ->
        if (l.length > 2 && l[1] == ':') l.substring(2) else l
    }

    private fun bytesAfter(hex: String, prefix: String, n: Int): IntArray? {
        var i = hex.indexOf(prefix)
        while (i >= 0 && i % 2 != 0) i = hex.indexOf(prefix, i + 1)
        if (i < 0) return null
        val s = i + prefix.length
        if (hex.length < s + n * 2) return null
        return try { IntArray(n) { hex.substring(s + it * 2, s + it * 2 + 2).toInt(16) } } catch (_: Exception) { null }
    }

    suspend fun supported(): Set<Int> {
        val out = HashSet<Int>()
        var base = 0
        while (base <= 0xA0) {
            if (base != 0 && !out.contains(base)) break
            val hex = clean(raw("01%02X".format(base), 2500))
            var idx = hex.indexOf("41%02X".format(base))
            while (idx >= 0) {
                val b = bytesAfter(hex.substring(idx), "41%02X".format(base), 4)
                if (b != null) for (k in 0 until 32) {
                    if ((b[k / 8] shr (7 - k % 8)) and 1 == 1) out.add(base + k + 1)
                }
                idx = hex.indexOf("41%02X".format(base), idx + 4)
            }
            base += 0x20
        }
        return out
    }

    suspend fun read(p: Pid): Double? {
        val hex = clean(raw("01%02X".format(p.pid)))
        if (hex.contains("NODATA") || hex.contains("ERROR") || hex.contains("?")) return null
        val b = bytesAfter(hex, "41%02X".format(p.pid), p.nb) ?: return null
        return p.dec(b)
    }

    suspend fun voltage(): Double? {
        val r = raw("ATRV").trim().uppercase().replace("V", "")
        return r.toDoubleOrNull()
    }

    /** Spia motore accesa e numero di codici memorizzati. */
    suspend fun monitor(): Pair<Boolean, Int>? {
        val b = bytesAfter(clean(raw("0101")), "4101", 4) ?: return null
        return ((b[0] and 0x80) != 0) to (b[0] and 0x7F)
    }

    /** Legge i codici di errore: modo "03" memorizzati, "07" in sospeso. */
    suspend fun dtcs(mode: String): List<String> {
        val r = raw(mode, 4000)
        val ls = lines(r)
        if (ls.any { it.contains("NODATA") }) return emptyList()
        val segs = ArrayList<String>()
        if (ls.any { it.length > 2 && it[1] == ':' }) {
            segs.add(ls.filter { it.length > 2 && it[1] == ':' }.joinToString("") { it.substring(2) })
        } else segs.addAll(ls)
        val pre = "4" + mode.last()
        val res = LinkedHashSet<String>()
        for (sRaw in segs) {
            val i = sRaw.indexOf(pre)
            if (i < 0) continue
            var s = sRaw.substring(i + 2)
            if ((s.length / 2) % 2 == 1) s = s.substring(2)
            var k = 0
            while (k + 4 <= s.length) {
                val code = s.substring(k, k + 4)
                k += 4
                if (code == "0000" || !code.all { it.isDigit() || it in 'A'..'F' }) continue
                val v = code.toInt(16)
                val letter = "PCBU"[(v shr 14) and 3]
                res.add(letter + ((v shr 12) and 3).toString() + "%03X".format(v and 0xFFF))
            }
        }
        return res.toList()
    }
}
