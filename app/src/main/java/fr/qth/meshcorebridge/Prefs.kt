package fr.qth.meshcorebridge

import android.content.Context
import androidx.core.content.edit
import fr.qth.meshcorebridge.core.ClientMode

/** Réglages persistants (SharedPreferences). */
object Prefs {
    const val DEFAULT_PORT = 5000
    const val LOOPBACK = "127.0.0.1"
    const val ALL_INTERFACES = "0.0.0.0"
    const val DEFAULT_PIN = "123456"   // PIN d'appairage par défaut du firmware MeshCore

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun address(c: Context): String? = sp(c).getString("address", null)
    fun name(c: Context): String? = sp(c).getString("name", null)
    fun setDevice(c: Context, address: String, name: String?) = sp(c).edit { putString("address", address); putString("name", name) }

    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutostart(c: Context, v: Boolean) = sp(c).edit { putBoolean("autostart", v) }

    fun port(c: Context): Int = sp(c).getInt("port", DEFAULT_PORT)
    fun setPort(c: Context, v: Int) = sp(c).edit { putInt("port", v) }

    /** Écoute sur toutes les interfaces (LAN). Désactivé par défaut : seul le loopback est exposé. */
    fun listenAll(c: Context): Boolean = sp(c).getBoolean("listen_all", false)
    fun setListenAll(c: Context, v: Boolean) = sp(c).edit { putBoolean("listen_all", v) }

    fun host(c: Context): String = if (listenAll(c)) ALL_INTERFACES else LOOPBACK

    fun multiClient(c: Context): Boolean = sp(c).getBoolean("multi", true)
    fun setMultiClient(c: Context, v: Boolean) = sp(c).edit { putBoolean("multi", v) }
    fun mode(c: Context): ClientMode = if (multiClient(c)) ClientMode.MULTI else ClientMode.SINGLE

    fun pin(c: Context): String = sp(c).getString("pin", DEFAULT_PIN) ?: DEFAULT_PIN
    fun setPin(c: Context, v: String) = sp(c).edit { putString("pin", v) }
    fun autoPin(c: Context): Boolean = sp(c).getBoolean("auto_pin", true)
    fun setAutoPin(c: Context, v: Boolean) = sp(c).edit { putBoolean("auto_pin", v) }

    /** Vrai tant que l'utilisateur n'a pas explicitement arrêté le service : sert à détecter un arrêt inattendu. */
    fun shouldRun(c: Context): Boolean = sp(c).getBoolean("should_run", false)
    fun setShouldRun(c: Context, v: Boolean) { sp(c).edit(commit = true) { putBoolean("should_run", v) } }

    fun lastHeartbeat(c: Context): Long = sp(c).getLong("heartbeat", 0)
    fun setLastHeartbeat(c: Context, t: Long) = sp(c).edit { putLong("heartbeat", t) }
}
