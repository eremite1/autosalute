package it.eremite.autosalute

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Controlla su GitHub se c'è una versione nuova, la scarica e la installa. */
object Updater {
    private const val API = "https://api.github.com/repos/eremite1/autosalute/releases/latest"

    data class Info(val code: Int, val name: String, val url: String, val notes: String)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val info: Info) : State()
        data class Downloading(val info: Info, val pct: Int) : State()
        data class Ready(val info: Info, val file: File) : State()
        data class Error(val msg: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun currentCode(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autosalute", Context.MODE_PRIVATE)

    /** Controlla al massimo ogni [minGapMs]; quiet = non mostra "aggiornata" né errori. */
    suspend fun check(ctx: Context, quiet: Boolean = false, minGapMs: Long = 0): Info? {
        val s = state.value
        if (s is State.Downloading || s is State.Ready) return null
        val now = System.currentTimeMillis()
        if (minGapMs > 0 && now - prefs(ctx).getLong("lastCheck", 0) < minGapMs) {
            return (state.value as? State.Available)?.info
        }
        if (!quiet) state.value = State.Checking
        return try {
            val info = withContext(Dispatchers.IO) { fetch() }
            prefs(ctx).edit().putLong("lastCheck", now).apply()
            if (info != null && info.code > currentCode(ctx)) {
                state.value = State.Available(info); info
            } else {
                if (!quiet || state.value is State.Checking) state.value = State.UpToDate
                null
            }
        } catch (e: Exception) {
            if (!quiet) state.value = State.Error("Impossibile controllare: ${e.message ?: "nessuna connessione"}")
            null
        }
    }

    private fun fetch(): Info? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AutoSalute")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            if (c.responseCode == 404) throw Exception("pagina versioni non raggiungibile (repository privato?)")
            if (c.responseCode != 200) throw Exception("errore ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name").removePrefix("v")
            val code = tag.toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            return Info(code, j.optString("name", "Versione $code"), url ?: return null, j.optString("body").trim())
        } finally { c.disconnect() }
    }

    suspend fun download(ctx: Context, info: Info) {
        state.value = State.Downloading(info, 0)
        try {
            val dir = File(ctx.cacheDir, "aggiornamenti").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "AutoSalute-${info.code}.apk")
            withContext(Dispatchers.IO) {
                var u = URL(info.url)
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", "AutoSalute")
                    c.connectTimeout = 15_000; c.readTimeout = 30_000
                    val rc = c.responseCode
                    if (rc in 300..399 && hops < 5) {
                        u = URL(u, c.getHeaderField("Location")); c.disconnect(); hops++; continue
                    }
                    if (rc != 200) throw Exception("errore download $rc")
                    break
                }
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var last = -1
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            os.write(buf, 0, n); done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; state.value = State.Downloading(info, p) }
                            }
                        }
                    }
                }
                c.disconnect()
            }
            state.value = State.Ready(info, out)
            install(ctx)
        } catch (e: Exception) {
            state.value = State.Error("Download non riuscito: ${e.message}")
        }
    }

    /** Apre l'installatore di Android. Se serve, chiede prima il permesso "installa app sconosciute". */
    fun install(ctx: Context) {
        val r = state.value as? State.Ready ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", r.file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()
}
