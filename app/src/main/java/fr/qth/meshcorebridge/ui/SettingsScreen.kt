package fr.qth.meshcorebridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private const val REPO_URL = "https://github.com/QuickTrollHosted/meshcore-bridge"

@Composable
fun SettingsScreen(
    locked: Boolean,
    portText: String,
    portValid: Boolean,
    onPortChange: (String) -> Unit,
    listenAll: Boolean,
    hasLan: Boolean,
    onListenAllChange: (Boolean) -> Unit,
    multi: Boolean,
    onMultiChange: (Boolean) -> Unit,
    autoPin: Boolean,
    onAutoPinChange: (Boolean) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    autostart: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    versionText: String,
    modifier: Modifier = Modifier,
) {
    var askLanWarning by rememberSaveable { mutableStateOf(false) }
    var showXiaomi by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    if (askLanWarning) {
        AlertDialog(
            onDismissRequest = { askLanWarning = false },
            title = { Text("Écouter sur le réseau local ?") },
            text = {
                Text(
                    "Le port TCP n'a AUCUNE authentification. Toute machine de votre réseau local pourra lire vos messages MeshCore " +
                        "et envoyer des commandes à votre nœud (y compris le réinitialiser). À n'activer que sur un réseau de confiance.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askLanWarning = false
                    onListenAllChange(true)
                }) { Text("Activer quand même") }
            },
            dismissButton = { TextButton(onClick = { askLanWarning = false }) { Text("Annuler") } },
        )
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (locked) {
            Banner(
                "Arrêtez le relais pour modifier la connexion et l'appairage",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        // ---- Connexion ----
        SectionTitle("Connexion")
        OutlinedTextField(
            value = portText,
            onValueChange = { onPortChange(it.filter(Char::isDigit).take(5)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !locked,
            label = { Text("Port TCP") },
            singleLine = true,
            isError = !portValid,
            supportingText = {
                Text(if (portValid) "Port d'écoute des applis clientes (1024–65535)" else "Entrez un port entre 1024 et 65535")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        SwitchRow(
            title = "Écouter sur le réseau local",
            help = "Permet à d'autres appareils du Wi-Fi de se connecter. Sinon, seul ce téléphone (127.0.0.1) peut le faire.",
            checked = listenAll,
            enabled = !locked,
            onCheckedChange = { wanted ->
                if (wanted) askLanWarning = true else onListenAllChange(false)
            },
        )
        if (listenAll) {
            Banner(
                "Aucune authentification : toute machine du réseau local peut lire vos messages et piloter votre nœud.",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!hasLan) {
                Text(
                    "Permission « réseau local » absente : l'écoute retombera sur 127.0.0.1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwitchRow(
            title = if (multi) "Mode multi-clients (diffusion)" else "Mode un seul client",
            help = "Autorise plusieurs applis à se connecter en même temps au relais.",
            checked = multi,
            enabled = !locked,
            onCheckedChange = onMultiChange,
        )
        if (multi) {
            Text(
                "Multi-clients : chaque client reçoit TOUTES les réponses du nœud, y compris celles destinées aux autres, " +
                    "et les commandes de tous passent dans une file unique. Évitez deux clients qui pilotent le nœud en même temps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Appairage ----
        SectionTitle("Appairage")
        SwitchRow(
            title = "PIN automatique",
            help = "Saisit le PIN d'appairage à votre place lors de la connexion au nœud.",
            checked = autoPin,
            enabled = !locked,
            onCheckedChange = onAutoPinChange,
        )
        if (autoPin) {
            OutlinedTextField(
                value = pin,
                onValueChange = { onPinChange(it.filter(Char::isDigit).take(6)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !locked,
                label = { Text("PIN du nœud (défaut 123456)") },
                singleLine = true,
                isError = pin.isNotEmpty() && pin.length != 6,
                supportingText = { Text("6 chiffres") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }

        // ---- Démarrage et fiabilité ----
        SectionTitle("Démarrage et fiabilité")
        SwitchRow(
            title = "Démarrer au boot",
            help = "Relance le relais automatiquement quand le téléphone redémarre.",
            checked = autostart,
            enabled = true,
            onCheckedChange = onAutostartChange,
        )
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("Optimisation de la batterie", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Exemptez l'appli pour que le système n'arrête pas le relais en arrière-plan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
            ) { Text("Exempter l'appli") }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { showXiaomi = !showXiaomi },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Aide Xiaomi / HyperOS", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (showXiaomi) "▲" else "▼", style = MaterialTheme.typography.bodyMedium)
        }
        if (showXiaomi) {
            Text(
                "Xiaomi / HyperOS : activez aussi « Démarrage automatique » pour l'appli, mettez la batterie sur « Sans restriction » " +
                    "et verrouillez l'appli dans les récentes, sinon le système peut tuer le relais.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- À propos ----
        SectionTitle("À propos")
        Text(versionText, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Licence MIT",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            REPO_URL,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { runCatching { uriHandler.openUri(REPO_URL) } }
                .padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Banner(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    help: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

// @Preview (nécessite androidx.compose.ui:ui-tooling-preview, absent des dépendances)
@Composable
private fun SettingsScreenPreview() {
    MeshTheme(dark = false) {
        SettingsScreen(
            locked = false, portText = "5000", portValid = true, onPortChange = {},
            listenAll = true, hasLan = false, onListenAllChange = {},
            multi = true, onMultiChange = {}, autoPin = true, onAutoPinChange = {},
            pin = "123456", onPinChange = {}, autostart = false, onAutostartChange = {},
            onOpenBatterySettings = {}, versionText = "Version 1.0 (1)",
        )
    }
}

// @Preview (nécessite androidx.compose.ui:ui-tooling-preview, absent des dépendances)
@Composable
private fun SettingsScreenLockedPreview() {
    MeshTheme(dark = true) {
        SettingsScreen(
            locked = true, portText = "99", portValid = false, onPortChange = {},
            listenAll = false, hasLan = true, onListenAllChange = {},
            multi = false, onMultiChange = {}, autoPin = false, onAutoPinChange = {},
            pin = "", onPinChange = {}, autostart = true, onAutostartChange = {},
            onOpenBatterySettings = {}, versionText = "Version 1.0 (1)",
        )
    }
}
