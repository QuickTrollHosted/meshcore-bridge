package fr.qth.meshcorebridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    /** Liste à jour des clients connectés (adresse:port distants). */
    fun onClientsChanged(clients: List<String>)
    /** Appelé après chaque trame échangée ; [code] = premier octet (type de paquet), jamais le contenu. */
    fun onFrame(toRadio: Boolean, length: Int, code: Int)
}

enum class ClientMode {
    /** Un seul client à la fois ; les suivants sont refusés. Comportement le plus fidèle au protocole. */
    SINGLE,

    /**
     * Plusieurs clients : toutes les trames du nœud sont **diffusées** à tous les clients, et les trames de
     * tous les clients sont **sérialisées** dans une file unique vers le nœud. Le protocole n'identifie pas
     * le client : chaque client voit aussi les réponses destinées aux autres.
     */
    MULTI,
}

/**
 * Serveur TCP du pont.
 *
 *  - Un client lent ne bloque ni la radio ni les autres clients : les trames radio passent par une file
 *    bornée par client ; si elle déborde, ce client (et lui seul) est déconnecté.
 *  - Les trames venant du nœud sans client connecté sont abandonnées (le protocole n'est pas conçu pour
 *    être mis en tampon : une réponse tardive serait attribuée au mauvais échange).
 *  - Les trames des clients passent par une file unique : l'ordre d'arrivée est préservé et la radio ne
 *    reçoit jamais deux trames entremêlées.
 */
class TcpBridgeServer(
    private val host: String,
    private val port: Int,
    private val mode: ClientMode,
    private val radio: RadioPort,
    private val events: BridgeEvents,
    private val counters: BridgeCounters = BridgeCounters(),
    private val clientQueueCapacity: Int = 128,
    private val maxClients: Int = 8,
) {
    // Pool dédié à l'E/S bloquante (jamais de famine du Dispatchers.IO partagé).
    private val ioPool = Executors.newCachedThreadPool { r -> Thread(r, "bridge-io").apply { isDaemon = true } }
    private val io = ioPool.asCoroutineDispatcher()

    // SupervisorJob : l'échec d'un client ne doit jamais annuler la boucle d'acceptation ni les autres clients.
    private val serverScope = CoroutineScope(SupervisorJob() + io)

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConn>()
    private val lock = Any()
    private val toRadio = Channel<ByteArray>(512)

    /** Port réellement ouvert (utile quand `port = 0` dans les tests). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(host), port))
        server = ss
        events.log("TCP à l'écoute sur $host:${ss.localPort} (mode ${if (mode == ClientMode.MULTI) "multi-clients" else "un seul client"})")
        radio.setListener(::onRadioFrame)
        serverScope.launch { for (f in toRadio) radio.send(f) }   // file unique vers la radio
        serverScope.launch { acceptLoop(ss) }
    }

    fun stop() {
        radio.setListener(null)
        serverScope.cancel()
        try { server?.close() } catch (_: IOException) {}
        server = null
        toRadio.close()
        clients.toList().forEach { it.close("arrêt du serveur") }
        io.close()
        ioPool.shutdown()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        val limit = if (mode == ClientMode.SINGLE) 1 else maxClients
        while (currentCoroutineContext().isActive && !ss.isClosed) {
            val sock = try {
                ss.accept()
            } catch (e: IOException) {
                if (!ss.isClosed) events.log("accept() a échoué: ${e.message}")
                return
            }
            val remote = sock.remoteSocketAddress.toString().trimStart('/')
            val conn = ClientConn(sock, remote)
            val accepted = synchronized(lock) { if (clients.size < limit) { clients.add(conn); true } else false }
            if (!accepted) {
                events.log(
                    if (mode == ClientMode.SINGLE) "client $remote refusé : un client est déjà connecté (mode un seul client)"
                    else "client $remote refusé : maximum de $maxClients clients atteint",
                )
                try { sock.close() } catch (_: IOException) {}
                continue
            }
            conn.start()
        }
    }

    private fun fireClientsChanged() = events.onClientsChanged(clients.map { it.remote })

    /** Appelé depuis le thread BLE : ne doit jamais bloquer. */
    private fun onRadioFrame(frame: ByteArray) {
        counters.framesFromRadio.incrementAndGet()
        counters.bytesFromRadio.addAndGet(frame.size.toLong())
        events.onFrame(false, frame.size, if (frame.isNotEmpty()) frame[0].toInt() and 0xFF else -1)
        val snapshot = clients.toList()
        if (snapshot.isEmpty()) {
            counters.framesDroppedNoClient.incrementAndGet()
            return
        }
        val encoded = Framing.encodeToClient(frame)   // encodé une seule fois, diffusé à tous
        for (c in snapshot) {
            if (!c.outbound.trySend(encoded).isSuccess) c.close("client trop lent (file pleine)")
        }
    }

    private inner class ClientConn(private val sock: Socket, val remote: String) {
        val outbound = Channel<ByteArray>(clientQueueCapacity)
        private val closed = AtomicBoolean(false)

        fun start() {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            events.log("client connecté : $remote")
            fireClientsChanged()
            serverScope.launch {
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
                val n = try { input.read(buf) } catch (e: IOException) { return }
                if (n < 0) return
                try {
                    for (f in dec.feed(buf, 0, n)) {
                        counters.framesToRadio.incrementAndGet()
                        counters.bytesToRadio.addAndGet(f.size.toLong())
                        events.onFrame(true, f.size, f[0].toInt() and 0xFF)
                        toRadio.send(f)   // file unique ; seul ce client attend si la radio est lente
                    }
                } catch (e: FrameException) {
                    events.log("trame client invalide ($remote) : ${e.message} → déconnexion")
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return   // file radio fermée (arrêt)
                }
            }
        }

        private suspend fun writeLoop() {
            try {
                val out = sock.getOutputStream()
                for (data in outbound) { out.write(data); out.flush() }
            } catch (e: IOException) {
                close("écriture impossible : ${e.message}")
            } catch (_: CancellationException) {
            }
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            try { sock.close() } catch (_: IOException) {}
            outbound.close()
            synchronized(lock) { clients.remove(this) }
            events.log("client déconnecté : $remote ($reason)")
            fireClientsChanged()
        }
    }
}
