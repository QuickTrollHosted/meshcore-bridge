package fr.qth.meshcorebridge

import android.content.Context

/** Réglages persistants (SharedPreferences). */
object Prefs {
    const val PORT = 5000
    const val HOST = "127.0.0.1"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun address(c: Context): String? = sp(c).getString("address", null)
    fun name(c: Context): String? = sp(c).getString("name", null)
    fun setDevice(c: Context, address: String, name: String?) =
        sp(c).edit().putString("address", address).putString("name", name).apply()

    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutostart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    /** Vrai tant que l'utilisateur n'a pas explicitement arrêté le service : sert à détecter un arrêt inattendu. */
    fun shouldRun(c: Context): Boolean = sp(c).getBoolean("should_run", false)
    fun setShouldRun(c: Context, v: Boolean) = sp(c).edit().putBoolean("should_run", v).commit().let { }

    fun lastHeartbeat(c: Context): Long = sp(c).getLong("heartbeat", 0)
    fun setLastHeartbeat(c: Context, t: Long) = sp(c).edit().putLong("heartbeat", t).apply()
}
