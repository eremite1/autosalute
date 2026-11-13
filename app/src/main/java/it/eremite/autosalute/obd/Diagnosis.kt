package it.eremite.autosalute.obd

import android.content.Context
import it.eremite.autosalute.Live
import it.eremite.autosalute.data.Db
import it.eremite.autosalute.health.Dtc
import it.eremite.autosalute.health.Stat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** sev: -1 informazione, 0 tutto ok, 1 da controllare, 2 problema. */
data class DiagItem(val group: String, val sev: Int, val title: String, val detail: String, val action: String = "") {
    fun toJson(): JSONObject = JSONObject().put("g", group).put("s", sev).put("t", title).put("d", detail).put("a", action)
    companion object {
        fun from(j: JSONObject) = DiagItem(j.optString("g"), j.optInt("s"), j.optString("t"), j.optString("d"), j.optString("a"))
    }
}

data class DiagState(
    val running: Boolean = false, val waiting: Boolean = false, val step: String = "", val progress: Float = 0f,
    val items: List<DiagItem> = emptyList(), val reportId: Long? = null, val error: String? = null,
)

object DiagCtl {
    val state = MutableStateFlow(DiagState())
    @Volatile var requested = false

    fun fail(msg: String) {
        if (!requested && !state.value.waiting) return
        requested = false
        state.value = DiagState(error = msg)
    }
}

object DiagGroups {
    const val ID = "Identificazione"
    const val CODES = "Spia e codici errore"
    const val READY = "Autodiagnosi della centralina"
    const val HISTORY = "Storia recente"
    const val BATTERY = "Batteria e ricarica"
    const val ENGINE = "Motore al minimo"
    const val MIX = "Aria, carburante e scarico"
    const val FAP = "Filtro antiparticolato (FAP)"
    val ORDER = listOf(CODES, BATTERY, ENGINE, FAP, MIX, READY, HISTORY, ID)
}

