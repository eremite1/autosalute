package it.eremite.autosalute.obd

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import it.eremite.autosalute.Live
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Canale di comunicazione con il lettore ELM327 (Vgate). */
interface ObdLink {
    suspend fun connect()
    suspend fun send(cmd: String, timeoutMs: Long): String
    fun close()
}

private fun adapter(ctx: Context): BluetoothAdapter =
    (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ?: throw IOException("Bluetooth non disponibile")

/** Bluetooth classico (SPP). */
@SuppressLint("MissingPermission")
class ClassicLink(private val ctx: Context, private val mac: String) : ObdLink {
    private var sock: BluetoothSocket? = null
    private var ins: InputStream? = null
    private var outs: OutputStream? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val ad = adapter(ctx)
        if (!ad.isEnabled) throw IOException("Bluetooth spento")
        val dev = ad.getRemoteDevice(mac)
        try { ad.cancelDiscovery() } catch (_: Exception) {}
        val s = try {
            dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: IOException) {
            dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
        sock = s; ins = s.inputStream; outs = s.outputStream
    }

    override suspend fun send(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val i = ins ?: throw IOException("non collegato")
        val o = outs ?: throw IOException("non collegato")
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray()); o.flush()
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val t0 = System.currentTimeMillis()
        while (true) {
            if (i.available() > 0) {
                val n = i.read(buf)
                if (n < 0) throw IOException("canale chiuso")
                sb.append(String(buf, 0, n))
                if (sb.indexOf(">") >= 0) break
            } else {
                if (System.currentTimeMillis() - t0 > timeoutMs) {
                    if (sb.isEmpty()) throw IOException("nessuna risposta")
                    break
                }
                delay(5)
            }
        }
        sb.toString().replace(">", "")
    }

    override fun close() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** Bluetooth a basso consumo (BLE), usato dal Vgate iCar Pro 4.0. */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val mac: String) : ObdLink {
    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Unit>()
    private val sb = StringBuilder()
    @Volatile private var prompt: CompletableDeferred<Unit>? = null
    @Volatile private var open = false

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                open = true
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                open = false
                if (!ready.isCompleted) ready.completeExceptionally(IOException("connessione BLE rifiutata ($status)"))
                prompt?.completeExceptionally(IOException("BLE scollegato"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pair = pick(g)
            if (pair == null) {
                ready.completeExceptionally(IOException("servizio OBD non trovato sul dispositivo"))
                return
            }
            val (notify, write) = pair
            writeCh = write
            g.setCharacteristicNotification(notify, true)
            val d = notify.getDescriptor(CCCD)
            if (d != null) {
                val v = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(d, v)
            } else ready.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (!ready.isCompleted) ready.complete(Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        @Deprecated("vecchie versioni")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { onData(it) }
        }
    }

    private fun onData(v: ByteArray) {
        synchronized(sb) {
            sb.append(String(v))
            if (sb.indexOf(">") >= 0) prompt?.complete(Unit)
        }
    }

    private fun pick(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val skip = setOf("1800", "1801", "180a", "180f")
        val services = g.services.filter { s -> skip.none { s.uuid.toString().startsWith("0000$it") } }
            .sortedBy { s ->
                val u = s.uuid.toString()
                when { u.startsWith("0000fff0") -> 0; u.startsWith("000018f0") -> 1; u.startsWith("0000ffe0") -> 2; else -> 3 }
            }
        for (s in services) {
            val n = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val w = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (n != null && w != null) return n to w
        }
        return null
    }

    override suspend fun connect() {
        val ad = adapter(ctx)
        if (!ad.isEnabled) throw IOException("Bluetooth spento")
        val dev = ad.getRemoteDevice(mac)
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        withTimeoutOrNull(20_000) { ready.await() } ?: run { close(); throw IOException("timeout connessione BLE") }
    }

    override suspend fun send(cmd: String, timeoutMs: Long): String {
        val g = gatt ?: throw IOException("non collegato")
        val c = writeCh ?: throw IOException("non collegato")
        if (!open) throw IOException("BLE scollegato")
        synchronized(sb) { sb.setLength(0) }
        val p = CompletableDeferred<Unit>()
        prompt = p
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val bytes = (cmd + "\r").toByteArray()
        var res = g.writeCharacteristic(c, bytes, type)
        if (res != BluetoothStatusCodes.SUCCESS) {
            delay(50)
            res = g.writeCharacteristic(c, bytes, type)
            if (res != BluetoothStatusCodes.SUCCESS) throw IOException("scrittura BLE fallita ($res)")
        }
        withTimeoutOrNull(timeoutMs) { p.await() }
        val out = synchronized(sb) { sb.toString() }
        if (out.isEmpty()) throw IOException("nessuna risposta")
        return out.replace(">", "")
    }

    override fun close() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; open = false
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** Sceglie il tipo di collegamento; se sconosciuto prova prima BLE poi classico. */
suspend fun openLink(ctx: Context, mac: String, type: String): Pair<ObdLink, String> {
    val order = when (type) { "classic" -> listOf("classic"); "ble" -> listOf("ble"); else -> listOf("ble", "classic") }
    var last: Exception? = null
    for (t in order) {
        val l: ObdLink = if (t == "ble") BleLink(ctx, mac) else ClassicLink(ctx, mac)
        try { l.connect(); return l to t } catch (e: Exception) {
            if (e is CancellationException) throw e
            last = e; l.close(); Live.log("Tentativo $t fallito: ${e.message}")
        }
    }
    throw IOException(last?.message ?: "collegamento non riuscito")
}
