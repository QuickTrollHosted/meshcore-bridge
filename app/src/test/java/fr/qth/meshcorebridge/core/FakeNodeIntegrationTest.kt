package fr.qth.meshcorebridge.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Test d'intégration sans matériel : un faux nœud MeshCore (répond comme un firmware compagnon, de façon
 * asynchrone, sur un autre thread comme le ferait la pile BLE) derrière le vrai serveur TCP, avec de vrais
 * sockets et le framing réel dans les deux sens.
 */
private class FakeMeshNode : RadioPort {
    private val ble = Executors.newSingleThreadExecutor()
    @Volatile private var listener: ((ByteArray) -> Unit)? = null
    override fun setListener(listener: ((ByteArray) -> Unit)?) { this.listener = listener }
    override fun send(frame: ByteArray) {
        val reply = when (frame[0].toInt()) {
            0x01 -> byteArrayOf(0x05, 0x01, 0x16) + "FakeNode".toByteArray()   // APP_START → SELF_INFO
            0x16 -> byteArrayOf(0x0D, 0x03) + ByteArray(8)                       // DEVICE_QUERY → DEVICE_INFO
            0x05 -> byteArrayOf(0x0C) + ByteArray(4)                              // GET_TIME → CURR_TIME
            else -> byteArrayOf(0x01, 0x01)                                       // ERR (commande inconnue)
        }
        ble.execute { Thread.sleep(5); listener?.invoke(reply) }                 // latence radio simulée
    }
    fun shutdown() = ble.shutdownNow()
}

class FakeNodeIntegrationTest {
    private val node = FakeMeshNode()
    private val events = TestEvents()
    private lateinit var server: TcpBridgeServer
    private lateinit var scope: CoroutineScope

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { server.stop(); node.shutdown(); scope.cancel() }

    private fun start(mode: ClientMode) {
        server = TcpBridgeServer("127.0.0.1", 0, mode, node, events).also { it.start() }
    }

    private fun Socket.send(vararg payload: Int) = getOutputStream().write(Framing.encodeToNode(ByteArray(payload.size) { payload[it].toByte() }))

    @Test fun handshakeLikeARealClient_singleMode() {
        start(ClientMode.SINGLE)
        Socket("127.0.0.1", server.boundPort).use { s ->
            s.soTimeout = 3000
            s.send(0x01, 0, 0, 0, 0, 0, 0, 0, 't'.code, 'c'.code, 'p'.code)
            assertEquals(0x05, s.readFrame()[0].toInt())
            s.send(0x16, 0x03)
            assertEquals(0x0D, s.readFrame()[0].toInt())
            // deux commandes dans un seul segment TCP : traitées l'une après l'autre
            s.getOutputStream().write(Framing.encodeToNode(byteArrayOf(0x05)) + Framing.encodeToNode(byteArrayOf(0x16, 0x03)))
            val codes = setOf(s.readFrame()[0].toInt(), s.readFrame()[0].toInt())
            assertEquals(setOf(0x0C, 0x0D), codes)
        }
    }

    @Test fun twoClientsMultiMode_bothSeeEveryResponse() {
        start(ClientMode.MULTI)
        Socket("127.0.0.1", server.boundPort).use { a -> Socket("127.0.0.1", server.boundPort).use { b ->
            a.soTimeout = 3000; b.soTimeout = 3000
            events.waitClients(2)
            a.send(0x16, 0x03)                       // A pose la question…
            assertEquals(0x0D, a.readFrame()[0].toInt())
            assertEquals(0x0D, b.readFrame()[0].toInt())   // …B reçoit aussi la réponse : limite documentée (diffusion)
            b.send(0x05)
            assertEquals(0x0C, b.readFrame()[0].toInt())
            assertEquals(0x0C, a.readFrame()[0].toInt())
        } }
    }
}
