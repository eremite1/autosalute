package it.eremite.autosalute.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.eremite.autosalute.health.Finding
import org.json.JSONArray
import org.json.JSONObject

class Sample(
    val ts: Long, val rpm: Double, val speed: Double, val load: Double, val coolant: Double,
    val intake: Double, val maf: Double, val boost: Double, val rail: Double, val fuel: Double,
    val volt: Double, val oil: Double
)

class Trip(
    val id: Long, val start: Long, val end: Long, val km: Double, val dur: Double,
    val avgSpeed: Double, val maxSpeed: Double, val fuelL: Double, val cons: Double, val score: Int,
    val metrics: Map<String, Double>, val findings: List<Finding>, val dtcs: List<String>
)

class MaintEntry(
    val id: Long, val ts: Long, val km: Double, val type: String, val parts: List<String>,
    val cost: Double, val place: String, val notes: String
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "autosalute.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER, ende INTEGER,
            km REAL, dur REAL, avg_speed REAL, max_speed REAL, fuel_l REAL, cons REAL, score INTEGER,
            metrics TEXT, findings TEXT, dtcs TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE samples(trip INTEGER, ts INTEGER, rpm REAL, speed REAL, load REAL, coolant REAL,
            intake REAL, maf REAL, boost REAL, rail REAL, fuel REAL, volt REAL, oil REAL)"""
        )
        db.execSQL("CREATE INDEX s_trip ON samples(trip)")
        createMaint(db)
    }

    private fun createMaint(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS maint(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, km REAL,
            type TEXT, parts TEXT, cost REAL, place TEXT, notes TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createMaint(db)
    }

    // ---------------------------------------------------------------- manutenzione
    fun saveMaint(m: MaintEntry): Long {
        val cv = ContentValues().apply {
            put("ts", m.ts); put("km", m.km); put("type", m.type)
            put("parts", JSONArray(m.parts).toString()); put("cost", m.cost)
            put("place", m.place); put("notes", m.notes)
        }
        return if (m.id > 0) { writableDatabase.update("maint", cv, "id=?", arrayOf(m.id.toString())); m.id }
        else writableDatabase.insert("maint", null, cv)
    }

    fun deleteMaint(id: Long) { writableDatabase.delete("maint", "id=?", arrayOf(id.toString())) }

    fun maint(): List<MaintEntry> {
        val out = ArrayList<MaintEntry>()
        readableDatabase.rawQuery("SELECT * FROM maint ORDER BY ts DESC, id DESC", null).use { c ->
            while (c.moveToNext()) {
                val pj = JSONArray(c.getString(c.getColumnIndexOrThrow("parts")) ?: "[]")
                out.add(
                    MaintEntry(
                        c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("ts")),
                        c.getDouble(c.getColumnIndexOrThrow("km")), c.getString(c.getColumnIndexOrThrow("type")) ?: "",
                        (0 until pj.length()).map { pj.getString(it) }, c.getDouble(c.getColumnIndexOrThrow("cost")),
                        c.getString(c.getColumnIndexOrThrow("place")) ?: "", c.getString(c.getColumnIndexOrThrow("notes")) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** Km percorsi (registrati dall'app) dopo un certo momento. */
    fun kmSince(ts: Long): Double =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(km),0) FROM trips WHERE ende IS NOT NULL AND start > ?", arrayOf(ts.toString())).use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }

    // ---------------------------------------------------------------- backup
    /** Esporta viaggi (riassunti) e manutenzione. I campioni secondo per secondo non sono inclusi. */
    fun exportJson(): JSONObject {
        val trips = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM trips WHERE ende IS NOT NULL", null).use { c ->
            while (c.moveToNext()) trips.put(rowJson(c))
        }
        val maint = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM maint", null).use { c -> while (c.moveToNext()) maint.put(rowJson(c)) }
        return JSONObject().put("trips", trips).put("maint", maint)
    }

    private fun rowJson(c: android.database.Cursor): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            val k = c.getColumnName(i)
            if (k == "id") continue
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(k, c.getLong(i))
                android.database.Cursor.FIELD_TYPE_FLOAT -> { val d = c.getDouble(i); if (!d.isNaN() && !d.isInfinite()) o.put(k, d) }
                android.database.Cursor.FIELD_TYPE_STRING -> o.put(k, c.getString(i))
                else -> {}
            }
        }
        return o
    }

    /** Ripristina: sostituisce viaggi e manutenzione con quelli del backup. */
    fun importJson(j: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("samples", null, null); db.delete("trips", null, null); db.delete("maint", null, null)
            fun ins(table: String, arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues()
                    o.keys().forEach { k ->
                        when (val v = o.get(k)) {
                            is Int -> cv.put(k, v.toLong()); is Long -> cv.put(k, v)
                            is Double -> cv.put(k, v); is Number -> cv.put(k, v.toDouble())
                            is String -> cv.put(k, v)
                        }
                    }
                    db.insert(table, null, cv)
                }
            }
            ins("trips", j.optJSONArray("trips")); ins("maint", j.optJSONArray("maint"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun startTrip(t: Long): Long = writableDatabase.insert("trips", null, ContentValues().apply { put("start", t) })

    fun addSamples(trip: Long, list: List<Sample>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in list) db.insert("samples", null, ContentValues().apply {
                put("trip", trip); put("ts", s.ts)
                pn("rpm", s.rpm); pn("speed", s.speed); pn("load", s.load); pn("coolant", s.coolant)
                pn("intake", s.intake); pn("maf", s.maf); pn("boost", s.boost); pn("rail", s.rail)
                pn("fuel", s.fuel); pn("volt", s.volt); pn("oil", s.oil)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun ContentValues.pn(k: String, v: Double) { if (v.isNaN()) putNull(k) else put(k, v) }

    fun finishTrip(t: Trip) {
        writableDatabase.update("trips", ContentValues().apply {
            put("ende", t.end); put("km", t.km); put("dur", t.dur); put("avg_speed", t.avgSpeed)
            put("max_speed", t.maxSpeed); put("fuel_l", t.fuelL); put("cons", t.cons); put("score", t.score)
            put("metrics", JSONObject(t.metrics.filterValues { !it.isNaN() && !it.isInfinite() }).toString())
            put("findings", JSONArray(t.findings.map { it.toJson() }).toString())
            put("dtcs", JSONArray(t.dtcs).toString())
        }, "id=?", arrayOf(t.id.toString()))
    }

    fun deleteTrip(id: Long) {
        writableDatabase.delete("samples", "trip=?", arrayOf(id.toString()))
        writableDatabase.delete("trips", "id=?", arrayOf(id.toString()))
    }

    fun trips(limit: Int = 300): List<Trip> {
        val out = ArrayList<Trip>()
        readableDatabase.rawQuery("SELECT * FROM trips WHERE ende IS NOT NULL ORDER BY start DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                val m = HashMap<String, Double>()
                val mj = JSONObject(c.getString(c.getColumnIndexOrThrow("metrics")) ?: "{}")
                mj.keys().forEach { k -> m[k] = mj.optDouble(k) }
                val fj = JSONArray(c.getString(c.getColumnIndexOrThrow("findings")) ?: "[]")
                val dj = JSONArray(c.getString(c.getColumnIndexOrThrow("dtcs")) ?: "[]")
                out.add(
                    Trip(
                        c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("start")),
                        c.getLong(c.getColumnIndexOrThrow("ende")), c.getDouble(c.getColumnIndexOrThrow("km")),
                        c.getDouble(c.getColumnIndexOrThrow("dur")), c.getDouble(c.getColumnIndexOrThrow("avg_speed")),
                        c.getDouble(c.getColumnIndexOrThrow("max_speed")), c.getDouble(c.getColumnIndexOrThrow("fuel_l")),
                        c.getDouble(c.getColumnIndexOrThrow("cons")), c.getInt(c.getColumnIndexOrThrow("score")),
                        m, (0 until fj.length()).map { Finding.fromJson(fj.getJSONObject(it)) },
                        (0 until dj.length()).map { dj.getString(it) }
                    )
                )
            }
        }
        return out
    }

    fun samples(trip: Long): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery("SELECT * FROM samples WHERE trip=? ORDER BY ts", arrayOf(trip.toString())).use { c ->
            fun d(k: String): Double { val i = c.getColumnIndexOrThrow(k); return if (c.isNull(i)) Double.NaN else c.getDouble(i) }
            while (c.moveToNext()) out.add(
                Sample(c.getLong(c.getColumnIndexOrThrow("ts")), d("rpm"), d("speed"), d("load"), d("coolant"),
                    d("intake"), d("maf"), d("boost"), d("rail"), d("fuel"), d("volt"), d("oil"))
            )
        }
        return out
    }

    /** Tiene i campioni dettagliati degli ultimi 120 giorni (i riassunti restano per sempre). */
    fun prune() {
        val lim = System.currentTimeMillis() - 120L * 24 * 3600 * 1000
        writableDatabase.delete("samples", "ts < ?", arrayOf(lim.toString()))
    }

    fun clear() {
        writableDatabase.delete("samples", null, null)
        writableDatabase.delete("trips", null, null)
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
    }
}
