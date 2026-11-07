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

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "autosalute.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

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
