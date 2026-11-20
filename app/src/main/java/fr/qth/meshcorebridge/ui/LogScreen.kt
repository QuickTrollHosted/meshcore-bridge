package fr.qth.meshcorebridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class LogFilter(val label: String) { ALL("Tout"), EVENTS("Événements"), FRAMES("Trames") }

private enum class LineKind { EVENT, FRAME, WARN }

/** Longueur de l'horodatage « HH:mm:ss.SSS » préfixé par BridgeLog (12 caractères) suivi d'un espace. */
private const val TS_LEN = 13

/**
 * Critère de classification (voir BridgeLog.push : « HH:mm:ss.SSS message ») :
 * - BridgeLog.frame(...) n'est appelé que par BridgeService avec « "$dir $length o (code 0x..)" »
 *   où dir vaut « tcp→radio » ou « radio→tcp » : une trame est donc une ligne dont le message commence
 *   par l'une de ces deux directions (ou contient « trame »). Un test « contient → » seul serait faux :
 *   certains événements BridgeLog.i contiennent une flèche (ex. « … → repli sur … »).
 * - Les événements (BridgeLog.i) contenant « ⚠ », « refusé », « impossible » ou « manquante » sont
 *   affichés en couleur d'avertissement/erreur.
 */
private fun classify(line: String): LineKind {
    val msg = if (line.length > TS_LEN) line.substring(TS_LEN) else line
    return when {
        msg.startsWith("tcp→radio") || msg.startsWith("radio→tcp") || msg.contains("trame", ignoreCase = true) -> LineKind.FRAME
        msg.contains("⚠") || msg.contains("refusé", true) || msg.contains("impossible", true) ||
            msg.contains("manquante", true) -> LineKind.WARN
        else -> LineKind.EVENT
    }
}

@Composable
fun LogScreen(lines: List<String>, onExport: () -> Unit, modifier: Modifier = Modifier) {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    // Nombre de lignes masquées (état local : n'efface pas le journal réel).
    var hiddenCount by remember { mutableIntStateOf(0) }
    val colors = LocalStatusColors.current

    // Si le journal a été tronqué (tampon circulaire), ne pas masquer plus que ce qui existe.
    val start = hiddenCount.coerceAtMost(lines.size)
    val visible = remember(lines, filter, start) {
        lines.drop(start)
            .filter {
                when (filter) {
                    LogFilter.ALL -> true
                    LogFilter.EVENTS -> classify(it) != LineKind.FRAME
                    LogFilter.FRAMES -> classify(it) == LineKind.FRAME
                }
            }
            .asReversed()
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Exporter") }
            TextButton(
                onClick = { hiddenCount = lines.size },
                enabled = lines.size > start,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Vider l'affichage") }
        }

        if (visible.isEmpty()) {
            Text(
                "Aucun événement",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visible) { line ->
                        val color = when (classify(line)) {
                            LineKind.FRAME -> MaterialTheme.colorScheme.onSurfaceVariant
                            LineKind.WARN -> colors.warn
                            LineKind.EVENT -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            line,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogScreenPreview() {
    MeshTheme {
        LogScreen(
            lines = listOf(
                "12:00:01.100 service démarré — origine : ui (sdk 34)",
                "12:00:02.250 tcp→radio 12 o (code 0x3D)",
                "12:00:02.400 radio→tcp 40 o (code 0x01)",
                "12:00:05.000 ⚠ écoute LAN demandée mais permission refusée",
            ),
            onExport = {},
        )
    }
}
