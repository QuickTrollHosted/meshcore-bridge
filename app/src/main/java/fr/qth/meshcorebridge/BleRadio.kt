package fr.qth.meshcorebridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import fr.qth.meshcorebridge.core.RadioPort
import java.util.ArrayDeque
import java.util.UUID

val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // app → nœud
val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // nœud → app
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Lien BLE vers un nœud MeshCore compagnon (Nordic UART).
 * Une écriture / une notification GATT = une trame brute, sans en-tête.
 * Reconnexion automatique avec backoff (2 s → 30 s).
 */
@SuppressLint("MissingPermission")
class BleRadio(
    context: Context,
    private val address: String,
    private val onState: (BleState, String) -> Unit,
    private val log: (String) -> Unit,
) : RadioPort {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var stopped = true
    private var ready = false
    private var backoffMs = 2_000L

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false
    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    private val connectTimeout = Runnable {
        log("BLE : délai dépassé avant d'être prêt → nouvelle tentative")
        teardownAndRetry()
    }

    override fun setListener(listener: ((ByteArray) -> Unit)?) { this.listener = listener }

    fun start() { main.post { stopped = false; connect() } }

    fun stop() {
        main.post {
            stopped = true
            main.removeCallbacksAndMessages(null)
            closeGatt()
            onState(BleState.STOPPED, "")
        }
    }

    private fun adapter(): BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun connect() {
        if (stopped) return
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            onState(BleState.RETRY, "Bluetooth désactivé")
            log("BLE : Bluetooth désactivé, nouvel essai dans ${backoffMs / 1000}s")
            scheduleRetry(); return
        }
        val dev = try { ad.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            log("BLE : adresse invalide $address"); onState(BleState.STOPPED, "adresse invalide"); return
        }
        log("BLE : connexion à ${dev.name ?: address} (bond=${bondName(dev.bondState)})")
        onState(BleState.CONNECTING, dev.name ?: address)
        main.postDelayed(connectTimeout, 30_000)
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun bondName(s: Int) = when (s) { BluetoothDevice.BOND_BONDED -> "appairé"; BluetoothDevice.BOND_BONDING -> "en cours"; else -> "non appairé" }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        synchronized(lock) { ready = false; rx = null; writeQueue.clear(); writing = false }
        gatt?.let { try { it.disconnect() } catch (_: Exception) {}; try { it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private fun teardownAndRetry() {
        closeGatt()
        if (stopped) return
        onState(BleState.RETRY, "nouvel essai dans ${backoffMs / 1000}s")
        scheduleRetry()
    }

    private fun scheduleRetry() {
        val d = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        main.postDelayed({ connect() }, d)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    log("BLE : lien établi, demande de MTU 517")
                    if (!g.requestMtu(517)) { log("BLE : requestMtu refusé, découverte directe"); startDiscovery(g) }
                } else {
                    log("BLE : déconnecté (status=$status, état=$newState)")
                    teardownAndRetry()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
                log("BLE : MTU = $mtu (status=$status)")
                startDiscovery(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val svc = g.getService(NUS_SERVICE)
                val tx = svc?.getCharacteristic(NUS_TX)
                val rxc = svc?.getCharacteristic(NUS_RX)
                if (status != BluetoothGatt.GATT_SUCCESS || tx == null || rxc == null) {
                    log("BLE : service Nordic UART introuvable (status=$status) — firmware compagnon BLE ?")
                    teardownAndRetry(); return@post
                }
                rx = rxc
                g.setCharacteristicNotification(tx, true)
                val d = tx.getDescriptor(CCCD)
                if (d == null) { log("BLE : descripteur CCCD absent"); teardownAndRetry(); return@post }
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    }
                }
                if (!ok) { log("BLE : activation des notifications refusée"); teardownAndRetry() }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    main.removeCallbacks(connectTimeout)
                    backoffMs = 2_000
                    synchronized(lock) { ready = true }
                    log("BLE : notifications actives, lien prêt")
                    onState(BleState.READY, g.device.name ?: address)
                    pump()
                } else {
                    // 5/15 = authentification/chiffrement requis : l'appairage (PIN) vient d'être demandé.
                    log("BLE : écriture CCCD échouée (status=$status) — appairage requis ? PIN par défaut 123456")
                    teardownAndRetry()
                }
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") deliver(c, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("BLE : écriture échouée (status=$status)")
            synchronized(lock) { writing = false }
            pump()
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        onState(BleState.DISCOVERING, "MTU $mtu")
        if (!g.discoverServices()) { log("BLE : discoverServices refusé"); teardownAndRetry() }
    }

    private fun deliver(c: BluetoothGattCharacteristic, value: ByteArray?) {
        if (c.uuid != NUS_TX || value == null || value.isEmpty()) return
        listener?.invoke(value.copyOf())
    }

    override fun send(frame: ByteArray) {
        synchronized(lock) {
            if (!ready) { log("BLE : trame client ignorée (lien non prêt)"); return }
            if (writeQueue.size >= 256) { log("BLE : file d'écriture pleine, trame abandonnée"); return }
            writeQueue.add(frame)
        }
        pump()
    }

    /** Une seule écriture GATT en vol à la fois. */
    private fun pump() {
        val g = gatt ?: return
        val frame: ByteArray
        val ch: BluetoothGattCharacteristic
        synchronized(lock) {
            if (!ready || writing) return
            ch = rx ?: return
            frame = writeQueue.poll() ?: return
            writing = true
        }
        if (frame.size > mtu - 3) log("BLE : trame de ${frame.size} o > MTU-3 (${mtu - 3}) : l'écriture risque d'échouer")
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, frame, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = frame; g.writeCharacteristic(ch) }
        }
        if (!ok) {
            log("BLE : writeCharacteristic refusé")
            synchronized(lock) { writing = false }
        } else if (noResp) {
            synchronized(lock) { writing = false }
            main.post { pump() }
        }
    }
}
