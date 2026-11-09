package it.eremite.autosalute.data

import android.content.Context
import it.eremite.autosalute.App
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Una voce di manutenzione con intervallo consigliato (valori indicativi Dacia 1.5 dCi, modificabili). */
class Part(val key: String, val label: String, val km: Int?, val months: Int?)

/** Tipo di intervento con le voci preselezionate. */
class MaintType(val label: String, val preset: List<String>)

data class Due(
    val part: Part, val km: Int?, val months: Int?,
    val lastTs: Long?, val lastKm: Double?,
    val kmLeft: Double?, val daysLeft: Long?, val status: Int, // 0 ok, 1 presto, 2 scaduto, -1 mai registrato
)

object Maint {
    val PARTS = listOf(
        Part("olio", "Olio motore", 20000, 12),
        Part("filtro_olio", "Filtro olio", 20000, 12),
        Part("filtro_aria", "Filtro aria", 60000, 48),
        Part("filtro_gasolio", "Filtro gasolio", 40000, 24),
        Part("filtro_abitacolo", "Filtro abitacolo (antipolline)", 20000, 12),
        Part("distribuzione", "Cinghia distribuzione + pompa acqua", 120000, 60),
        Part("cinghia_servizi", "Cinghia servizi", 120000, 60),
        Part("liquido_freni", "Liquido freni", null, 24),
        Part("liquido_raff", "Liquido raffreddamento", 120000, 48),
        Part("pastiglie_ant", "Pastiglie freni anteriori", 40000, null),
        Part("dischi_ant", "Dischi freni anteriori", 80000, null),
        Part("freni_post", "Freni posteriori (ganasce/tamburi)", 80000, null),
        Part("pneumatici", "Pneumatici nuovi", 40000, 60),
        Part("convergenza", "Convergenza / equilibratura", 20000, 24),
        Part("batteria", "Batteria", null, 60),
        Part("candelette", "Candelette", 100000, null),
        Part("spazzole", "Spazzole tergicristallo", null, 12),
        Part("clima", "Ricarica / igienizzazione clima", null, 24),
        Part("revisione", "Revisione ministeriale", null, 24),
        Part("gomme_invernali", "Montate gomme invernali", null, null),
        Part("gomme_estive", "Montate gomme estive", null, null),
        Part("altro", "Altro", null, null),
    )
    private val byKey = PARTS.associateBy { it.key }
    fun part(k: String) = byKey[k]

    val TYPES = listOf(
        MaintType("Tagliando completo", listOf("olio", "filtro_olio", "filtro_aria", "filtro_gasolio", "filtro_abitacolo")),
        MaintType("Tagliando parziale", listOf("olio", "filtro_olio")),
        MaintType("Cambio gomme stagionale", listOf("gomme_invernali")),
        MaintType("Pneumatici", listOf("pneumatici")),
        MaintType("Freni", listOf("pastiglie_ant")),
        MaintType("Distribuzione", listOf("distribuzione", "cinghia_servizi")),
        MaintType("Batteria", listOf("batteria")),
        MaintType("Revisione", listOf("revisione")),
        MaintType("Riparazione", emptyList()),
        MaintType("Altro", emptyList()),
    )

