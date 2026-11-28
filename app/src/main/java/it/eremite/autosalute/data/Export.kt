package it.eremite.autosalute.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import it.eremite.autosalute.health.Health
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Crea un file di testo con tutti i dati dell'auto selezionata, da condividere (per esempio con Claude). */
object Export {
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY)
    private fun f(v: Double?) = if (v == null || v.isNaN()) "-" else Health.fmt(v)

    suspend fun build(ctx: Context): File = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val car = Cars.activeId(ctx)
        val c = db.car(car)
        val trips = db.trips(car).sortedBy { it.start }
        val sb = StringBuilder()
        sb.append("AUTOSALUTE – ESPORTAZIONE DATI\n")
        sb.append("Auto: ${c?.name} · telaio ${c?.vin ?: "-"} · esportato ${df.format(Date())}\n")
        sb.append("Contachilometri stimato: ${f(Maint.odometer(ctx, car))} km\n")
        sb.append("Viaggi: ${trips.size} · km totali ${f(trips.sumOf { it.km })}\n\n")

        sb.append("== VIAGGI ==\n")
        sb.append("data;km;min;vel_media;L;L100;punteggio;eco;liquido_marcia;liquido_max;olio_max;riscaldamento_s;batt_riposo;batt_avviamento;ricarica;ricarica_min;ricarica_max;minimo_giri;minimo_instab;carico_minimo;turbo_2250;gasolio_2250;aria_2250;aria_minimo;fuliggine_ini;fuliggine_fine;km_da_rigen;diluiz_olio;app_mAh;segnalazioni\n")
        for (t in trips) {
            val m = t.metrics
            sb.append(listOf(
                df.format(Date(t.start)), f(t.km), f(t.dur / 60), f(t.avgSpeed), f(t.fuelL), f(t.cons), t.score.toString(), f(m["eco_score"]),
                f(m["coolant_cruise"]), f(m["coolant_max"]), f(m["oil_max"]), f(m["warmup_s"]), f(m["volt_rest"]), f(m["crank_min"]),
                f(m["volt_run"]), f(m["volt_run_min"]), f(m["volt_run_max"]), f(m["idle_rpm"]), f(m["idle_rpm_sd"]), f(m["idle_load"]),
                f(m["boost_2250"]), f(m["rail_2250"]), f(m["maf_2250"]), f(m["maf_idle"]), f(m["soot_start"]), f(m["soot_end"]),
                f(m["km_regen_end"]), f(m["oil_dilution"]), f(m["app_mah"]),
                t.findings.filter { it.sev >= 1 }.joinToString(" | ") { it.title }
            ).joinToString(";")).append("\n")
        }

        sb.append("\n== RIGENERAZIONI FAP ==\n")
        db.regens(car).sortedBy { it.start }.forEach { e ->
            sb.append("${df.format(Date(e.start))} · ${e.type}${if (e.interrupted) " (interrotta)" else ""} · ${f(e.durS / 60)} min · " +
                "fuliggine ${f(e.soot0)}→${f(e.soot1)} g · max ${f(e.maxTemp)}°C · dopo ${f(e.kmStart)} km · ${e.method}\n")
        }

        sb.append("\n== DIAGNOSI ==\n")
        db.diags(car).forEach { d ->
            sb.append("${df.format(Date(d.ts))} · ${d.json.optString("verdict")} (${d.json.optInt("score")}/100)\n")
            val arr = d.json.optJSONArray("items")
            if (arr != null) for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optInt("s") >= 1) sb.append("   - ${item.optString("t")}: ${item.optString("d")}\n")
            }
        }

        sb.append("\n== MANUTENZIONE ==\n")
        db.maint(car).forEach { m ->
            sb.append("${df.format(Date(m.ts))} · ${m.type} · ${f(m.km)} km · ${m.parts.joinToString(",")} · ${f(m.cost)} € · ${m.notes}\n")
        }

        sb.append("\n== REGISTRO TECNICO (ultime righe) ==\n")
        it.eremite.autosalute.Live.logLines.value.take(60).forEach { line -> sb.append(line).append("\n") }

        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "AutoSalute-dati-" + SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(Date()) + ".txt")
        file.writeText(sb.toString())
        file
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Dati AutoSalute").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Condividi i dati dell'auto"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
