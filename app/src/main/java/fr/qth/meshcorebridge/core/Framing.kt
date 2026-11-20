package fr.qth.meshcorebridge.core

/**
 * Framing du protocole compagnon MeshCore côté TCP / série.
 *
 * Vérifié dans le firmware (`src/helpers/ArduinoSerialInterface.cpp`) et dans `meshcore-proxy` :
 *  - client → nœud : `0x3C ('<')` + longueur (uint16 little-endian) + trame
 *  - nœud → client : `0x3E ('>')` + longueur (uint16 little-endian) + trame
 *
 * En BLE, une écriture ou une notification GATT = une trame, sans aucun en-tête.
 * Le pont convertit donc : TCP(`<`…) → BLE(trame brute) et BLE(trame brute) → TCP(`>`…).
 */
object Framing {
    const val MARKER_TO_NODE: Byte = 0x3C   // '<'
    const val MARKER_FROM_NODE: Byte = 0x3E // '>'
    const val HEADER_SIZE = 3

    /** Valeur du firmware actuel (BaseSerialInterface.h). Marge x2 pour les évolutions futures. */
    const val FIRMWARE_MAX_FRAME_SIZE = 176
    const val MAX_FRAME_SIZE = 352

    /** Encode une trame venant du nœud pour un client TCP : `>` + len LE + payload. */
    fun encodeToClient(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "trame trop grande: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = MARKER_FROM_NODE
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Encode une trame destinée au nœud en format TCP : `<` + len LE + payload (utile aux tests / clients). */
    fun encodeToNode(payload: ByteArray): ByteArray {
        val out = encodeToClient(payload)
        out[0] = MARKER_TO_NODE
        return out
    }
}

/** Trame TCP invalide dont on ne peut pas se resynchroniser (le flux n'est plus fiable). */
class FrameException(message: String) : Exception(message)

/**
 * Décodeur incrémental des trames client → nœud (`<` + len LE + payload).
 *
 * Gère : fragmentation (en-tête ou corps coupé n'importe où), trames concaténées,
 * trames incomplètes (conservées jusqu'à l'arrivée de la suite), octets parasites entre
 * trames (ignorés jusqu'au prochain `<`, comme le firmware), longueur nulle (ignorée,
 * comme le firmware) et longueur > [maxFrameSize] ([FrameException] : on ne sait plus où
 * finit la trame, l'appelant doit fermer la connexion).
 *
 * Non thread-safe : un décodeur par connexion, utilisé depuis une seule coroutine.
 */
class TcpFrameDecoder(private val maxFrameSize: Int = Framing.MAX_FRAME_SIZE) {
    private enum class State { IDLE, LEN_LO, LEN_HI, BODY }

    private var state = State.IDLE
    private var lenLo = 0
    private var expected = 0
    private var body = ByteArray(0)
    private var bodyPos = 0

    /** Nombre d'octets parasites ignorés depuis la création (hors en-têtes/corps valides). */
    var discardedBytes: Long = 0
        private set

    /** Vrai si une trame est partiellement reçue. */
    val hasPartialFrame: Boolean get() = state != State.IDLE

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "bornes invalides" }
        var out: MutableList<ByteArray>? = null
        var i = offset
        val end = offset + length
        while (i < end) {
            when (state) {
                State.IDLE -> {
                    if (data[i] == Framing.MARKER_TO_NODE) state = State.LEN_LO else discardedBytes++
                    i++
                }
                State.LEN_LO -> {
                    lenLo = data[i].toInt() and 0xFF
                    state = State.LEN_HI
                    i++
                }
                State.LEN_HI -> {
                    expected = lenLo or ((data[i].toInt() and 0xFF) shl 8)
                    i++
                    when {
                        expected == 0 -> state = State.IDLE
                        expected > maxFrameSize -> {
                            val bad = expected
                            reset()
                            throw FrameException("longueur de trame invalide: $bad (max $maxFrameSize)")
                        }
                        else -> {
                            body = ByteArray(expected)
                            bodyPos = 0
                            state = State.BODY
                        }
                    }
                }
                State.BODY -> {
                    val n = minOf(expected - bodyPos, end - i)
                    System.arraycopy(data, i, body, bodyPos, n)
                    bodyPos += n
                    i += n
                    if (bodyPos == expected) {
                        (out ?: mutableListOf<ByteArray>().also { out = it }).add(body)
                        body = ByteArray(0)
                        state = State.IDLE
                    }
                }
            }
        }
        return out ?: emptyList()
    }

    fun reset() {
        state = State.IDLE
        expected = 0
        bodyPos = 0
        body = ByteArray(0)
    }
}
