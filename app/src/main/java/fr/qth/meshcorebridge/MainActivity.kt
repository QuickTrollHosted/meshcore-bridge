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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.io.File

private data class Found(val name: String?, val address: String, val rssi: Int)

class MainActivity : ComponentActivity() {
    private var scanning by mutableStateOf(false)
    private val found = mutableStateMapOf<String, Found>()
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
        BridgeLog.init(this)
        Alerts.ensureChannels(this)
        checkPerms()
        Alerts.checkUnexpectedStop(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onResume() { super.onResume(); checkPerms() }
    override fun onPause() { super.onPause(); if (scanning) stopScan() }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked, onChange, enabled = enabled)
            Text(label, Modifier.weight(1f))
        }
    }

    @Composable
    private fun Screen() {
        val status by BridgeState.status.collectAsStateWithLifecycle()
        val log by BridgeLog.lines.collectAsStateWithLifecycle()
        var device by remember { mutableStateOf(Prefs.address(this) to Prefs.name(this)) }
        var autostart by remember { mutableStateOf(Prefs.autostart(this)) }
        var multi by remember { mutableStateOf(Prefs.multiClient(this)) }
        var listenAll by remember { mutableStateOf(Prefs.listenAll(this)) }
        var portText by remember { mutableStateOf(Prefs.port(this).toString()) }
        var autoPin by remember { mutableStateOf(Prefs.autoPin(this)) }
        var pin by remember { mutableStateOf(Prefs.pin(this)) }
        var askLanWarning by remember { mutableStateOf(false) }
        val locked = status.serviceRunning
        val portValid = portText.toIntOrNull()?.let { it in 1024..65535 } == true

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPerms() }
        val lanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            checkPerms()
            listenAll = granted; Prefs.setListenAll(this, granted)
            if (!granted) BridgeLog.i("permission réseau local refusée : écoute LAN non activée")
        }

        if (scanning) LaunchedEffect(Unit) { delay(12_000); stopScan() }

        if (askLanWarning) AlertDialog(
            onDismissRequest = { askLanWarning = false },
            title = { Text("Écouter sur le réseau local ?") },
            text = {
                Text("Le port TCP n'a AUCUNE authentification. Toute machine de votre réseau local pourra lire vos messages MeshCore " +
                    "et envoyer des commandes à votre nœud (y compris le réinitialiser). À n'activer que sur un réseau de confiance.")
            },
            confirmButton = {
                TextButton({
                    askLanWarning = false
                    if (LocalNetwork.required && !LocalNetwork.isGranted(this@MainActivity)) lanLauncher.launch(LocalNetwork.PERMISSION)
                    else { listenAll = true; Prefs.setListenAll(this@MainActivity, true) }
                }) { Text("Activer quand même") }
            },
            dismissButton = { TextButton({ askLanWarning = false }) { Text("Annuler") } },
        )

        val lanIps = if (listenAll) LocalNetwork.ipv4Addresses() else emptyList()
        val shownPort = portText.toIntOrNull() ?: Prefs.DEFAULT_PORT

        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("MeshCore Bridge", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            item { Text("Relais BLE ↔ TCP pour nœud MeshCore. Le nœud n'accepte qu'une connexion BLE : l'appli officielle doit passer par ce relais en TCP.", fontSize = 12.sp) }

            if (!hasPerms) item {
                Button({ permLauncher.launch(requiredPermissions()) }, Modifier.fillMaxWidth()) { Text("Accorder les permissions (Bluetooth, notifications)") }
            }

            // ---- Nœud ----
            item { Text("Nœud : ${device.second ?: "—"} ${device.first ?: "(aucun)"}", fontWeight = FontWeight.SemiBold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ if (scanning) stopScan() else startScan() }, enabled = hasPerms && !locked) { Text(if (scanning) "Arrêter le scan" else "Scanner") }
                    Text("Tous les appareils"); Switch(showAll, { showAll = it })
                }
            }
            items(found.values.sortedByDescending { it.rssi }, key = { it.address }) { f ->
                Text(
                    "${f.name ?: "(sans nom)"}  ${f.address}  ${f.rssi} dBm",
                    Modifier.fillMaxWidth().clickable(enabled = !locked) {
                        Prefs.setDevice(this@MainActivity, f.address, f.name); device = f.address to f.name; stopScan()
                    }.padding(vertical = 6.dp),
                )
            }

            // ---- Démarrer / arrêter ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        Prefs.setPort(this@MainActivity, shownPort)
                        BridgeService.start(this@MainActivity, "interface")
                    }, enabled = hasPerms && device.first != null && !status.serviceRunning && portValid) { Text("Démarrer") }
                    OutlinedButton({ BridgeService.stop(this@MainActivity) }, enabled = status.serviceRunning) { Text("Arrêter") }
                }
            }

            // ---- État ----
            item {
                val addr = if (listenAll && lanIps.isNotEmpty()) lanIps.joinToString(" ou ") { "$it:$shownPort" } + "  (et 127.0.0.1:$shownPort)"
                else "${if (listenAll) "0.0.0.0" else Prefs.LOOPBACK}:$shownPort"
                Text(
                    "Service : ${if (status.serviceRunning) "actif" else "arrêté"}\n" +
                        "BLE : ${status.ble.label} ${status.bleDetail}\n" +
                        "Adresse du relais : $addr\n" +
                        "Clients TCP : ${status.clients.size}${if (status.clients.isNotEmpty()) " (" + status.clients.joinToString() + ")" else ""}\n" +
                        "Vers le nœud : ${status.bytesToRadio} o / ${status.framesToRadio} trames\n" +
                        "Depuis le nœud : ${status.bytesFromRadio} o / ${status.framesFromRadio} trames (${status.framesDropped} sans client)",
                )
            }

            // ---- Réglages ----
            item { Text("Réglages${if (locked) " (arrêter le service pour modifier)" else ""}", fontWeight = FontWeight.SemiBold) }
            item {
                OutlinedTextField(
                    portText, { portText = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), enabled = !locked,
                    label = { Text("Port TCP (1024–65535)") }, isError = !portValid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                SwitchRow("Écouter sur le réseau local (0.0.0.0)", listenAll, !locked) { on ->
                    if (on) askLanWarning = true else { listenAll = false; Prefs.setListenAll(this@MainActivity, false) }
                }
            }
            if (listenAll) item {
                Text("⚠ Aucune authentification : tout appareil du réseau peut piloter votre nœud.", color = Color(0xFFB00020), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (listenAll && LocalNetwork.required && !hasLan) item {
                Text("Permission « réseau local » absente : l'écoute retombera sur 127.0.0.1.", color = Color(0xFFB00020), fontSize = 12.sp)
            }
            item {
                SwitchRow(if (multi) "Mode multi-clients (diffusion)" else "Mode un seul client", multi, !locked) { multi = it; Prefs.setMultiClient(this@MainActivity, it) }
            }
            if (multi) item {
                Text("Multi-clients : chaque client reçoit TOUTES les réponses du nœud, y compris celles destinées aux autres, " +
                    "et les commandes de tous passent dans une file unique. Évitez deux clients qui pilotent le nœud en même temps.", fontSize = 12.sp)
            }
            item { SwitchRow("Saisir le PIN d'appairage automatiquement", autoPin, !locked) { autoPin = it; Prefs.setAutoPin(this@MainActivity, it) } }
            if (autoPin) item {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(6); if (pin.length == 6) Prefs.setPin(this@MainActivity, pin) }, Modifier.fillMaxWidth(),
                    enabled = !locked, label = { Text("PIN du nœud (défaut 123456)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
            item { SwitchRow("Démarrer au boot", autostart) { autostart = it; Prefs.setAutostart(this@MainActivity, it) } }

            // ---- Survie du service ----
            item {
                OutlinedButton({ startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.fillMaxWidth()) {
                    Text("Optimisation de la batterie (exempter l'appli)")
                }
            }
            item {
                Text("Xiaomi / HyperOS : activez aussi « Démarrage automatique » pour l'appli, mettez la batterie sur « Sans restriction » " +
                    "et verrouillez l'appli dans les récentes, sinon le système peut tuer le relais.", fontSize = 12.sp)
            }

            // ---- Journal ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Journal", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OutlinedButton({ exportLog() }) { Text("Exporter") }
                }
            }
            items(log.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
