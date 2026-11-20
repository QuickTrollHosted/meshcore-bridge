package fr.qth.meshcorebridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Permission `ACCESS_LOCAL_NETWORK` (Android 17 / API 37) : exigée pour le trafic vers et depuis le LAN
 * par les applis ciblant l'API 37. Le loopback n'est pas concerné. Avant l'API 37 elle n'existe pas.
 * L'appli cible l'API 36 (non appliquée) mais la demande dès l'API 37 pour rester prête.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun isGranted(c: Context): Boolean =
        !required || ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Adresses IPv4 non loopback du téléphone (pour afficher où joindre le relais). */
    fun ipv4Addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) { emptyList() }
}
