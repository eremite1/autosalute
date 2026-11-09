package it.eremite.autosalute.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.eremite.autosalute.health.Finding
import kotlinx.coroutines.flow.MutableStateFlow
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

class Car(val id: Long, val name: String, val vin: String?)

class GeoPoint(val ts: Long, val lat: Double, val lon: Double, val speed: Double)

class DiagReport(val id: Long, val ts: Long, val car: Long, val vin: String?, val json: JSONObject)

/** Auto selezionata: tutti i dati (viaggi, statistiche, manutenzione) sono separati per auto. */
object Cars {
    val active = MutableStateFlow(0L)

    fun activeId(c: Context): Long {
        if (active.value == 0L) active.value = c.getSharedPreferences("autosalute", Context.MODE_PRIVATE).getLong("active_car", 1L)
        return active.value
    }

    fun select(c: Context, id: Long) {
        c.getSharedPreferences("autosalute", Context.MODE_PRIVATE).edit().putLong("active_car", id).apply()
        active.value = id
        it.eremite.autosalute.Live.tripsVersion.value++
    }
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "autosalute.db", null, 3) {

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
        migrate3(db)
    }

    private fun createMaint(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS maint(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, km REAL,
            type TEXT, parts TEXT, cost REAL, place TEXT, notes TEXT)"""
        )
    }

    private fun migrate3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cars(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, vin TEXT, created INTEGER)")
        db.execSQL("INSERT OR IGNORE INTO cars(id, name, vin, created) VALUES(1, 'Dacia Sandero Stepway', NULL, ${System.currentTimeMillis()})")
        db.execSQL("ALTER TABLE trips ADD COLUMN car INTEGER DEFAULT 1")
        db.execSQL("ALTER TABLE maint ADD COLUMN car INTEGER DEFAULT 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS points(trip INTEGER, ts INTEGER, lat REAL, lon REAL, spd REAL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS p_trip ON points(trip)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diag(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, car INTEGER, vin TEXT, json TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createMaint(db)
        if (o < 3) migrate3(db)
    }

    // ---------------------------------------------------------------- auto
    fun cars(): List<Car> {
        val out = ArrayList<Car>()
        readableDatabase.rawQuery("SELECT id, name, vin FROM cars ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(Car(c.getLong(0), c.getString(1) ?: "Auto", if (c.isNull(2)) null else c.getString(2)))
        }
        return out
    }

    fun car(id: Long): Car? = cars().firstOrNull { it.id == id }
    fun carByVin(vin: String): Car? = cars().firstOrNull { it.vin == vin }

    fun addCar(name: String, vin: String?): Long = writableDatabase.insert("cars", null, ContentValues().apply {
        put("name", name); if (vin != null) put("vin", vin) else putNull("vin"); put("created", System.currentTimeMillis())
    })

    fun renameCar(id: Long, name: String) { writableDatabase.update("cars", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString())) }
    fun setCarVin(id: Long, vin: String?) { writableDatabase.update("cars", ContentValues().apply { if (vin == null) putNull("vin") else put("vin", vin) }, "id=?", arrayOf(id.toString())) }

    /** Elimina l'auto con tutti i suoi dati. */
    fun deleteCar(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE trip IN (SELECT id FROM trips WHERE car=?)", arrayOf(id))
            db.execSQL("DELETE FROM points WHERE trip IN (SELECT id FROM trips WHERE car=?)", arrayOf(id))
            db.delete("trips", "car=?", arrayOf(id.toString()))
            db.delete("maint", "car=?", arrayOf(id.toString()))
            db.delete("diag", "car=?", arrayOf(id.toString()))
            db.delete("cars", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------------------------------------------------------------- manutenzione
    fun saveMaint(m: MaintEntry, car: Long): Long {
        val cv = ContentValues().apply {
            put("ts", m.ts); put("km", m.km); put("type", m.type)
            put("parts", JSONArray(m.parts).toString()); put("cost", m.cost)
            put("place", m.place); put("notes", m.notes); put("car", car)
        }
        return if (m.id > 0) { writableDatabase.update("maint", cv, "id=?", arrayOf(m.id.toString())); m.id }
        else writableDatabase.insert("maint", null, cv)
    }

    fun deleteMaint(id: Long) { writableDatabase.delete("maint", "id=?", arrayOf(id.toString())) }

    fun maint(car: Long): List<MaintEntry> {
        val out = ArrayList<MaintEntry>()
        readableDatabase.rawQuery("SELECT * FROM maint WHERE car=? ORDER BY ts DESC, id DESC", arrayOf(car.toString())).use { c ->
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

    /** Km percorsi (registrati dall'app) con questa auto dopo un certo momento. */
    fun kmSince(ts: Long, car: Long): Double =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(km),0) FROM trips WHERE ende IS NOT NULL AND start > ? AND car=?",
            arrayOf(ts.toString(), car.toString())).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    // ---------------------------------------------------------------- diagnosi
    fun saveDiag(ts: Long, car: Long, vin: String?, j: JSONObject): Long = writableDatabase.insert("diag", null, ContentValues().apply {
        put("ts", ts); put("car", car); if (vin != null) put("vin", vin); put("json", j.toString())
    })

    fun diags(car: Long? = null): List<DiagReport> {
        val out = ArrayList<DiagReport>()
        val q = if (car == null) "SELECT id, ts, car, vin, json FROM diag ORDER BY ts DESC LIMIT 50"
        else "SELECT id, ts, car, vin, json FROM diag WHERE car=$car ORDER BY ts DESC LIMIT 50"
        readableDatabase.rawQuery(q, null).use { c ->
            while (c.moveToNext()) out.add(DiagReport(c.getLong(0), c.getLong(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3),
                try { JSONObject(c.getString(4)) } catch (_: Exception) { JSONObject() }))
        }
        return out
    }

    fun deleteDiag(id: Long) { writableDatabase.delete("diag", "id=?", arrayOf(id.toString())) }

    // ---------------------------------------------------------------- GPS
    fun addPoints(trip: Long, list: List<GeoPoint>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in list) db.insert("points", null, ContentValues().apply {
                put("trip", trip); put("ts", p.ts); put("lat", p.lat); put("lon", p.lon); put("spd", p.speed)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun points(trip: Long): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        readableDatabase.rawQuery("SELECT ts, lat, lon, spd FROM points WHERE trip=? ORDER BY ts", arrayOf(trip.toString())).use { c ->
            while (c.moveToNext()) out.add(GeoPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)))
        }
        return out
    }

    /** Ultima posizione registrata con questa auto (dove l'hai parcheggiata). */
    fun lastPoint(car: Long): GeoPoint? =
        readableDatabase.rawQuery(
            "SELECT p.ts, p.lat, p.lon, p.spd FROM points p JOIN trips t ON p.trip=t.id WHERE t.car=? ORDER BY p.ts DESC LIMIT 1",
            arrayOf(car.toString())
        ).use { c -> if (c.moveToFirst()) GeoPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)) else null }

    // ---------------------------------------------------------------- backup
    /** Esporta auto, viaggi (riassunti), manutenzione e diagnosi. Campioni e punti GPS non inclusi. */
    fun exportJson(): JSONObject {
        fun all(q: String, keepId: Boolean): JSONArray {
            val a = JSONArray()
            readableDatabase.rawQuery(q, null).use { c -> while (c.moveToNext()) a.put(rowJson(c, keepId)) }
            return a
        }
        return JSONObject()
            .put("cars", all("SELECT * FROM cars", true))
            .put("trips", all("SELECT * FROM trips WHERE ende IS NOT NULL", false))
            .put("maint", all("SELECT * FROM maint", false))
            .put("diag", all("SELECT * FROM diag", false))
    }

    private fun rowJson(c: android.database.Cursor, keepId: Boolean): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            val k = c.getColumnName(i)
            if (k == "id" && !keepId) continue
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(k, c.getLong(i))
                android.database.Cursor.FIELD_TYPE_FLOAT -> { val d = c.getDouble(i); if (!d.isNaN() && !d.isInfinite()) o.put(k, d) }
                android.database.Cursor.FIELD_TYPE_STRING -> o.put(k, c.getString(i))
                else -> {}
            }
        }
        return o
    }

    /** Ripristina: sostituisce tutti i dati con quelli del backup. */
    fun importJson(j: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("samples", "points", "trips", "maint", "diag")) db.delete(t, null, null)
            if (j.has("cars")) db.delete("cars", null, null)
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
            ins("cars", j.optJSONArray("cars")); ins("trips", j.optJSONArray("trips"))
            ins("maint", j.optJSONArray("maint")); ins("diag", j.optJSONArray("diag"))
            db.execSQL("INSERT OR IGNORE INTO cars(id, name, vin, created) VALUES(1, 'Dacia Sandero Stepway', NULL, ${System.currentTimeMillis()})")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------------------------------------------------------------- viaggi
    fun startTrip(t: Long, car: Long): Long = writableDatabase.insert("trips", null, ContentValues().apply { put("start", t); put("car", car) })

    fun setTripCar(trip: Long, car: Long) {
        writableDatabase.update("trips", ContentValues().apply { put("car", car) }, "id=?", arrayOf(trip.toString()))
    }

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
            put("max_speed", t.maxSpeed); pn("fuel_l", t.fuelL); pn("cons", t.cons); put("score", t.score)
            put("metrics", JSONObject(t.metrics.filterValues { !it.isNaN() && !it.isInfinite() }).toString())
            put("findings", JSONArray(t.findings.map { it.toJson() }).toString())
            put("dtcs", JSONArray(t.dtcs).toString())
        }, "id=?", arrayOf(t.id.toString()))
    }

    fun deleteTrip(id: Long) {
        writableDatabase.delete("samples", "trip=?", arrayOf(id.toString()))
        writableDatabase.delete("points", "trip=?", arrayOf(id.toString()))
        writableDatabase.delete("trips", "id=?", arrayOf(id.toString()))
    }

    fun trips(car: Long, limit: Int = 5000): List<Trip> {
        val out = ArrayList<Trip>()
        readableDatabase.rawQuery("SELECT * FROM trips WHERE ende IS NOT NULL AND car=? ORDER BY start DESC LIMIT $limit",
            arrayOf(car.toString())).use { c ->
            fun d(k: String): Double { val i = c.getColumnIndexOrThrow(k); return if (c.isNull(i)) Double.NaN else c.getDouble(i) }
            while (c.moveToNext()) {
                val m = HashMap<String, Double>()
                val mj = JSONObject(c.getString(c.getColumnIndexOrThrow("metrics")) ?: "{}")
                mj.keys().forEach { k -> m[k] = mj.optDouble(k) }
                val fj = JSONArray(c.getString(c.getColumnIndexOrThrow("findings")) ?: "[]")
                val dj = JSONArray(c.getString(c.getColumnIndexOrThrow("dtcs")) ?: "[]")
                out.add(
                    Trip(
                        c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("start")),
                        c.getLong(c.getColumnIndexOrThrow("ende")), d("km"), d("dur"), d("avg_speed"), d("max_speed"),
                        d("fuel_l"), d("cons"), c.getInt(c.getColumnIndexOrThrow("score")),
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

    /** Campioni dettagliati: ultimi 120 giorni. Percorsi GPS: ultimi 365 giorni. I riassunti restano per sempre. */
    fun prune() {
        val day = 24L * 3600 * 1000
        val now = System.currentTimeMillis()
        writableDatabase.delete("samples", "ts < ?", arrayOf((now - 120 * day).toString()))
        writableDatabase.delete("points", "ts < ?", arrayOf((now - 365 * day).toString()))
    }

    fun clear(car: Long) {
        writableDatabase.execSQL("DELETE FROM samples WHERE trip IN (SELECT id FROM trips WHERE car=?)", arrayOf(car))
        writableDatabase.execSQL("DELETE FROM points WHERE trip IN (SELECT id FROM trips WHERE car=?)", arrayOf(car))
        writableDatabase.delete("trips", "car=?", arrayOf(car.toString()))
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
    }
}
