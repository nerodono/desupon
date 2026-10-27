package moe.desupon.parser

import moe.desupon.parser.internal.Entry
import moe.desupon.parser.internal.ShareUrl
import moe.desupon.parser.internal.entry
import moe.desupon.parser.link.QuicLinks
import moe.desupon.parser.link.ShadowsocksLink
import moe.desupon.parser.link.V2rayLinks
import moe.desupon.parser.link.WireGuard

internal object LinkParser {
    private val NOT_IMPORTED = setOf("freeturn", "ssr", "http", "https", "tg", "happ")

    fun parse(line: String): Entry {
        val scheme = ShareUrl.schemeOf(line) ?: return Entry.Skip(null, SkipReason.MALFORMED)
        return when (scheme) {
            "vless" -> entry(scheme) { listOf(V2rayLinks.vless(ShareUrl.parse(line))) }
            "vmess" -> entry(scheme) { listOf(V2rayLinks.vmess(line)) }
            "trojan" -> entry(scheme) { listOf(V2rayLinks.trojan(ShareUrl.parse(line))) }
            "ss" -> entry(scheme) { listOf(ShadowsocksLink.parse(line)) }
            "hysteria2", "hy2" -> entry(scheme) { listOf(QuicLinks.hysteria2(ShareUrl.parse(line))) }
            "hysteria" -> entry(scheme) { listOf(QuicLinks.hysteria(ShareUrl.parse(line))) }
            "tuic" -> entry(scheme) { listOf(QuicLinks.tuic(ShareUrl.parse(line))) }
            "juicity" -> entry(scheme) { listOf(QuicLinks.juicity(ShareUrl.parse(line))) }
            "wireguard", "wg", "awg" -> entry(scheme) { listOf(WireGuard.link(ShareUrl.parse(line))) }
            "vpn" -> entry(scheme) { WireGuard.amnezia(line) }
            else -> when {
                scheme == "happ" && line.trim().lowercase().startsWith("happ://crypt") -> Entry.Skip(scheme, SkipReason.ENCRYPTED)
                scheme in NOT_IMPORTED -> Entry.Skip(scheme, SkipReason.UNSUPPORTED_SCHEME)
                else -> Entry.Skip(scheme, SkipReason.UNKNOWN_SCHEME)
            }
        }
    }
}
