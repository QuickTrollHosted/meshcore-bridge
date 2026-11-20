package fr.qth.meshcorebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Démarrage automatique optionnel. Chaque étape est journalisée dans `files/bridge.log`
 * pour pouvoir établir le comportement réel au boot sur l'appareil.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BridgeLog.init(context)
        val auto = Prefs.autostart(context)
        BridgeLog.i("BOOT_COMPLETED reçu (autostart=$auto, nœud=${Prefs.address(context)}, sdk=${Build.VERSION.SDK_INT}, fabricant=${Build.MANUFACTURER})")
        if (!auto || Prefs.address(context) == null) return
        try {
            BridgeService.start(context, "boot")
            BridgeLog.i("startForegroundService(boot) accepté par Android")
        } catch (e: Exception) {
            BridgeLog.i("startForegroundService(boot) REFUSÉ : ${e::class.java.simpleName} — ${e.message}")
            Alerts.relaunch(context, "Démarrage automatique impossible")
        }
    }
}
