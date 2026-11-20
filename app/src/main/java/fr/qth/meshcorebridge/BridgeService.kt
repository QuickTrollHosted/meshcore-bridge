package fr.qth.meshcorebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.qth.meshcorebridge.core.BridgeCounters
import fr.qth.meshcorebridge.core.BridgeEvents
import fr.qth.meshcorebridge.core.TcpBridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Service premier plan (connectedDevice) : BLE ↔ TCP 127.0.0.1:5000, un seul client. */
class BridgeService : Service() {
    companion object {
        const val ACTION_START = "fr.qth.meshcorebridge.START"
        const val ACTION_STOP = "fr.qth.meshcorebridge.STOP"
        const val EXTRA_ORIGIN = "origin"
        @Volatile var running = false

        fun start(c: Context, origin: String) = ContextCompat.startForegroundService(
            c, Intent(c, BridgeService::class.java).setAction(ACTION_START).putExtra(EXTRA_ORIGIN, origin),
        )

        fun stop(c: Context) { c.startService(Intent(c, BridgeService::class.java).setAction(ACTION_STOP)) }
    }

    private var scope: CoroutineScope? = null
    private var radio: BleRadio? = null
    private var server: TcpBridgeServer? = null
    private var counters = BridgeCounters()
    private var startedAt = 0L
    private var userStopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BridgeLog.init(this)
        if (intent?.action == ACTION_STOP) {
            userStopped = true
            BridgeLog.i("arrêt demandé par l'utilisateur")
            stopBridge()
            Prefs.setShouldRun(this, false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val origin = intent?.getStringExtra(EXTRA_ORIGIN)
            ?: "REDÉMARRAGE PAR LE SYSTÈME (le processus avait été tué, START_STICKY)"
        BridgeLog.i("service démarré — origine : $origin (sdk ${android.os.Build.VERSION.SDK_INT})")
        if (intent == null) {
            val hb = Prefs.lastHeartbeat(this)
            BridgeLog.i("⚠ le service a été tué puis relancé par Android (dernier signe de vie : ${if (hb > 0) java.util.Date(hb) else "inconnu"})")
        }

        Alerts.ensureChannels(this)
        try {
            ServiceCompat.startForeground(this, Alerts.ID_SERVICE, notification("Démarrage…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            BridgeLog.i("startForeground refusé : ${e::class.java.simpleName} — ${e.message}")
            Alerts.relaunch(this, "Démarrage automatique refusé par Android")
            Prefs.setShouldRun(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        val address = Prefs.address(this)
        if (address == null) {
            BridgeLog.i("aucun nœud sélectionné")
            stopSelf(); return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 31
        ) {
            BridgeLog.i("permission BLUETOOTH_CONNECT manquante")
            Alerts.relaunch(this, "Permission Bluetooth manquante")
            stopSelf(); return START_NOT_STICKY
        }

        if (running) return START_STICKY   // déjà actif : simple re-démarrage de l'intent
        Prefs.setShouldRun(this, true)
        startBridge(address)
        return START_STICKY
    }

    private fun startBridge(address: String) {
        running = true
        startedAt = System.currentTimeMillis()
        counters = BridgeCounters()
        BridgeState.reset()
        BridgeState.update { it.copy(serviceRunning = true) }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }

        val events = object : BridgeEvents {
            override fun log(message: String) = BridgeLog.i(message)
            override fun onClientChanged(remote: String?) {
                BridgeState.update { it.copy(clientRemote = remote) }
                refreshNotification()
            }
            override fun onFrame(toRadio: Boolean, length: Int, code: Int) {
                val dir = if (toRadio) "tcp→radio" else "radio→tcp"
                BridgeLog.frame("$dir $length o (code 0x%02X)".format(code and 0xFF))
                BridgeState.syncCounters(counters)
            }
        }
        val ble = BleRadio(this, address, { st, detail ->
            BridgeState.update { it.copy(ble = st, bleDetail = detail) }
            refreshNotification()
        }, BridgeLog::i).also { radio = it }

        try {
            val srv = TcpBridgeServer(Prefs.HOST, Prefs.PORT, ble, events, counters).also { server = it }
            srv.start()
        } catch (e: Exception) {
            BridgeLog.i("impossible d'ouvrir ${Prefs.HOST}:${Prefs.PORT} : ${e.message}")
            userStopped = true
            stopBridge(); Prefs.setShouldRun(this, false)
            Alerts.relaunch(this, "Port ${Prefs.PORT} indisponible")
            stopSelf(); return
        }
        ble.start()

        sc.launch {
            var n = 0
            while (true) {
                Prefs.setLastHeartbeat(this@BridgeService, System.currentTimeMillis())
                if (n % 5 == 0) {
                    val s = BridgeState.status.value
                    val up = (System.currentTimeMillis() - startedAt) / 60_000
                    BridgeLog.i("vivant depuis ${up} min — BLE ${s.ble.label}, client ${s.clientRemote ?: "aucun"}, ↑${s.bytesToRadio} o ↓${s.bytesFromRadio} o")
                }
                n++
                delay(60_000)
            }
        }
    }

    private fun stopBridge() {
        server?.stop(); server = null
        radio?.stop(); radio = null
        scope?.cancel(); scope = null
        running = false
        BridgeState.update { it.copy(serviceRunning = false, clientRemote = null) }
    }

    private fun refreshNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(Alerts.ID_SERVICE, notification(statusText()))
    }

    private fun statusText(): String {
        val s = BridgeState.status.value
        return "BLE : ${s.ble.label} · TCP ${Prefs.HOST}:${Prefs.PORT} · client : ${if (s.clientRemote != null) "oui" else "non"}"
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Alerts.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MeshCore Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Alerts.openAppIntent(this))
            .addAction(0, "Arrêter", stop)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        BridgeLog.i("onTaskRemoved : l'appli a été retirée des récentes (le service doit continuer)")
    }

    override fun onDestroy() {
        BridgeLog.i("onDestroy du service (arrêt utilisateur : $userStopped)")
        stopBridge()
        super.onDestroy()
    }
}
