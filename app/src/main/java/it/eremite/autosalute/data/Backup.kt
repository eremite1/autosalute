package it.eremite.autosalute.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import it.eremite.autosalute.Live
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Backup su un file scelto dall'utente (di solito su Google Drive).
 * Dopo ogni viaggio e ogni modifica alla manutenzione il file viene riscritto,
 * e Google Drive lo sincronizza sul cloud del tuo account.
 */
object Backup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    val status = MutableStateFlow(0) // cambia quando lo stato va ridisegnato

    /** Impostazioni che viaggiano con il backup (non il lettore, che dipende dal telefono). */
    private val PREF_KEYS = listOf("odo_km", "odo_ts", "maint_plan", "autostart")

    private fun p(c: Context) = c.getSharedPreferences("autosalute", Context.MODE_PRIVATE)

    fun target(c: Context): Uri? = p(c).getString("backup_uri", null)?.let { Uri.parse(it) }
    fun lastOk(c: Context): Long = p(c).getLong("backup_ts", 0)
    fun lastError(c: Context): String? = p(c).getString("backup_err", null)

    fun setTarget(c: Context, uri: Uri) {
        try {
            c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        p(c).edit().putString("backup_uri", uri.toString()).remove("backup_err").apply()
        status.value++
    }

    fun clearTarget(c: Context) {
        target(c)?.let { u -> try { c.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {} }
        p(c).edit().remove("backup_uri").apply(); status.value++
    }

    private fun build(c: Context): String {
        val j = Db.get(c).exportJson()
        val pr = JSONObject()
        val all = p(c).all
        for (k in PREF_KEYS) when (val v = all[k]) {
            is Float -> pr.put(k, v.toDouble()); is Long -> pr.put(k, v); is Int -> pr.put(k, v)
            is Boolean -> pr.put(k, v); is String -> pr.put(k, v)
        }
        j.put("app", "AutoSalute").put("version", 1).put("created", System.currentTimeMillis()).put("prefs", pr)
        return j.toString()
    }

    /** Scrive subito il backup. Restituisce null se tutto ok, altrimenti il messaggio di errore. */
    suspend fun runNow(c: Context, uri: Uri? = target(c)): String? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext "nessun file di backup scelto"
        lock.withLock {
            try {
                val data = build(c).toByteArray()
                val os = c.contentResolver.openOutputStream(uri, "wt") ?: c.contentResolver.openOutputStream(uri, "w")
                    ?: throw Exception("file non scrivibile")
                os.use { it.write(data); it.flush() }
                p(c).edit().putLong("backup_ts", System.currentTimeMillis()).remove("backup_err").apply()
                status.value++
                null
            } catch (e: Exception) {
                val m = e.message ?: e.javaClass.simpleName
                p(c).edit().putString("backup_err", m).apply()
                status.value++
                Live.log("Backup non riuscito: $m")
                m
            }
        }
    }

    /** Backup in sottofondo, se è stato scelto un file. */
    fun auto(c: Context) {
        val app = c.applicationContext
        if (target(app) == null) return
        scope.launch { runNow(app) }
    }

    /** Ripristina dal file. Restituisce un messaggio per l'utente. */
    suspend fun restore(c: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val txt = c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext "Impossibile aprire il file"
            val j = JSONObject(txt)
            if (j.optString("app") != "AutoSalute") return@withContext "Questo file non è un backup di AutoSalute"
            Db.get(c).importJson(j)
            val pr = j.optJSONObject("prefs")
            if (pr != null) {
                val e = p(c).edit()
                pr.keys().forEach { k ->
                    when (k) {
                        "odo_km" -> e.putFloat(k, pr.optDouble(k).toFloat())
                        "odo_ts" -> e.putLong(k, pr.optLong(k))
                        "autostart" -> e.putBoolean(k, pr.optBoolean(k, true))
                        else -> e.putString(k, pr.optString(k))
                    }
                }
                e.apply()
            }
            setTarget(c, uri)
            Live.tripsVersion.value++
            val n = j.optJSONArray("trips")?.length() ?: 0
            val m = j.optJSONArray("maint")?.length() ?: 0
            "Ripristinati $n viaggi e $m interventi di manutenzione"
        } catch (e: Exception) {
            "Ripristino non riuscito: ${e.message}"
        }
    }
}
