package fr.qth.meshcorebridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.qth.meshcorebridge.BleState
import fr.qth.meshcorebridge.BridgeStatus
import java.util.Locale

private enum class Tone { OK, WARN, ERROR, IDLE }

private data class StateInfo(val tone: Tone, val title: String, val subtitle: String)

private fun stateInfo(s: BridgeStatus): StateInfo {
    if (!s.serviceRunning) {
        return StateInfo(Tone.IDLE, "Arrêté", "Le relais est éteint. Démarrez-le pour que vos applis MeshCore puissent s'y connecter.")
    }
    val clients = when (s.clients.size) {
        0 -> "Aucune appli connectée pour l'instant."
        1 -> "1 appli connectée."
        else -> "${s.clients.size} applis connectées."
    }
    return when (s.ble) {
        BleState.READY -> StateInfo(Tone.OK, "Relais actif", "Nœud connecté. $clients")
        BleState.CONNECTING, BleState.DISCOVERING ->
            StateInfo(Tone.WARN, "En attente du nœud…", s.bleDetail.ifBlank { "Connexion Bluetooth au nœud en cours. Gardez-le allumé et à proximité." })
        BleState.RETRY ->
            StateInfo(Tone.ERROR, "Erreur : nœud injoignable", s.bleDetail.ifBlank { "Nouvelle tentative automatique en cours. Vérifiez que le nœud est allumé et à portée." })
        BleState.STOPPED -> StateInfo(Tone.WARN, "Démarrage…", "Le relais se lance.")
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024L -> "$n o"
    n < 1024L * 1024L -> String.format(Locale.FRANCE, "%.1f Ko", n / 1024.0)
    else -> String.format(Locale.FRANCE, "%.2f Mo", n / (1024.0 * 1024.0))
}

@Composable
fun HomeScreen(
    status: BridgeStatus,
    nodeName: String?,
    nodeAddress: String?,
    listenAll: Boolean,
    port: Int,
    portValid: Boolean,
    hasPerms: Boolean,
    hasLan: Boolean,
    lanIps: List<String>,
    onGrantPerms: () -> Unit,
    onPickNode: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasNode = !nodeAddress.isNullOrBlank()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(status, hasNode, hasPerms, portValid, onStart, onStop) }

        if (!hasPerms) {
            item {
                AlertBanner(
                    tone = Tone.ERROR,
                    title = "Autorisations manquantes",
                    text = "Le Bluetooth et les notifications sont nécessaires pour joindre le nœud et garder le relais actif.",
                    actionLabel = "Autoriser",
                    onAction = onGrantPerms,
                )
            }
        }
        if (listenAll) {
            item {
                val text = if (hasLan) {
                    "Toute appli du réseau local peut se connecter au relais, sans authentification. À n'utiliser que sur un réseau de confiance."
                } else {
                    "Aucun réseau local détecté : l'écoute retombe sur 127.0.0.1 (cet appareil seulement). Connectez-vous au Wi-Fi ou désactivez l'option."
                }
                AlertBanner(
                    tone = Tone.WARN,
                    title = "Écoute réseau local active",
                    text = text,
                    actionLabel = if (!hasLan) "Réglages" else null,
                    onAction = onOpenSettings,
                )
            }
        }

        item { NodeCard(nodeName, nodeAddress, hasNode, onPickNode) }
        item { ClientsCard(status.clients, listenAll, hasLan, port, lanIps) }
        item { TrafficCard(status) }
    }
}

@Composable
private fun toneColor(t: Tone): Color {
    val c = LocalStatusColors.current
    return when (t) {
        Tone.OK -> c.ok
        Tone.WARN -> c.warn
        Tone.ERROR -> c.error
        Tone.IDLE -> c.idle
    }
}

