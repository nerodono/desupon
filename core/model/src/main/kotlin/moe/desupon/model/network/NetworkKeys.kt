package moe.desupon.model.network

import java.security.MessageDigest
import java.util.Locale

/** Permission-free network identities that survive reconnects, unlike `Network` handles; no SSIDs or location. */
object NetworkKeys {
    private const val ID_HEX_CHARS = 16

    fun cellular(mccMnc: String): NetworkKey = NetworkKey(Transport.CELLULAR, mccMnc.trim())

    /** Hash of default gateways, DNS servers and search domains; order-, case- and duplicate-insensitive. */
    fun hashed(transport: Transport, gateways: Collection<String>, dnsServers: Collection<String>, domains: String?): NetworkKey {
        val md = MessageDigest.getInstance("SHA-256")
        fun part(tag: String, values: Collection<String>) {
            md.update(tag.toByteArray())
            values.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().sorted().forEach {
                md.update(it.toByteArray())
                md.update(0)
            }
        }
        part("g", gateways)
        part("d", dnsServers)
        part("s", domains.orEmpty().split(' ', ',', ';'))
        val hex = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return NetworkKey(transport, hex.take(ID_HEX_CHARS))
    }

    /** Cellular with a known operator is keyed by MCC-MNC; everything else, cellular without one included, by [hashed]. */
    fun of(transport: Transport, mccMnc: String?, gateways: Collection<String>, dnsServers: Collection<String>, domains: String?): NetworkKey =
        if (transport == Transport.CELLULAR && !mccMnc.isNullOrBlank()) cellular(mccMnc) else hashed(transport, gateways, dnsServers, domains)
}