class Diagnosis(private val ctx: Context, private val elm: Elm, private val sup: Set<Int>, private val vin: String?, private val car: Long,
                private val ext: RenaultExt? = null) {
    private val items = ArrayList<DiagItem>()
    private val values = JSONObject()
    private var diesel = false

    private fun add(i: DiagItem) {
        items.add(i)
        DiagCtl.state.value = DiagCtl.state.value.copy(items = items.toList())
    }

    private fun step(t: String, p: Float) {
        DiagCtl.state.value = DiagCtl.state.value.copy(running = true, waiting = false, step = t, progress = p)
        Live.log("Diagnosi: $t")
    }

    private suspend fun num(pid: Int, nb: Int, f: (IntArray) -> Double): Double? =
        if (pid in sup) try { elm.pid(pid, nb)?.let(f) } catch (e: java.io.IOException) { throw e } catch (_: Exception) { null } else null

    suspend fun run(): Long {
        DiagCtl.state.value = DiagState(running = true, step = "Avvio", progress = 0f)
        val ts = System.currentTimeMillis()

        // 1 · Identificazione
        step("Identifico l'auto", 0.05f)
        val calib = try { elm.info09(0x04) } catch (_: Exception) { null }
        val ecu = try { elm.info09(0x0A) } catch (_: Exception) { null }
        val proto = elm.protocol()
        val fuelType = num(0x51, 1) { it[0].toDouble() }?.toInt()
        val mon = elm.pid(0x01, 4)
        diesel = mon?.let { (it[1] and 0x08) != 0 } ?: (fuelType == 4)
        val fuelName = when (fuelType) {
            1 -> "Benzina"; 4 -> "Diesel"; 5 -> "GPL"; 6 -> "Metano"; 8 -> "Elettrica"; 9, 10, 11, 12, 13, 14 -> "Bifuel"; 15, 16, 17, 18, 19, 20 -> "Ibrida"
            else -> if (diesel) "Diesel" else "Benzina (dedotto)"
        }
        add(DiagItem(DiagGroups.ID, -1, "Telaio (VIN): ${vin ?: "non fornito"}",
            listOfNotNull("Alimentazione: $fuelName", "Protocollo: $proto", ecu?.let { "Centralina: $it" }, calib?.let { "Software: $it" },
                "Parametri disponibili: ${sup.size}").joinToString("\n")))
        values.put("vin", vin ?: "").put("fuel", fuelName).put("protocol", proto).put("ecu", ecu ?: "").put("calib", calib ?: "")

        // 2 · Spia e codici
        step("Leggo spia motore e codici errore", 0.15f)
        val mil = mon?.let { (it[0] and 0x80) != 0 } ?: false
        val stored = elm.dtcs("03")
        val pending = elm.dtcs("07")
        val permanent = try { elm.dtcs("0A") } catch (_: Exception) { emptyList() }
        if (mil) add(DiagItem(DiagGroups.CODES, 2, "Spia motore accesa",
            "La centralina ha acceso la spia gialla del motore: ha rilevato un guasto che può aumentare consumi o emissioni o danneggiare componenti.",
            "Fai controllare l'auto da un'officina, specie se la spia lampeggia."))
        if (stored.isEmpty() && pending.isEmpty() && permanent.isEmpty() && !mil)
            add(DiagItem(DiagGroups.CODES, 0, "Nessun codice errore", "La centralina del motore non ha memorizzato guasti."))
        stored.forEach { add(DiagItem(DiagGroups.CODES, 2, "Errore $it", Dtc.describe(it).replaceFirstChar { c -> c.uppercase() } + ". Guasto confermato e memorizzato.",
            "Cerca il codice con il modello dell'auto o mostralo al meccanico.")) }
        pending.filter { it !in stored }.forEach { add(DiagItem(DiagGroups.CODES, 1, "In sospeso $it", Dtc.describe(it).replaceFirstChar { c -> c.uppercase() } +
            ". La centralina l'ha visto ma non ancora confermato: può essere l'inizio di un guasto o un episodio isolato.", "Ripeti la diagnosi tra qualche giorno.")) }
        permanent.filter { it !in stored }.forEach { add(DiagItem(DiagGroups.CODES, 1, "Permanente $it", Dtc.describe(it).replaceFirstChar { c -> c.uppercase() } +
            ". Codice che non si cancella finché la centralina non verifica che il guasto è risolto.")) }
        values.put("dtc", JSONArray(stored)).put("pending", JSONArray(pending)).put("permanent", JSONArray(permanent)).put("mil", mil)

        // Fotografia del momento del guasto
        if (stored.isNotEmpty() || pending.isNotEmpty()) {
            step("Leggo la fotografia del momento del guasto", 0.25f)
            val rpm = try { elm.freeze(0x0C, 2)?.let { (it[0] * 256 + it[1]) / 4.0 } } catch (_: Exception) { null }
            val spd = try { elm.freeze(0x0D, 1)?.let { it[0].toDouble() } } catch (_: Exception) { null }
            val cool = try { elm.freeze(0x05, 1)?.let { it[0] - 40.0 } } catch (_: Exception) { null }
            val load = try { elm.freeze(0x04, 1)?.let { it[0] * 100.0 / 255 } } catch (_: Exception) { null }
            val parts = listOfNotNull(rpm?.let { "${it.toInt()} giri" }, spd?.let { "${it.toInt()} km/h" },
                cool?.let { "motore a ${it.toInt()}°C" }, load?.let { "carico ${it.toInt()}%" })
            if (parts.isNotEmpty()) add(DiagItem(DiagGroups.CODES, -1, "Condizioni quando è comparso il guasto", parts.joinToString(", ") + ".",
                "Utile al meccanico per riprodurre il problema."))
        }

        // 3 · Monitor di prontezza
        step("Controllo l'autodiagnosi della centralina", 0.32f)
        if (mon != null) readiness(mon)

        // 4 · Storia
        step("Leggo la storia recente", 0.4f)
        val distMil = num(0x21, 2) { (it[0] * 256 + it[1]).toDouble() }
        val distClr = num(0x31, 2) { (it[0] * 256 + it[1]).toDouble() }
        val warm = num(0x30, 1) { it[0].toDouble() }
        val timeClr = num(0x4E, 2) { (it[0] * 256 + it[1]).toDouble() }
        distMil?.let {
            if (it > 0) add(DiagItem(DiagGroups.HISTORY, 1, "Percorsi ${it.toInt()} km con la spia accesa", "L'auto ha viaggiato con un guasto segnalato."))
        }
        val recentClear = (distClr != null && distClr < 80) || (warm != null && warm < 5) || (timeClr != null && timeClr < 60)
        if (recentClear) add(DiagItem(DiagGroups.HISTORY, 1, "Codici cancellati di recente",
            listOfNotNull(distClr?.let { "${it.toInt()} km" }, warm?.let { "${it.toInt()} avviamenti a caldo" }, timeClr?.let { "${it.toInt()} minuti di motore" })
                .joinToString(", ", "Dalla cancellazione: ") +
                ". Succede dopo un intervento in officina o se si stacca la batteria, ma anche quando qualcuno vuole nascondere un difetto.",
            "Se stai valutando un'auto usata, chiedi perché e ripeti la diagnosi dopo qualche giorno di uso."))
        else distClr?.let { add(DiagItem(DiagGroups.HISTORY, 0, "Nessuna cancellazione recente", "Ultima cancellazione codici: ${it.toInt()} km fa.")) }
        values.put("dist_clear", distClr ?: -1).put("dist_mil", distMil ?: -1)

        // 5 · Batteria
        step("Misuro la batteria", 0.47f)
        val v0 = elm.voltage()
        val rpm0 = num(0x0C, 2) { (it[0] * 256 + it[1]) / 4.0 } ?: 0.0
        val running = rpm0 > 400
        if (v0 != null && !running) {
            values.put("volt_rest", v0)
            add(when {
                v0 >= 12.4 -> DiagItem(DiagGroups.BATTERY, 0, "Batteria carica (${f2(v0)} V)", "A motore spento la tensione è buona.")
                v0 >= 12.2 -> DiagItem(DiagGroups.BATTERY, 1, "Batteria poco carica (${f2(v0)} V)", "Dovrebbe stare sopra 12,4 V a motore spento.",
                    "Fai un viaggio lungo o ricaricala; se resta bassa, falla testare.")
                else -> DiagItem(DiagGroups.BATTERY, 2, "Batteria scarica (${f2(v0)} V)", "Sotto 12,2 V rischi di non riuscire ad avviare.",
                    "Ricaricala e falla testare: potrebbe essere da sostituire.")
            })
        }

        // 6 · Prove a motore acceso
        if (!running) {
            add(DiagItem(DiagGroups.ENGINE, -1, "Prove a motore acceso non eseguite",
                "Il motore era spento. Per una diagnosi completa avvia il motore, lascialo al minimo e ripeti.", ""))
        } else {
            step("Prove al minimo: tieni il motore acceso senza accelerare", 0.55f)
            val keys = listOf(
                Triple(0x0C, 2) { b: IntArray -> (b[0] * 256 + b[1]) / 4.0 } to "rpm",
                Triple(0x0D, 1) { b: IntArray -> b[0].toDouble() } to "speed",
                Triple(0x04, 1) { b: IntArray -> b[0] * 100.0 / 255 } to "load",
                Triple(0x05, 1) { b: IntArray -> b[0] - 40.0 } to "coolant",
                Triple(0x06, 1) { b: IntArray -> (b[0] - 128) * 100.0 / 128 } to "stft",
                Triple(0x07, 1) { b: IntArray -> (b[0] - 128) * 100.0 / 128 } to "ltft",
                Triple(0x14, 2) { b: IntArray -> b[0] / 200.0 } to "o2",
                Triple(0x10, 2) { b: IntArray -> (b[0] * 256 + b[1]) / 100.0 } to "maf",
                Triple(0x2D, 1) { b: IntArray -> b[0] * 100.0 / 128 - 100 } to "egr_err",
                Triple(0x1F, 2) { b: IntArray -> (b[0] * 256 + b[1]).toDouble() } to "runtime",
            ).filter { it.first.first in sup }
            val st = HashMap<String, Stat>()
            val o2 = ArrayList<Double>()
            val volts = Stat()
            val n = 24
            for (i in 0 until n) {
                for ((t, k) in keys) {
                    if (k == "runtime" && i % 6 != 0) continue
                    val v = try { elm.pid(t.first, t.second)?.let(t.third) } catch (e: java.io.IOException) { throw e } catch (_: Exception) { null } ?: continue
                    st.getOrPut(k) { Stat() }.add(v)
                    if (k == "o2") o2.add(v)
                }
                if (i % 3 == 0) elm.voltage()?.let { volts.add(it) }
                step("Prove al minimo: tieni il motore acceso senza accelerare", 0.55f + 0.38f * (i + 1) / n)
                delay(150)
            }
            fun m(k: String) = st[k]?.takeIf { it.n > 0 }?.mean
            val coolant = m("coolant")
            val warmEngine = coolant != null && coolant >= 75
            val rt = st["runtime"]?.max
            val moving = (m("speed") ?: 0.0) > 3
            values.put("coolant", coolant ?: -99).put("idle_rpm", m("rpm") ?: -1).put("idle_sd", st["rpm"]?.sd ?: -1)
                .put("volt_run", if (volts.n > 0) volts.mean else -1.0)

            // ricarica
            if (volts.n > 0) {
                val v = volts.mean
                add(when {
                    v > 15.0 -> DiagItem(DiagGroups.BATTERY, 2, "Tensione di ricarica troppo alta (${f2(v)} V)", "Il regolatore dell'alternatore potrebbe essere guasto: rischi di rovinare la batteria.",
                        "Fai controllare l'alternatore.")
                    v >= 13.5 -> DiagItem(DiagGroups.BATTERY, 0, "Alternatore carica bene (${f2(v)} V)", "Con il motore acceso la tensione è nella norma (13,5–14,8 V).")
                    v >= 13.0 -> DiagItem(DiagGroups.BATTERY, 1, "Ricarica un po' bassa (${f2(v)} V)",
                        "Può essere normale su auto con alternatore \"intelligente\" (batteria già carica), ma se resta bassa a lungo la batteria si scarica.",
                        "Ricontrolla con fari e clima accesi.")
                    else -> DiagItem(DiagGroups.BATTERY, 2, "L'alternatore non carica (${f2(v)} V)", "Con il motore acceso la tensione dovrebbe superare 13,5 V.",
                        "Controlla alternatore e cinghia: rischi di restare a piedi.")
                })
            }

            // temperatura
            coolant?.let { c ->
                add(when {
                    c >= 108 -> DiagItem(DiagGroups.ENGINE, 2, "Motore troppo caldo (${c.toInt()}°C)", "Rischio di danni seri al motore.",
                        "Spegni e controlla liquido di raffreddamento e ventola a motore freddo.")
                    c >= 100 -> DiagItem(DiagGroups.ENGINE, 1, "Motore molto caldo (${c.toInt()}°C)", "Al minimo è un po' alta: verifica che la ventola parta.")
                    rt != null && rt > 900 && c < 70 -> DiagItem(DiagGroups.ENGINE, 1, "Il motore non si scalda (${c.toInt()}°C)",
                        "Dopo ${(rt / 60).toInt()} minuti dovrebbe essere sopra 80°C: probabile termostato bloccato aperto.", "Fai controllare il termostato.")
                    c < 60 -> DiagItem(DiagGroups.ENGINE, -1, "Motore ancora freddo (${c.toInt()}°C)",
                        "Alcune prove sono più precise a motore caldo: per un controllo accurato ripeti dopo 15 minuti di guida.")
                    else -> DiagItem(DiagGroups.ENGINE, 0, "Temperatura motore regolare (${c.toInt()}°C)", "")
                })
            }

            // minimo
            if (!moving) st["rpm"]?.let { r ->
                if (r.n >= 8) {
                    val mean = r.mean; val sd = r.sd
                    val stableTxt = "Media ${mean.toInt()} giri, oscillazione ±${sd.toInt()}."
                    add(when {
                        sd > 70 -> DiagItem(DiagGroups.ENGINE, 2, "Minimo molto irregolare", "$stableTxt Il motore \"balla\": possibili mancate accensioni, iniettori, candele/candelette o prese d'aria.",
                            "Fai controllare il motore.")
                        sd > 35 -> DiagItem(DiagGroups.ENGINE, 1, "Minimo un po' irregolare", "$stableTxt Può dipendere da clima o ventola accesi; se lo senti vibrare, fallo controllare.")
                        warmEngine && (mean < 550 || mean > 1100) -> DiagItem(DiagGroups.ENGINE, 1, "Minimo fuori norma (${mean.toInt()} giri)",
                            "A motore caldo di solito sta tra 650 e 950 giri.", "Controlla se ci sono utilizzatori accesi; altrimenti fallo verificare.")
                        else -> DiagItem(DiagGroups.ENGINE, 0, "Minimo stabile", stableTxt)
                    })
                }
                m("load")?.let { l ->
                    if (warmEngine && l > 40) add(DiagItem(DiagGroups.ENGINE, 1, "Il motore fatica al minimo (carico ${l.toInt()}%)",
                        "Normale con clima, luci e sbrinatori accesi; altrimenti può indicare attriti o problemi di alimentazione."))
                }
            }

            // miscela (benzina)
            if (!diesel) {
                val lt = m("ltft"); val stf = m("stft")
                if (lt != null) {
                    val tot = lt + (stf ?: 0.0)
                    add(when {
                        abs(lt) > 20 || abs(tot) > 25 -> DiagItem(DiagGroups.MIX, 2, "Miscela aria-benzina fuori controllo (${sgn(lt)}%)",
                            if (lt > 0) "La centralina aggiunge molta benzina: probabile presa d'aria, debimetro sporco o pompa debole."
                            else "La centralina toglie molta benzina: possibili iniettori che perdono o sonda lambda guasta.", "Fai controllare.")
                        abs(lt) > 10 -> DiagItem(DiagGroups.MIX, 1, "Miscela da tenere d'occhio (${sgn(lt)}%)",
                            if (lt > 0) "La centralina compensa aggiungendo benzina: piccola presa d'aria o debimetro sporco."
                            else "La centralina compensa togliendo benzina.")
                        else -> DiagItem(DiagGroups.MIX, 0, "Miscela aria-benzina corretta (${sgn(lt)}%)", "Le correzioni della centralina sono piccole.")
                    })
                }
                if (o2.size >= 8 && warmEngine) {
                    var cross = 0
                    for (i in 1 until o2.size) if ((o2[i - 1] - 0.45) * (o2[i] - 0.45) < 0) cross++
                    add(if (cross >= 2) DiagItem(DiagGroups.MIX, 0, "Sonda lambda attiva", "Il sensore dei gas di scarico oscilla come dovrebbe ($cross cambi).")
                    else DiagItem(DiagGroups.MIX, 1, "Sonda lambda lenta o ferma", "Il sensore prima del catalizzatore oscilla poco (${o2.size} letture, $cross cambi). Può aumentare i consumi.",
                        "Ripeti a motore ben caldo; se resta così, fai controllare la sonda."))
                }
            } else {
                m("egr_err")?.let { e ->
                    if (warmEngine) add(if (abs(e) > 25) DiagItem(DiagGroups.MIX, 1, "Valvola EGR poco precisa (errore ${e.toInt()}%)",
                        "La valvola che ricircola i gas di scarico non segue bene il comando: spesso è sporca di carbonio.", "Pulizia EGR in officina.")
                    else DiagItem(DiagGroups.MIX, 0, "Valvola EGR regolare", "Segue il comando della centralina."))
                }
            }
            m("maf")?.let { f ->
                if (f < 0.5) add(DiagItem(DiagGroups.MIX, 2, "Debimetro non misura aria", "Il sensore del flusso d'aria segna quasi zero a motore acceso.", "Controlla connettore e sensore."))
                else values.put("maf_idle", f)
            }
        }

        // FAP (dati Renault)
        if (ext != null && ext.available) {
            step("Leggo lo stato del filtro antiparticolato", 0.95f)
            val f = HashMap<String, Double>()
            ext.readFap(f); ext.readSlow(f)
            f["soot_g"]?.let { g ->
                values.put("soot_g", g)
                add(when {
                    g >= 45 -> DiagItem(DiagGroups.FAP, 2, "FAP molto intasato (${f1(g)} g di fuliggine)",
                        "Oltre questa soglia la centralina può non riuscire più a pulirlo da sola.", "Rigenerazione forzata in officina, il prima possibile.")
                    g >= 32 -> DiagItem(DiagGroups.FAP, 1, "FAP carico (${f1(g)} g di fuliggine)",
                        "Sopra il livello a cui di solito parte la pulizia.", "Fai 20-30 minuti di extraurbano o autostrada a velocità costante.")
                    g >= 18 -> DiagItem(DiagGroups.FAP, 0, "FAP a metà (${f1(g)} g di fuliggine)", "La prossima pulizia automatica arriverà presto.")
                    else -> DiagItem(DiagGroups.FAP, 0, "FAP pulito (${f1(g)} g di fuliggine)", "")
                })
            }
            val kmR = f["km_regen"]; val fail = f["regen_fail"]; val cnt = f["regen_count"]
            add(DiagItem(DiagGroups.FAP, -1, "Storia delle pulizie",
                listOfNotNull(kmR?.let { "Ultima rigenerazione riuscita: ${it.toInt()} km fa" },
                    f["regen_last_min"]?.let { "durata ${it.toInt()} minuti" },
                    cnt?.let { "contatore rigenerazioni: ${it.toInt()}" },
                    f["km_dpf"]?.let { "km del filtro: ${it.toInt()}" }).joinToString(" · ").ifEmpty { "Non disponibile" }))
            fail?.let {
                if (it >= 3) add(DiagItem(DiagGroups.FAP, 1, "Rigenerazioni fallite: ${it.toInt()}",
                    "La centralina ha provato a pulire il filtro senza riuscirci: di solito per viaggi troppo brevi.",
                    "Fai un tratto lungo a velocità costante; se aumentano ancora, fai controllare."))
            }
            f["oil_dilution"]?.let { d ->
                if (d in 0.0..30.0) {
                    values.put("oil_dilution", d)
                    add(if (d >= 5) DiagItem(DiagGroups.FAP, 1, "Olio diluito dal gasolio (${f1(d)}%)",
                        "Le rigenerazioni interrotte lasciano gasolio nell'olio: l'olio lubrifica meno.", "Valuta di anticipare il cambio olio.")
                    else DiagItem(DiagGroups.FAP, 0, "Diluizione dell'olio contenuta (${f1(d)}%)", ""))
                }
            }
            f["odo_ecu"]?.let { values.put("odometer", it) }
        }

        // Riepilogo
        step("Preparo il report", 0.97f)
        val score = score(items)
        val verdict = verdict(items)
        val j = JSONObject().put("ts", ts).put("score", score).put("verdict", verdict).put("diesel", diesel).put("running", running)
            .put("values", values).put("items", JSONArray(items.map { it.toJson() }))
        val id = Db.get(ctx).saveDiag(ts, car, vin, j)
        DiagCtl.state.value = DiagState(running = false, progress = 1f, step = "Fatto", items = items.toList(), reportId = id)
        Live.log("Diagnosi completata: $verdict")
        return id
    }

    private fun readiness(b: IntArray) {
        val B = b[1]; val C = b[2]; val D = b[3]
        val names = if (diesel) mapOf(0 to "Catalizzatore", 1 to "Sistema NOx / AdBlue", 3 to "Pressione turbo", 5 to "Sensori gas di scarico",
            6 to "Filtro antiparticolato (FAP)", 7 to "EGR")
        else mapOf(0 to "Catalizzatore", 1 to "Catalizzatore riscaldato", 2 to "Sistema vapori benzina", 3 to "Aria secondaria",
            5 to "Sonde lambda", 6 to "Riscaldatori sonde lambda", 7 to "EGR")
        val base = mapOf(0 to "Mancate accensioni", 1 to "Sistema di alimentazione", 2 to "Componenti")
        val done = ArrayList<String>(); val notDone = ArrayList<String>()
        for ((bit, n) in base) if ((B shr bit) and 1 == 1) { if ((B shr (bit + 4)) and 1 == 0) done.add(n) else notDone.add(n) }
        for ((bit, n) in names) if ((C shr bit) and 1 == 1) { if ((D shr bit) and 1 == 0) done.add(n) else notDone.add(n) }
        values.put("ready_ok", done.size).put("ready_ko", notDone.size)
        if (notDone.isEmpty()) add(DiagItem(DiagGroups.READY, 0, "Tutti i controlli interni completati (${done.size})",
            "La centralina ha verificato da sola: ${done.joinToString(", ")}."))
        else add(DiagItem(DiagGroups.READY, if (notDone.size > 2) 1 else -1, "Controlli interni non ancora completati: ${notDone.size}",
            "Non verificati: ${notDone.joinToString(", ")}. Verificati: ${done.joinToString(", ").ifEmpty { "nessuno" }}. " +
                "È normale dopo aver staccato la batteria o cancellato i codici: si completano guidando qualche giorno.",
            "Alla revisione serve che siano quasi tutti completati."))
    }

    private fun f2(v: Double) = "%.1f".format(Locale.ITALY, v)
    private fun f1(v: Double) = "%.1f".format(Locale.ITALY, v)
    private fun sgn(v: Double) = (if (v > 0) "+" else "") + "%.0f".format(v)

    companion object {
        fun score(items: List<DiagItem>): Int =
            (100 - items.sumOf { when (it.sev) { 2 -> 30; 1 -> 8; else -> 0 }.toInt() }).coerceIn(0, 100)

        fun verdict(items: List<DiagItem>): String = when {
            items.any { it.sev == 2 } -> "Problemi da risolvere"
            items.any { it.sev == 1 } -> "Qualcosa da tenere d'occhio"
            else -> "Auto in buono stato"
        }

        /** Report in testo semplice da condividere (WhatsApp, email…). */
        fun toText(j: JSONObject, carName: String?): String {
            val sb = StringBuilder()
            val df = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.ITALY)
            sb.append("DIAGNOSI AUTO – AutoSalute\n")
            sb.append(df.format(Date(j.optLong("ts")))).append("\n")
            carName?.let { sb.append("Auto: ").append(it).append("\n") }
            j.optJSONObject("values")?.optString("vin")?.takeIf { it.isNotEmpty() }?.let { sb.append("Telaio: ").append(it).append("\n") }
            sb.append("\nRISULTATO: ").append(j.optString("verdict")).append(" (").append(j.optInt("score")).append("/100)\n")
            val items = j.optJSONArray("items") ?: JSONArray()
            val list = (0 until items.length()).map { DiagItem.from(items.getJSONObject(it)) }
            for (g in DiagGroups.ORDER) {
                val gi = list.filter { it.group == g }
                if (gi.isEmpty()) continue
                sb.append("\n").append(g.uppercase()).append("\n")
                for (i in gi) {
                    val tag = when (i.sev) { 2 -> "[PROBLEMA] "; 1 -> "[DA CONTROLLARE] "; 0 -> "[OK] "; else -> "• " }
                    sb.append(tag).append(i.title).append("\n")
                    if (i.detail.isNotBlank()) sb.append("   ").append(i.detail.replace("\n", "\n   ")).append("\n")
                    if (i.action.isNotBlank()) sb.append("   → ").append(i.action).append("\n")
                }
            }
            sb.append("\nNota: diagnosi tramite presa OBD2 sulla centralina motore. Non sostituisce il controllo di un meccanico.")
            return sb.toString()
        }
    }
}
