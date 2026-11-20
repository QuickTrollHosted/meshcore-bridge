package fr.qth.meshcorebridge.core

/**
 * Lien vers le nœud (BLE en production, faux nœud dans les tests).
 * Les trames échangées sont brutes : sans en-tête de framing TCP.
 */
interface RadioPort {
    /** Envoie une trame au nœud. Ne doit pas bloquer le thread appelant. */
    fun send(frame: ByteArray)

    /** Définit (ou retire avec null) le récepteur des trames venant du nœud. */
    fun setListener(listener: ((ByteArray) -> Unit)?)
}
