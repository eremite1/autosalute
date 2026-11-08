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
import it.eremite.autosalute.Live
import it.eremite.autosalute.Prefs
import it.eremite.autosalute.Updater
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import it.eremite.autosalute.service.ObdService
import it.eremite.autosalute.service.Watch

class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val cdmLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
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
    val tabs = listOf("Salute" to Icons.Filled.Favorite, "Live" to Icons.Filled.PlayArrow, "Viaggi" to Icons.AutoMirrored.Filled.List,
        "Tendenze" to Icons.Filled.DateRange, "Imposta" to Icons.Filled.Settings)
    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            NavigationBar(containerColor = C.card) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = C.card2, selectedIconColor = C.ok, selectedTextColor = C.ok,
                            unselectedIconColor = C.dim, unselectedTextColor = C.dim)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(act) { tab = 4 }
                1 -> LiveScreen()
                2 -> TripsScreen()
                3 -> TrendsScreen()
                else -> SetupScreen(act)
            }
        }
    }
}
