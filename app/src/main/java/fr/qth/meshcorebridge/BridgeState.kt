package fr.qth.meshcorebridge

import android.content.Context
import fr.qth.meshcorebridge.core.BridgeCounters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BleState(val label: String) {
    STOPPED("Arrêté"),
    CONNECTING("Connexion…"),
    DISCOVERING("Découverte des services…"),
    READY("Connecté"),
    RETRY("Déconnecté, nouvelle tentative"),
}

data class BridgeStatus(
    val serviceRunning: Boolean = false,
    val ble: BleState = BleState.STOPPED,
    val bleDetail: String = "",
    val clients: List<String> = emptyList(),
    val bytesToRadio: Long = 0,
    val bytesFromRadio: Long = 0,
    val framesToRadio: Long = 0,
    val framesFromRadio: Long = 0,
    val framesDropped: Long = 0,
)

/** État partagé service ↔ UI (même processus). */
object BridgeState {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status

    fun update(f: (BridgeStatus) -> BridgeStatus) = _status.update(f)

    fun syncCounters(c: BridgeCounters) = update {
        it.copy(
            bytesToRadio = c.bytesToRadio.get(), bytesFromRadio = c.bytesFromRadio.get(),
            framesToRadio = c.framesToRadio.get(), framesFromRadio = c.framesFromRadio.get(),
            framesDropped = c.framesDroppedNoClient.get(),
        )
    }

    fun reset() { _status.value = BridgeStatus() }
}

/**
 * Journal : mémoire (affiché à l'écran) + fichier `files/bridge.log` pour les événements
 * (survie du service, boot…). Les trames individuelles ne vont qu'en mémoire.
 * Jamais de contenu de message : seulement sens, longueur et code de paquet.
 */
object BridgeLog {
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 512 * 1024L
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    @Volatile private var file: File? = null

    fun init(c: Context) {
        if (file == null) file = File(c.applicationContext.filesDir, "bridge.log")
    }

    /** Événement : mémoire + fichier. */
    @Synchronized fun i(msg: String) {
        push(msg)
        val f = file ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText("${fileFmt.format(Date())} $msg\n")
        } catch (_: Exception) {}
    }

    /** Copie complète du journal en mémoire (événements et trames), du plus ancien au plus récent. */
    fun snapshot(): List<String> = _lines.value

    /** Trame : mémoire seulement. */
    @Synchronized fun frame(msg: String) = push(msg)

    private fun push(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        val cur = _lines.value
        _lines.value = if (cur.size >= MAX_LINES) cur.drop(cur.size - MAX_LINES + 1) + line else cur + line
    }
}
