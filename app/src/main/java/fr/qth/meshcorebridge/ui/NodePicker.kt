package fr.qth.meshcorebridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Appareil Bluetooth trouvé pendant un scan. */
data class ScanItem(val name: String?, val address: String, val rssi: Int)

private fun ScanItem.isMeshCore() = name?.startsWith("MeshCore", ignoreCase = true) == true

private fun signalLabel(rssi: Int): String = when {
    rssi >= -65 -> "Fort"
    rssi >= -80 -> "Moyen"
    else -> "Faible"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodePickerSheet(
    scanning: Boolean,
    results: List<ScanItem>,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onToggleScan: () -> Unit,
    onPick: (ScanItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choisir un nœud MeshCore", style = MaterialTheme.typography.titleLarge)

            Button(onClick = onToggleScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (scanning) "Arrêter" else "Scanner")
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Switch) { onShowAllChange(!showAll) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Afficher tous les appareils Bluetooth", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = showAll, onCheckedChange = null)
            }
            HorizontalDivider()

            val sorted = results.sortedByDescending { it.rssi }
            if (sorted.isEmpty()) {
                Text(
                    "Aucun nœud trouvé. Vérifiez que le nœud est allumé et que le Bluetooth est activé",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(sorted, key = { it.address }) { item ->
                        val mesh = item.isMeshCore()
                        Surface(
                            color = if (mesh) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(item) },
                        ) {
                            Column(Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Text(
                                    item.name ?: "Appareil sans nom",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (mesh) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    item.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "Signal ${signalLabel(item.rssi)} (${item.rssi} dBm)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(bottom = 16.dp)) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodePickerPreview() {
    MeshTheme {
        NodePickerSheet(
            scanning = true,
            results = listOf(
                ScanItem("MeshCore-ab12", "AA:BB:CC:DD:EE:01", -58),
                ScanItem(null, "AA:BB:CC:DD:EE:02", -90),
                ScanItem("Casque", "AA:BB:CC:DD:EE:03", -75),
            ),
            showAll = true,
            onShowAllChange = {},
            onToggleScan = {},
            onPick = {},
            onDismiss = {},
        )
    }
}
