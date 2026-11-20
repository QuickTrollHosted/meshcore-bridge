package fr.qth.meshcorebridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Compteurs d'octets / trames (payload seul, sans en-tête de framing). */
class BridgeCounters {
    val bytesToRadio = AtomicLong()
    val bytesFromRadio = AtomicLong()
    val framesToRadio = AtomicLong()
    val framesFromRadio = AtomicLong()
    val framesDroppedNoClient = AtomicLong()
}

interface BridgeEvents {
    fun log(message: String)
    fun onClientChanged(remote: String?)
    /** Appelé après chaque trame échangée ; [code] = premier octet (type de paquet), jamais le contenu. */
    fun onFrame(toRadio: Boolean, length: Int, code: Int)
}

/**
 * Serveur TCP du pont. Phase 0 : **un seul client à la fois**.
 *
 *  - Un second client est refusé (fermé aussitôt) tant que le premier est connecté.
 *  - Un client lent ne bloque ni la radio ni la boucle d'acceptation : les trames radio passent par
 *    une file bornée ; si elle déborde, le client lent est déconnecté.
 *  - Les trames venant du nœud sans client connecté sont abandonnées (le protocole n'est pas conçu
 *    pour être mis en tampon : une réponse tardive serait attribuée au mauvais échange).
 */
class TcpBridgeServer(
    private val host: String,
    private val port: Int,
    private val radio: RadioPort,
    private val events: BridgeEvents,
    private val counters: BridgeCounters = BridgeCounters(),
    private val clientQueueCapacity: Int = 128,
) {
    // Pool dédié à l'E/S bloquante : un thread par boucle (accept / lecture / écriture), jamais de famine
    // possible comme avec le Dispatchers.IO partagé (64 threads max, déjà utilisé par le reste de l'appli).
    private val ioPool = Executors.newCachedThreadPool { r -> Thread(r, "bridge-io").apply { isDaemon = true } }
    private val io = ioPool.asCoroutineDispatcher()

    // SupervisorJob : l'échec d'un client ne doit jamais annuler la boucle d'acceptation ni les autres clients.
    private val serverScope = CoroutineScope(SupervisorJob() + io)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val activeClient = AtomicReference<ClientConn?>(null)

    /** Port réellement ouvert (utile quand `port = 0` dans les tests). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(host), port))
        server = ss
        events.log("TCP à l'écoute sur $host:${ss.localPort}")
        radio.setListener(::onRadioFrame)
        acceptJob = serverScope.launch { acceptLoop(ss) }
    }

    fun stop() {
        radio.setListener(null)
        serverScope.cancel()
        try { server?.close() } catch (_: IOException) {}
        server = null
        activeClient.getAndSet(null)?.close("arrêt du serveur")
        io.close()
        ioPool.shutdown()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (currentCoroutineContext().isActive && !ss.isClosed) {
            val sock = try {
                ss.accept()
            } catch (e: IOException) {
                if (!ss.isClosed) events.log("accept() a échoué: ${e.message}")
                return
            }
            val remote = sock.remoteSocketAddress.toString().trimStart('/')
            if (activeClient.get() != null) {
                events.log("client $remote refusé : un client est déjà connecté (mode un seul client)")
                try { sock.close() } catch (_: IOException) {}
                continue
            }
            val conn = ClientConn(sock, remote)
            if (!activeClient.compareAndSet(null, conn)) {
                try { sock.close() } catch (_: IOException) {}
                continue
            }
            conn.start()
        }
    }

    /** Appelé depuis le thread BLE : ne doit jamais bloquer. */
    private fun onRadioFrame(frame: ByteArray) {
        counters.framesFromRadio.incrementAndGet()
        counters.bytesFromRadio.addAndGet(frame.size.toLong())
        events.onFrame(false, frame.size, if (frame.isNotEmpty()) frame[0].toInt() and 0xFF else -1)
        val c = activeClient.get()
        if (c == null) {
            counters.framesDroppedNoClient.incrementAndGet()
            return
        }
        if (!c.outbound.trySend(Framing.encodeToClient(frame)).isSuccess) {
            c.close("client trop lent (file pleine)")
        }
    }

    private inner class ClientConn(private val sock: Socket, val remote: String) {
        val outbound = Channel<ByteArray>(clientQueueCapacity)
        private var job: Job? = null
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        fun start() {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            events.log("client connecté : $remote")
            events.onClientChanged(remote)
            job = serverScope.launch {
                val writer = launch { writeLoop() }
                try {
                    readLoop()
                } finally {
                    close("fin de lecture")
                    writer.cancel()
                }
            }
        }

        private suspend fun readLoop() {
            val dec = TcpFrameDecoder()
            val buf = ByteArray(1024)
            val input = try { sock.getInputStream() } catch (e: IOException) { return }  // socket déjà fermée
            while (currentCoroutineContext().isActive) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    return
                }
                if (n < 0) return
                try {
                    for (f in dec.feed(buf, 0, n)) {
                        counters.framesToRadio.incrementAndGet()
                        counters.bytesToRadio.addAndGet(f.size.toLong())
                        events.onFrame(true, f.size, f[0].toInt() and 0xFF)
                        radio.send(f)
                    }
                } catch (e: FrameException) {
                    events.log("trame client invalide : ${e.message} → déconnexion")
                    return
                }
            }
        }

        private suspend fun writeLoop() {
            try {
                val out = sock.getOutputStream()
                for (data in outbound) {
                    out.write(data); out.flush()
                }
            } catch (e: IOException) {
                close("écriture impossible : ${e.message}")
            } catch (_: CancellationException) {
            }
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            try { sock.close() } catch (_: IOException) {}
            outbound.close()
            activeClient.compareAndSet(this, null)
            events.log("client déconnecté : $remote ($reason)")
            events.onClientChanged(null)
        }
    }
}