@Composable
private fun StatusCard(
    status: BridgeStatus,
    hasNode: Boolean,
    hasPerms: Boolean,
    portValid: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val info = stateInfo(status)
    val color = toneColor(info.tone)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(
                    Modifier
                        .size(16.dp)
                        .background(color, CircleShape)
                        .clearAndSetSemantics { }
                )
                Text(
                    info.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { contentDescription = "État du relais : ${info.title}" },
                )
            }
            Text(info.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (status.serviceRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Arrêter le relais", style = MaterialTheme.typography.titleMedium) }
            } else {
                val reason = when {
                    !hasNode -> "Choisissez d'abord un nœud."
                    !hasPerms -> "Autorisez d'abord le Bluetooth et les notifications."
                    !portValid -> "Le port n'est pas valide : corrigez-le dans les réglages."
                    else -> null
                }
                Button(
                    onClick = onStart,
                    enabled = reason == null,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
                ) { Text("Démarrer le relais", style = MaterialTheme.typography.titleMedium) }
                if (reason != null) {
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AlertBanner(
    tone: Tone,
    title: String,
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val color = toneColor(tone)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pictogramme texte (ne repose pas sur la couleur seule).
                Text(
                    "!",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = "Alerte : $title" },
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun NodeCard(nodeName: String?, nodeAddress: String?, hasNode: Boolean, onPickNode: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nœud", style = MaterialTheme.typography.titleMedium)
            if (hasNode) {
                Text(
                    nodeName?.takeIf { it.isNotBlank() } ?: "Nœud sans nom",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    nodeAddress.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onPickNode,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text("Changer de nœud") }
            } else {
                Text("Aucun nœud choisi", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "1. Allumez votre nœud MeshCore (Bluetooth).\n2. Appuyez sur « Choisir un nœud ».\n3. Sélectionnez-le dans la liste.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onPickNode,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text("Choisir un nœud") }
            }
        }
    }
}

@Composable
private fun ClientsCard(clients: List<String>, listenAll: Boolean, hasLan: Boolean, port: Int, lanIps: List<String>) {
    val clipboard = LocalClipboardManager.current
    val addresses = buildList {
        add("127.0.0.1:$port" to "Cet appareil")
        if (listenAll && hasLan) lanIps.forEach { add("$it:$port" to "Réseau local") }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connexion des clients", style = MaterialTheme.typography.titleMedium)
            Text(
                "Saisissez l'une de ces adresses (TCP) dans vos autres applis MeshCore.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            addresses.forEach { (addr, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(addr, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(addr)) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Copier l'adresse $addr" },
                    ) { Text("Copier") }
                }
            }
            Text(
                if (clients.isEmpty()) "Aucun client connecté" else "Clients connectés (${clients.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            clients.forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun TrafficCard(s: BridgeStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trafic", style = MaterialTheme.typography.titleMedium)
            TrafficRow("Vers le nœud", "${formatBytes(s.bytesToRadio)} · ${s.framesToRadio} trames")
            TrafficRow("Depuis le nœud", "${formatBytes(s.bytesFromRadio)} · ${s.framesFromRadio} trames")
            TrafficRow("Trames sans client", s.framesDropped.toString())
        }
    }
}

@Composable
private fun TrafficRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// @Preview(showBackground = true) : à activer si ui-tooling-preview est ajouté aux dépendances.
@Composable
internal fun HomeScreenActivePreview() {
    MeshTheme(dark = false) {
        HomeScreen(
            status = BridgeStatus(
                serviceRunning = true, ble = BleState.READY, clients = listOf("192.168.1.20:51234"),
                bytesToRadio = 2048, bytesFromRadio = 3_500_000, framesToRadio = 12, framesFromRadio = 340, framesDropped = 2,
            ),
            nodeName = "MeshCore-A1B2", nodeAddress = "AA:BB:CC:DD:EE:FF",
            listenAll = true, port = 5000, portValid = true, hasPerms = true, hasLan = true,
            lanIps = listOf("192.168.1.42"),
            onGrantPerms = {}, onPickNode = {}, onStart = {}, onStop = {}, onOpenSettings = {},
        )
    }
}

// @Preview(showBackground = true) : à activer si ui-tooling-preview est ajouté aux dépendances.
@Composable
internal fun HomeScreenEmptyPreview() {
    MeshTheme(dark = true) {
        HomeScreen(
            status = BridgeStatus(),
            nodeName = null, nodeAddress = null,
            listenAll = true, port = 5000, portValid = true, hasPerms = false, hasLan = false,
            lanIps = emptyList(),
            onGrantPerms = {}, onPickNode = {}, onStart = {}, onStop = {}, onOpenSettings = {},
        )
    }
}
