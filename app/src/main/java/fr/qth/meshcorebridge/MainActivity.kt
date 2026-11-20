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
import android.widget.Toast
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.qth.meshcorebridge.ui.HomeScreen
import fr.qth.meshcorebridge.ui.LogScreen
import fr.qth.meshcorebridge.ui.MeshTheme
import fr.qth.meshcorebridge.ui.NodePickerSheet
import fr.qth.meshcorebridge.ui.ScanItem
import fr.qth.meshcorebridge.ui.SettingsScreen
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private var scanning by mutableStateOf(false)
    private val found = mutableStateMapOf<String, ScanItem>()
    private var showAll by mutableStateOf(false)
    private var hasPerms by mutableStateOf(false)
    private var hasLan by mutableStateOf(true)

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun checkPerms() {
        hasPerms = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        hasLan = LocalNetwork.isGranted(this)
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name
            val hasNus = r.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE } == true
            if (showAll || hasNus || name?.startsWith("MeshCore", ignoreCase = true) == true) {
                found[r.device.address] = ScanItem(name, r.device.address, r.rssi)
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

    /** Exporte le journal complet (en-tête de diagnostic + mémoire) via la feuille de partage. */
    private fun exportLog() {
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val f = File(dir, "meshcore-bridge-log.txt")
        val s = BridgeState.status.value
        f.writeText(buildString {
            appendLine("MeshCore Bridge ${BuildConfigInfo.version(this@MainActivity)} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) — ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Nœud : ${Prefs.name(this@MainActivity)} ${Prefs.address(this@MainActivity)}")
            appendLine("Réglages : port ${Prefs.port(this@MainActivity)}, écoute ${Prefs.host(this@MainActivity)}, mode ${Prefs.mode(this@MainActivity)}, boot ${Prefs.autostart(this@MainActivity)}")
            appendLine("État : service ${s.serviceRunning}, BLE ${s.ble}, ${s.clients.size} client(s), ↑${s.bytesToRadio} o ↓${s.bytesFromRadio} o")
            appendLine("(aucun contenu de message : seulement sens, longueur et code de paquet)")
            appendLine("---- événements persistants ----")
            File(filesDir, "bridge.log").takeIf { it.exists() }?.let { append(it.readText()) }
            appendLine("---- journal en mémoire (trames comprises) ----")
            BridgeLog.snapshot().forEach { appendLine(it) }
        })
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Exporter le journal"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BridgeLog.init(this)
        Alerts.ensureChannels(this)
        checkPerms()
        Alerts.checkUnexpectedStop(this)
        setContent { MeshTheme { Root() } }
    }

    override fun onResume() { super.onResume(); checkPerms() }
    override fun onPause() { super.onPause(); if (scanning) stopScan() }

    @Composable
    private fun Root() {
        val status by BridgeState.status.collectAsStateWithLifecycle()
        val log by BridgeLog.lines.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showPicker by rememberSaveable { mutableStateOf(false) }
        var device by remember { mutableStateOf(Prefs.address(this) to Prefs.name(this)) }
        var autostart by remember { mutableStateOf(Prefs.autostart(this)) }
        var multi by remember { mutableStateOf(Prefs.multiClient(this)) }
        var listenAll by remember { mutableStateOf(Prefs.listenAll(this)) }
        var portText by rememberSaveable { mutableStateOf(Prefs.port(this).toString()) }
        var autoPin by remember { mutableStateOf(Prefs.autoPin(this)) }
        var pin by remember { mutableStateOf(Prefs.pin(this)) }
        val portValid = portText.toIntOrNull()?.let { it in 1024..65535 } == true
        val port = portText.toIntOrNull() ?: Prefs.DEFAULT_PORT

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPerms() }
        val lanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            checkPerms()
            listenAll = granted; Prefs.setListenAll(this, granted)
            if (!granted) BridgeLog.i("permission réseau local refusée : écoute LAN non activée")
        }

        if (scanning) LaunchedEffect(Unit) { delay(12_000); stopScan() }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    val tabs = listOf("Relais" to "⇄", "Réglages" to "⚙", "Journal" to "≡")
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
                    }
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when (tab) {
                0 -> HomeScreen(
                    status = status, nodeName = device.second, nodeAddress = device.first,
                    listenAll = listenAll, port = port, portValid = portValid, hasPerms = hasPerms, hasLan = hasLan,
                    lanIps = if (listenAll) LocalNetwork.ipv4Addresses() else emptyList(),
                    onGrantPerms = { permLauncher.launch(requiredPermissions()) },
                    onPickNode = {
                        if (status.serviceRunning) Toast.makeText(this, "Arrêtez le relais pour changer de nœud", Toast.LENGTH_SHORT).show()
                        else showPicker = true
                    },
                    onStart = { Prefs.setPort(this, port); BridgeService.start(this, "interface") },
                    onStop = { BridgeService.stop(this) },
                    onOpenSettings = { tab = 1 },
                    modifier = m,
                )
                1 -> SettingsScreen(
                    locked = status.serviceRunning,
                    portText = portText, portValid = portValid, onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                    listenAll = listenAll, hasLan = hasLan,
                    onListenAllChange = { on ->
                        if (!on) { listenAll = false; Prefs.setListenAll(this, false) }
                        else if (LocalNetwork.required && !LocalNetwork.isGranted(this)) lanLauncher.launch(LocalNetwork.PERMISSION)
                        else { listenAll = true; Prefs.setListenAll(this, true) }
                    },
                    multi = multi, onMultiChange = { multi = it; Prefs.setMultiClient(this, it) },
                    autoPin = autoPin, onAutoPinChange = { autoPin = it; Prefs.setAutoPin(this, it) },
                    pin = pin, onPinChange = { pin = it.filter(Char::isDigit).take(6); if (pin.length == 6) Prefs.setPin(this, pin) },
                    autostart = autostart, onAutostartChange = { autostart = it; Prefs.setAutostart(this, it) },
                    onOpenBatterySettings = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    versionText = BuildConfigInfo.version(this),
                    modifier = m,
                )
                else -> LogScreen(lines = log, onExport = { exportLog() }, modifier = m)
            }
        }

        if (showPicker) NodePickerSheet(
            scanning = scanning,
            results = found.values.sortedByDescending { it.rssi },
            showAll = showAll, onShowAllChange = { showAll = it },
            onToggleScan = { if (scanning) stopScan() else if (hasPerms) startScan() else permLauncher.launch(requiredPermissions()) },
            onPick = { f ->
                stopScan()
                if (status.serviceRunning) BridgeLog.i("changement de nœud ignoré : arrêtez le relais d'abord")
                else { Prefs.setDevice(this, f.address, f.name); device = f.address to f.name }
                showPicker = false
            },
            onDismiss = { stopScan(); showPicker = false },
        )
    }
}