    private fun p(c: Context) = c.getSharedPreferences("autosalute", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ contachilometri
    private fun kKm(car: Long) = if (car == 1L) "odo_km" else "odo_km_$car"
    private fun kTs(car: Long) = if (car == 1L) "odo_ts" else "odo_ts_$car"

    fun odoTs(c: Context, car: Long = Cars.activeId(c)): Long = p(c).getLong(kTs(car), 0)

    fun setOdometer(c: Context, km: Double, ts: Long = System.currentTimeMillis(), car: Long = Cars.activeId(c)) {
        p(c).edit().putFloat(kKm(car), km.toFloat()).putLong(kTs(car), ts).apply()
    }

    /** Km attuali: ultimo valore inserito + km registrati dall'app da allora. Null se mai inserito. */
    fun odometer(c: Context, car: Long = Cars.activeId(c)): Double? {
        val pr = p(c)
        if (!pr.contains(kKm(car))) return null
        return pr.getFloat(kKm(car), 0f) + Db.get(c).kmSince(pr.getLong(kTs(car), 0), car)
    }

    // ------------------------------------------------------------ intervalli personalizzati
    private fun plan(c: Context): JSONObject = try { JSONObject(p(c).getString("maint_plan", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun interval(c: Context, part: Part): Pair<Int?, Int?> {
        val o = plan(c).optJSONObject(part.key) ?: return part.km to part.months
        val km = if (o.has("km")) o.optInt("km").takeIf { it > 0 } else part.km
        val mo = if (o.has("m")) o.optInt("m").takeIf { it > 0 } else part.months
        return km to mo
    }

    fun setInterval(c: Context, part: Part, km: Int?, months: Int?) {
        val j = plan(c)
        j.put(part.key, JSONObject().put("km", km ?: 0).put("m", months ?: 0))
        p(c).edit().putString("maint_plan", j.toString()).apply()
    }

    // ------------------------------------------------------------ scadenze
    fun dues(c: Context, car: Long = Cars.activeId(c), entries: List<MaintEntry> = Db.get(c).maint(car)): List<Due> {
        val odo = odometer(c, car)
        val now = System.currentTimeMillis()
        val out = ArrayList<Due>()
        for (part in PARTS) {
            val (ikm, imo) = interval(c, part)
            if (ikm == null && imo == null) continue
            val last = entries.firstOrNull { part.key in it.parts }
            if (last == null) { out.add(Due(part, ikm, imo, null, null, null, null, -1)); continue }
            val kmLeft = if (ikm != null && odo != null && last.km > 0) last.km + ikm - odo else null
            val daysLeft = imo?.let {
                val due = Calendar.getInstance().apply { timeInMillis = last.ts; add(Calendar.MONTH, it) }.timeInMillis
                (due - now) / (24L * 3600 * 1000)
            }
            val status = when {
                (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> 2
                (kmLeft != null && kmLeft < 1500) || (daysLeft != null && daysLeft < 30) -> 1
                else -> 0
            }
            out.add(Due(part, ikm, imo, last.ts, last.km.takeIf { it > 0 }, kmLeft, daysLeft, status))
        }
        seasonal(entries)?.let { out.add(it) }
        return out.sortedWith(compareByDescending<Due> { it.status }.thenBy { it.daysLeft ?: Long.MAX_VALUE })
    }

    /** Gomme invernali: obbligo 15 novembre - 15 aprile, le invernali vanno tolte entro il 15 maggio. */
    private fun seasonal(entries: List<MaintEntry>): Due? {
        val last = entries.firstOrNull { "gomme_invernali" in it.parts || "gomme_estive" in it.parts } ?: return null
        val winterOn = "gomme_invernali" in last.parts
        val cal = Calendar.getInstance(Locale.ITALY)
        val y = cal.get(Calendar.YEAR)
        fun d(m: Int, day: Int, yy: Int = y) = Calendar.getInstance().apply { set(yy, m, day, 0, 0, 0) }.timeInMillis
        val now = System.currentTimeMillis()
        val (target, label) = if (winterOn) {
            val t = if (now < d(Calendar.MAY, 16)) d(Calendar.MAY, 15) else d(Calendar.MAY, 15, y + 1)
            t to "Rimonta le gomme estive (entro il 15 maggio)"
        } else {
            val t = if (now < d(Calendar.NOVEMBER, 16)) d(Calendar.NOVEMBER, 15) else d(Calendar.NOVEMBER, 15, y + 1)
            t to "Monta le gomme invernali (obbligo dal 15 novembre)"
        }
        val days = (target - now) / (24L * 3600 * 1000)
        val status = when { days <= 0 -> 2; days <= 31 -> 1; else -> 0 }
        return Due(Part("stagionali", label, null, null), null, null, last.ts, last.km, null, days, status)
    }

    /** Chiave usata per non ripetere la stessa notifica. */
    fun noticeKey(d: Due) = "${d.part.key}_${d.lastTs}_${d.status}_${d.lastKm}"
}

/** Avvisa con una notifica quando una manutenzione si avvicina o è scaduta (una volta sola per stato). */
object MaintNotify {
    fun check(c: Context, car: Long = Cars.activeId(c)) {
        val p = c.getSharedPreferences("autosalute", Context.MODE_PRIVATE)
        val sent = p.getStringSet("maint_sent", emptySet())!!.toMutableSet()
        val due = Maint.dues(c, car).filter { it.status >= 1 }
        var id = 200
        for (d in due) {
            val k = Maint.noticeKey(d)
            if (k in sent) continue
            sent.add(k)
            val what = if (d.status == 2) "Scaduto" else "In scadenza"
            val detail = listOfNotNull(
                d.kmLeft?.let { if (it <= 0) "superato di ${(-it).toInt()} km" else "mancano ${it.toInt()} km" },
                d.daysLeft?.let { if (it <= 0) "scaduto da ${-it} giorni" else "mancano $it giorni" },
            ).joinToString(" · ")
            App.notify(c, App.CH_TRIP, id++, "$what: ${d.part.label}", detail.ifEmpty { "Apri la sezione Manutenzione" })
        }
        p.edit().putStringSet("maint_sent", sent).apply()
    }
}
