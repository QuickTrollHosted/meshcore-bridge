package fr.qth.meshcorebridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private data class Found(val name: String?, val address: String, val rssi: Int)

class MainActivity : ComponentActivity() {
    private var scanning by mutableStateOf(false)
    private val found = mutableStateMapOf<String, Found>()
    private var showAll by mutableStateOf(false)
    private var hasPerms by mutableStateOf(false)

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun checkPerms() {
        hasPerms = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name
            val hasNus = r.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE } == true
            if (showAll || hasNus || name?.startsWith("MeshCore", ignoreCase = true) == true) {
                found[r.device.address] = Found(name, r.device.address, r.rssi)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val sc = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner ?: return
        found.clear()
        scanning = true
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanning = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeLog.init(this)
        Alerts.ensureChannels(this)
        checkPerms()
        Alerts.checkUnexpectedStop(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onResume() { super.onResume(); checkPerms() }
    override fun onPause() { super.onPause(); if (scanning) stopScan() }

    @Composable
    private fun Screen() {
        val status by BridgeState.status.collectAsStateWithLifecycle()
        val log by BridgeLog.lines.collectAsStateWithLifecycle()
        var device by remember { mutableStateOf(Prefs.address(this) to Prefs.name(this)) }
        var autostart by remember { mutableStateOf(Prefs.autostart(this)) }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPerms() }

        // Arrêt automatique du scan après 12 s
        if (scanning) androidx.compose.runtime.LaunchedEffect(Unit) { delay(12_000); stopScan() }

        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("MeshCore Bridge — phase 0", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            item { Text("Relais BLE ↔ TCP ${Prefs.HOST}:${Prefs.PORT} (un seul client)", fontSize = 13.sp) }

            if (!hasPerms) item {
                Button({ permLauncher.launch(requiredPermissions()) }, Modifier.fillMaxWidth()) { Text("Accorder les permissions (Bluetooth, notifications)") }
            }

            item { Text("Nœud : ${device.second ?: "—"} ${device.first ?: "(aucun)"}", fontWeight = FontWeight.SemiBold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ if (scanning) stopScan() else startScan() }, enabled = hasPerms) { Text(if (scanning) "Arrêter le scan" else "Scanner") }
                    Text("Tous les appareils"); Switch(showAll, { showAll = it })
                }
            }
            items(found.values.sortedByDescending { it.rssi }, key = { it.address }) { f ->
                Text(
                    "${f.name ?: "(sans nom)"}  ${f.address}  ${f.rssi} dBm",
                    Modifier.fillMaxWidth().clickable {
                        Prefs.setDevice(this@MainActivity, f.address, f.name); device = f.address to f.name; stopScan()
                    }.padding(vertical = 6.dp),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ BridgeService.start(this@MainActivity, "interface") }, enabled = hasPerms && device.first != null && !status.serviceRunning) { Text("Démarrer") }
                    OutlinedButton({ BridgeService.stop(this@MainActivity) }, enabled = status.serviceRunning) { Text("Arrêter") }
                }
            }
            item {
                Text(
                    "Service : ${if (status.serviceRunning) "actif" else "arrêté"}\n" +
                        "BLE : ${status.ble.label} ${status.bleDetail}\n" +
                        "Client TCP : ${status.clientRemote ?: "aucun"}\n" +
                        "Vers le nœud : ${status.bytesToRadio} o / ${status.framesToRadio} trames\n" +
                        "Depuis le nœud : ${status.bytesFromRadio} o / ${status.framesFromRadio} trames (${status.framesDropped} sans client)",
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(autostart, { autostart = it; Prefs.setAutostart(this@MainActivity, it) })
                    Text("Démarrer au boot (expérimental)")
                }
            }
            item {
                OutlinedButton({ startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.fillMaxWidth()) {
                    Text("Optimisation de la batterie (exempter l'appli)")
                }
            }
            item { Spacer(Modifier.height(4.dp)); Text("Journal", fontWeight = FontWeight.SemiBold) }
            items(log.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
