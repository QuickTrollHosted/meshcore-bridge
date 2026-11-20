package fr.qth.meshcorebridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Alerts {
    const val CH_SERVICE = "bridge"
    const val CH_ALERT = "bridge_alert"
    const val ID_SERVICE = 1
    const val ID_ALERT = 2

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Service de relais", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertes", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openAppIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notification « le relais est arrêté, touchez pour le relancer ». */
    fun relaunch(c: Context, reason: String) {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            BridgeLog.i("alerte non affichée (POST_NOTIFICATIONS refusé) : $reason"); return
        }
        ensureChannels(c)
        val n = NotificationCompat.Builder(c, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("MeshCore Bridge arrêté")
            .setContentText("$reason — touchez pour relancer le relais")
            .setContentIntent(openAppIntent(c))
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
    }

    /** Détecte un arrêt non demandé : le service devait tourner mais ne tourne plus. */
    fun checkUnexpectedStop(c: Context) {
        if (Prefs.shouldRun(c) && !BridgeService.running) {
            val hb = Prefs.lastHeartbeat(c)
            val last = if (hb > 0) java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date(hb)) else "inconnu"
            BridgeLog.i("⚠ service arrêté de façon inattendue (dernier signe de vie : $last)")
            relaunch(c, "Arrêt inattendu (dernier signe de vie $last)")
        }
    }
}
