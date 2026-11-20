package fr.qth.meshcorebridge.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Faux nœud : enregistre ce que le pont lui envoie, et permet d'injecter des trames « radio ». */
private class FakeRadio : RadioPort {
    val sent = LinkedBlockingQueue<ByteArray>()
    @Volatile var rxListener: ((ByteArray) -> Unit)? = null
    override fun send(frame: ByteArray) { sent.add(frame) }
    override fun setListener(listener: ((ByteArray) -> Unit)?) { rxListener = listener }
    fun emit(frame: ByteArray) { rxListener?.invoke(frame) }
}

private class TestEvents : BridgeEvents {
    val logs = CopyOnWriteArrayList<String>()
    val clients = LinkedBlockingQueue<String>()
    override fun log(message: String) { logs.add(message) }
    override fun onClientChanged(remote: String?) { clients.add(remote ?: "none") }
    override fun onFrame(toRadio: Boolean, length: Int, code: Int) {}
}

class TcpBridgeServerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var radio: FakeRadio
    private lateinit var events: TestEvents
    private lateinit var server: TcpBridgeServer
    private val counters = BridgeCounters()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        radio = FakeRadio()
        events = TestEvents()
        server = TcpBridgeServer("127.0.0.1", 0, radio, events, counters, clientQueueCapacity = 16)
        server.start()
    }

    @After fun tearDown() { server.stop(); scope.cancel() }

    private fun connect(): Socket = Socket("127.0.0.1", server.boundPort).also { it.soTimeout = 3000 }

    private fun Socket.readExactly(n: Int): ByteArray {
        val out = ByteArray(n); var o = 0
        while (o < n) { val r = getInputStream().read(out, o, n - o); if (r < 0) error("EOF"); o += r }
        return out
    }

    private fun waitClient(expected: String) {
        val got = events.clients.poll(3, TimeUnit.SECONDS)
        assertTrue("attendu $expected, reçu $got", got != null && (expected == "any" && got != "none" || got == expected))
    }

    @Test fun tcpToRadio_stripsHeader() {
        connect().use { s ->
            waitClient("any")
            s.getOutputStream().write(Framing.encodeToNode(byteArrayOf(0x16, 0x03)))
            val f = radio.sent.poll(3, TimeUnit.SECONDS)!!
            assertArrayEquals(byteArrayOf(0x16, 0x03), f)
        }
    }

    @Test fun tcpToRadio_coalescedAndFragmentedWrites() {
        connect().use { s ->
            waitClient("any")
            val wire = Framing.encodeToNode(byteArrayOf(1, 2)) + Framing.encodeToNode(byteArrayOf(3))
            val out = s.getOutputStream()
            out.write(wire, 0, 2); out.flush(); Thread.sleep(30)
            out.write(wire, 2, wire.size - 2); out.flush()
            assertArrayEquals(byteArrayOf(1, 2), radio.sent.poll(3, TimeUnit.SECONDS)!!)
            assertArrayEquals(byteArrayOf(3), radio.sent.poll(3, TimeUnit.SECONDS)!!)
        }
    }

    @Test fun radioToTcp_addsHeader() {
        connect().use { s ->
            waitClient("any")
            radio.emit(byteArrayOf(0x05, 0x0A, 0x0B))
            assertArrayEquals(byteArrayOf(0x3E, 3, 0, 0x05, 0x0A, 0x0B), s.readExactly(6))
        }
        assertEquals(1, counters.framesFromRadio.get())
        assertEquals(3, counters.bytesFromRadio.get())
    }

    @Test fun radioFrameWithoutClient_isDropped() {
        radio.emit(byteArrayOf(0x01))
        assertEquals(1, counters.framesDroppedNoClient.get())
    }

    @Test fun secondClientIsRejected_firstKeepsWorking() {
        connect().use { first ->
            waitClient("any")
            connect().use { second ->
                // le second est fermé par le serveur
                assertEquals(-1, second.getInputStream().read())
            }
            radio.emit(byteArrayOf(0x07))
            assertArrayEquals(byteArrayOf(0x3E, 1, 0, 0x07), first.readExactly(4))
        }
        assertTrue(events.logs.any { it.contains("refusé") })
    }

    @Test fun clientCanReconnectAfterDisconnect() {
        connect().use { waitClient("any") }
        assertEquals("none", events.clients.poll(3, TimeUnit.SECONDS))
        connect().use { s ->
            waitClient("any")
            radio.emit(byteArrayOf(0x09))
            assertArrayEquals(byteArrayOf(0x3E, 1, 0, 0x09), s.readExactly(4))
        }
    }

    @Test fun invalidLength_disconnectsClient() {
        connect().use { s ->
            waitClient("any")
            s.getOutputStream().write(byteArrayOf(0x3C, 0xFF.toByte(), 0xFF.toByte()))
            assertEquals(-1, s.getInputStream().read())
        }
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun slowClient_neverBlocksRadioCallback() {
        connect().use { s ->
            waitClient("any")
            // Le client ne lit jamais : on inonde. L'émission radio doit rester non bloquante.
            val big = ByteArray(170) { 1 }
            val t0 = System.nanoTime()
            repeat(20_000) { radio.emit(big) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("la radio a été bloquée ($ms ms)", ms < 5_000)
        }
    }
}
