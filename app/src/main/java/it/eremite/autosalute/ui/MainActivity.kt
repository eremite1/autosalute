package it.eremite.autosalute.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import it.eremite.autosalute.Live
import it.eremite.autosalute.Prefs
import it.eremite.autosalute.Updater
import it.eremite.autosalute.data.Backup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import it.eremite.autosalute.service.ObdService
import it.eremite.autosalute.service.Watch

class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val cdmLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val toast = mutableStateOf<String?>(null)

    /** Crea il file di backup (l'utente sceglie Google Drive e la cartella). */
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            Backup.setTarget(this, uri)
            lifecycleScope.launch {
                val err = Backup.runNow(this@MainActivity, uri)
                Toast.makeText(this@MainActivity, err?.let { "Backup non riuscito: $it" } ?: "Backup salvato. D'ora in poi si aggiorna da solo.", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val msg = Backup.restore(this@MainActivity, uri)
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            refresh.intValue++
        }
    }
    fun chooseBackup() = createBackup.launch("AutoSalute-backup.json")
    fun chooseRestore() = openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
    fun backupNow() = lifecycleScope.launch {
        val err = Backup.runNow(this@MainActivity)
        Toast.makeText(this@MainActivity, err?.let { "Backup non riuscito: $it" } ?: "Backup aggiornato", Toast.LENGTH_SHORT).show()
    }
    fun setAutostart(v: Boolean) {
        Prefs.setAutostart(this, v)
        if (v) Watch.arm(this) else { Watch.disarm(this); Prefs.setSentinel(this, false); stopNow() }
        refresh.intValue++
    }
    val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askPermissions()
        setContent { AppTheme { Root(this) } }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
        lifecycleScope.launch { Updater.check(this@MainActivity, quiet = true, minGapMs = 3 * 3600_000L) }
    }

    fun askPermissions() {
        perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS))
    }

    fun batteryOk(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Apre la finestra di sistema per scegliere il lettore OBD2. */
    fun associate() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val req = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().build())
            .build()
        cdm.associate(req, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                cdmLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            override fun onAssociationCreated(associationInfo: AssociationInfo) { saved(associationInfo) }
            override fun onFailure(error: CharSequence?) {
                Toast.makeText(this@MainActivity, "Associazione non riuscita: $error", Toast.LENGTH_LONG).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun saved(info: AssociationInfo) {
        val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return
        val dev = try { getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(mac) } catch (_: Exception) { null }
        val type = when (try { dev?.type } catch (_: Exception) { null }) {
            BluetoothDevice.DEVICE_TYPE_LE -> "ble"
            BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL -> "classic"
            else -> "auto"
        }
        val name = info.displayName?.toString() ?: try { dev?.name } catch (_: Exception) { null }
        Watch.disarm(this)
        Prefs.setDevice(this, mac, name, type)
        Prefs.setSentinel(this, type == "classic")
        Watch.arm(this)
        Live.log("Associato: ${name ?: mac} ($type)")
        refresh.intValue++
        Toast.makeText(this, "Lettore associato!", Toast.LENGTH_SHORT).show()
    }

    fun forget() {
        val mac = Prefs.mac(this) ?: return
        Watch.disarm(this)
        try {
            val cdm = getSystemService(CompanionDeviceManager::class.java)
            cdm.myAssociations.filter { it.deviceMacAddress?.toString()?.uppercase() == mac }.forEach { cdm.disassociate(it.id) }
        } catch (_: Exception) {}
        getSharedPreferences("autosalute", MODE_PRIVATE).edit().remove("mac").remove("name").apply()
        refresh.intValue++
    }

    fun startNow() = ObdService.start(this, "manuale")
    fun stopNow() = ObdService.stop(this)
}

@Composable
fun Root(act: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Salute" to Icons.Filled.Favorite, "Eco" to Icons.Filled.Star, "Viaggi" to Icons.AutoMirrored.Filled.List,
        "Statistiche" to Icons.Filled.DateRange, "Officina" to Icons.Filled.Build, "Imposta" to Icons.Filled.Settings)
    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            NavigationBar(containerColor = C.card) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = C.card2, selectedIconColor = C.ok, selectedTextColor = C.ok,
                            unselectedIconColor = C.dim, unselectedTextColor = C.dim)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(act, goMaint = { tab = 4 }) { tab = 5 }
                1 -> EcoScreen()
                2 -> TripsScreen()
                3 -> TrendsScreen()
                4 -> MaintScreen()
                else -> SetupScreen(act)
            }
        }
    }
}
