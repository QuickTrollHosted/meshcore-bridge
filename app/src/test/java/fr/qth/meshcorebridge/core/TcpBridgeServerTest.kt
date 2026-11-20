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
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Faux nœud : enregistre ce que le pont lui envoie, et permet d'injecter des trames « radio ». */
class FakeRadio : RadioPort {
    val sent = LinkedBlockingQueue<ByteArray>()
    @Volatile var rxListener: ((ByteArray) -> Unit)? = null
    override fun send(frame: ByteArray) { sent.add(frame) }
    override fun setListener(listener: ((ByteArray) -> Unit)?) { rxListener = listener }
    fun emit(frame: ByteArray) { rxListener?.invoke(frame) }
}

class TestEvents : BridgeEvents {
    val logs = CopyOnWriteArrayList<String>()
    val clientLists = LinkedBlockingQueue<List<String>>()
    override fun log(message: String) { logs.add(message) }
    override fun onClientsChanged(clients: List<String>) { clientLists.add(clients) }
    override fun onFrame(toRadio: Boolean, length: Int, code: Int) {}

    /** Attend qu'une notification indique exactement [n] clients. */
    fun waitClients(n: Int) {
        val end = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < end) {
            val l = clientLists.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (l.size == n) return
        }
        error("jamais vu $n client(s) ; journal=$logs")
    }
}

fun InputStream.readExactly(n: Int): ByteArray {
    val out = ByteArray(n); var o = 0
    while (o < n) { val r = read(out, o, n - o); if (r < 0) error("EOF"); o += r }
    return out
}

/** Lit une trame `>` + len + payload côté client. */
fun Socket.readFrame(): ByteArray {
    val h = getInputStream().readExactly(3)
    check(h[0] == Framing.MARKER_FROM_NODE) { "marqueur ${h[0]}" }
    return getInputStream().readExactly((h[1].toInt() and 0xFF) or ((h[2].toInt() and 0xFF) shl 8))
}

class TcpBridgeServerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var radio: FakeRadio
    private lateinit var events: TestEvents
    private var server: TcpBridgeServer? = null
    private val counters = BridgeCounters()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        radio = FakeRadio(); events = TestEvents()
    }

    @After fun tearDown() { server?.stop(); scope.cancel() }

    private fun startServer(mode: ClientMode = ClientMode.SINGLE, maxClients: Int = 8) {
        server = TcpBridgeServer("127.0.0.1", 0, mode, radio, events, counters, clientQueueCapacity = 16, maxClients = maxClients).also { it.start() }
    }

    private fun connect(): Socket = Socket("127.0.0.1", server!!.boundPort).also { it.soTimeout = 3000 }

    // ---------------- mode un seul client ----------------

    @Test fun tcpToRadio_stripsHeader() {
        startServer()
        connect().use { s ->
            events.waitClients(1)
            s.getOutputStream().write(Framing.encodeToNode(byteArrayOf(0x16, 0x03)))
            assertArrayEquals(byteArrayOf(0x16, 0x03), radio.sent.poll(3, TimeUnit.SECONDS)!!)
        }
    }

    @Test fun tcpToRadio_coalescedAndFragmentedWrites() {
        startServer()
        connect().use { s ->
            events.waitClients(1)
            val wire = Framing.encodeToNode(byteArrayOf(1, 2)) + Framing.encodeToNode(byteArrayOf(3))
            val out = s.getOutputStream()
            out.write(wire, 0, 2); out.flush(); Thread.sleep(30)
            out.write(wire, 2, wire.size - 2); out.flush()
            assertArrayEquals(byteArrayOf(1, 2), radio.sent.poll(3, TimeUnit.SECONDS)!!)
            assertArrayEquals(byteArrayOf(3), radio.sent.poll(3, TimeUnit.SECONDS)!!)
        }
    }

    @Test fun radioToTcp_addsHeader() {
        startServer()
        connect().use { s ->
            events.waitClients(1)
            radio.emit(byteArrayOf(0x05, 0x0A, 0x0B))
            assertArrayEquals(byteArrayOf(0x3E, 3, 0, 0x05, 0x0A, 0x0B), s.getInputStream().readExactly(6))
        }
        assertEquals(1, counters.framesFromRadio.get())
        assertEquals(3, counters.bytesFromRadio.get())
    }

    @Test fun radioFrameWithoutClient_isDropped() {
        startServer()
        radio.emit(byteArrayOf(0x01))
        assertEquals(1, counters.framesDroppedNoClient.get())
    }

    @Test fun single_secondClientIsRejected_firstKeepsWorking() {
        startServer(ClientMode.SINGLE)
        connect().use { first ->
            events.waitClients(1)
            connect().use { second -> assertEquals(-1, second.getInputStream().read()) }
            radio.emit(byteArrayOf(0x07))
            assertArrayEquals(byteArrayOf(0x3E, 1, 0, 0x07), first.getInputStream().readExactly(4))
        }
        assertTrue(events.logs.any { it.contains("refusé") })
    }

    @Test fun clientCanReconnectAfterDisconnect() {
        startServer()
        connect().use { events.waitClients(1) }
        events.waitClients(0)
        connect().use { s ->
            events.waitClients(1)
            radio.emit(byteArrayOf(0x09))
            assertArrayEquals(byteArrayOf(0x3E, 1, 0, 0x09), s.getInputStream().readExactly(4))
        }
    }

    @Test fun invalidLength_disconnectsClient() {
        startServer()
        connect().use { s ->
            events.waitClients(1)
            s.getOutputStream().write(byteArrayOf(0x3C, 0xFF.toByte(), 0xFF.toByte()))
            assertEquals(-1, s.getInputStream().read())
        }
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun slowClient_neverBlocksRadioCallback() {
        startServer()
        connect().use {
            events.waitClients(1)
            val big = ByteArray(170) { 1 }
            val t0 = System.nanoTime()
            repeat(20_000) { radio.emit(big) }   // le client ne lit jamais
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("la radio a été bloquée ($ms ms)", ms < 5_000)
        }
    }

    // ---------------- mode multi-clients ----------------

    @Test fun multi_radioFramesAreBroadcastToAllClients() {
        startServer(ClientMode.MULTI)
        connect().use { a -> connect().use { b ->
            events.waitClients(2)
            radio.emit(byteArrayOf(0x05, 0x01))
            assertArrayEquals(byteArrayOf(0x05, 0x01), a.readFrame())
            assertArrayEquals(byteArrayOf(0x05, 0x01), b.readFrame())
        } }
    }

    @Test fun multi_clientFramesAreSerializedIntoOneQueue_noInterleaving() {
        startServer(ClientMode.MULTI)
        val perClient = 200
        connect().use { a -> connect().use { b ->
            events.waitClients(2)
            val go = CountDownLatch(1)
            fun sender(s: Socket, id: Int) = thread {
                go.await()
                val out = s.getOutputStream()
                for (i in 0 until perClient) out.write(Framing.encodeToNode(byteArrayOf(id.toByte(), (i and 0xFF).toByte(), (i shr 8).toByte(), 0x55, 0x66)))
                out.flush()
            }
            val ta = sender(a, 1); val tb = sender(b, 2)
            go.countDown(); ta.join(); tb.join()
            val lastSeen = mutableMapOf(1 to -1, 2 to -1)
            repeat(2 * perClient) {
                val f = radio.sent.poll(5, TimeUnit.SECONDS) ?: error("trame manquante après $it")
                assertEquals("trame corrompue/entremêlée", 5, f.size)
                assertEquals(0x55, f[3].toInt()); assertEquals(0x66, f[4].toInt())
                val id = f[0].toInt(); val n = (f[1].toInt() and 0xFF) or ((f[2].toInt() and 0xFF) shl 8)
                assertTrue("ordre d'un même client non préservé", n == lastSeen[id]!! + 1)
                lastSeen[id] = n
            }
            assertEquals(perClient - 1, lastSeen[1]); assertEquals(perClient - 1, lastSeen[2])
        } }
    }

    @Test fun multi_oneClientLeaving_doesNotAffectTheOther() {
        startServer(ClientMode.MULTI)
        connect().use { b ->
            connect().use { events.waitClients(2) }
            events.waitClients(1)
            radio.emit(byteArrayOf(0x0A))
            assertArrayEquals(byteArrayOf(0x0A), b.readFrame())
        }
    }

    @Test fun multi_badClientIsDisconnected_othersKeepWorking() {
        startServer(ClientMode.MULTI)
        connect().use { good -> connect().use { bad ->
            events.waitClients(2)
            bad.getOutputStream().write(byteArrayOf(0x3C, 0xFF.toByte(), 0xFF.toByte()))
            assertEquals(-1, bad.getInputStream().read())
            events.waitClients(1)
            radio.emit(byteArrayOf(0x0B))
            assertArrayEquals(byteArrayOf(0x0B), good.readFrame())
        } }
    }

    @Test fun multi_maxClientsIsEnforced() {
        startServer(ClientMode.MULTI, maxClients = 2)
        connect().use { connect().use {
            events.waitClients(2)
            connect().use { third -> assertEquals(-1, third.getInputStream().read()) }
        } }
        assertTrue(events.logs.any { it.contains("maximum de 2 clients") })
    }
}
