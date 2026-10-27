package moe.desupon.parser.link

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.desupon.parser.internal.B64
import moe.desupon.parser.internal.Candidate
import moe.desupon.parser.internal.Issues
import moe.desupon.parser.internal.NotANodeException
import moe.desupon.parser.internal.ShareUrl
import moe.desupon.parser.internal.asObj
import moe.desupon.parser.internal.jsonObj
import moe.desupon.parser.internal.lines2
import moe.desupon.parser.internal.looseKey
import moe.desupon.parser.internal.malformed
import moe.desupon.parser.internal.normalizeRange
import moe.desupon.parser.internal.parseBool
import moe.desupon.parser.internal.parseJsonOrNull
import moe.desupon.parser.internal.scalar
import moe.desupon.parser.internal.splitList
import moe.desupon.parser.internal.str
import moe.desupon.parser.internal.strictUtf8
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal data class WgPeer(
    val host: String,
    val port: Int,
    val publicKey: String,
    val preSharedKey: String?,
    val allowedIps: List<String>,
    val keepalive: Int?,
)

/** WireGuard / AmneziaWG client config; [amnezia] keys are loose (see [looseKey]) with raw values. */
internal data class WgConf(
    val privateKey: String,
    val addresses: List<String>,
    val mtu: Int?,
    val peers: List<WgPeer>,
    val amnezia: Map<String, String>,
    val name: String?,
) {
    fun toCandidate(): Candidate {
        if (peers.isEmpty()) malformed("no peer")
        if (addresses.isEmpty()) malformed("no interface address")
        val issues = Issues()
        val awg = if (isPlainWireGuard(amnezia)) null else amneziaJson(amnezia, issues)
        val out = jsonObj {
            str("type", "wireguard")
            mtu?.takeIf { it > 0 }?.let { num("mtu", it) }
            strings("address", addresses.map(::withPrefix))
            str("private_key", privateKey)
            el(
                "peers",
                kotlinx.serialization.json.JsonArray(
                    peers.map { p ->
                        jsonObj {
                            str("address", p.host)
                            num("port", p.port)
                            str("public_key", p.publicKey)
                            str("pre_shared_key", p.preSharedKey)
                            strings("allowed_ips", p.allowedIps.ifEmpty { DEFAULT_ALLOWED })
                            p.keepalive?.takeIf { it > 0 }?.let { num("persistent_keepalive_interval", it) }
                        }
                    },
                ),
            )
            el("amnezia", awg)
        }
        return Candidate(out, name, issues.reason)
    }

    companion object {
        private val DEFAULT_ALLOWED = listOf("0.0.0.0/0", "::/0")
        private val INTS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
        private val STRINGS = listOf("i1", "i2", "i3", "i4", "i5")
        private val RANGES = listOf(
            "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
            "contentpaddingaddition" to "content_padding_addition",
            "rekeyaftertime" to "rekey_after_time",
            "rekeytimeout" to "rekey_timeout",
            "rejectaftertime" to "reject_after_time",
            "keepalivetimeout" to "keepalive_timeout",
            "maxhandshakeattempts" to "max_handshake_attempts",
        )
        private const val HPK = "headerprotectionkey"
        private const val HPK_MIN_PADDING = 12

        val AMNEZIA_KEYS: Set<String> =
            (INTS + STRINGS + RANGES.map { it.first } + listOf(HPK, "randomtrailers", "disablecookies")).toSet()

        /** Absent or protocol-default values only: H1..H4 = 1..4 and nothing else set. */
        private fun isPlainWireGuard(a: Map<String, String>): Boolean = a.all { (k, v) ->
            val t = v.trim()
            when (k) {
                "h1" -> t == "1"
                "h2" -> t == "2"
                "h3" -> t == "3"
                "h4" -> t == "4"
                in INTS -> t.toIntOrNull() == 0
                "randomtrailers", "disablecookies" -> parseBool(t) != true
                else -> t.isEmpty()
            }
        }

        private fun amneziaJson(a: Map<String, String>, issues: Issues): JsonObject = jsonObj {
            for (k in INTS) a[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { num(k, it.toIntOrNull() ?: malformed("bad AmneziaWG $k")) }
            for ((from, to) in RANGES) a[from]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                el(to, JsonPrimitive(normalizeRange(it) ?: malformed("bad AmneziaWG range")))
            }
            for (k in STRINGS) str(k, a[k]?.trim())
            val hpk = a[HPK]?.trim()?.takeIf { it.isNotEmpty() }
            str("header_protection_key", hpk)
            flag("random_trailers", parseBool(a["randomtrailers"]) == true)
            flag("disable_cookies", parseBool(a["disablecookies"]) == true)
            if (hpk != null && listOf("s1", "s2", "s3", "s4").any { (a[it]?.trim()?.toIntOrNull() ?: 0) < HPK_MIN_PADDING }) {
                issues.unsupported("AmneziaWG header protection needs S1-S4 >= $HPK_MIN_PADDING")
            }
        }

        fun withPrefix(addr: String): String = when {
            '/' in addr -> addr
            ':' in addr -> "$addr/128"
            else -> "$addr/32"
        }
    }
}

internal object WireGuard {
    /** `wireguard://privkey@host:port?publickey=…&address=…`, and the `wg://host:port?pk=…&peer_public_key=…` variant. */
    fun link(u: ShareUrl): Candidate {
        val priv = u.userInfo?.trim()?.takeIf { it.isNotEmpty() } ?: u.qKey("privatekey", "private_key", "pk", "secretkey")
            ?: malformed("missing private key")
        val peer = WgPeer(
            host = u.requireHost(),
            port = u.requirePort(),
            publicKey = u.qKey("publickey", "public_key", "peer_public_key", "peerpublickey") ?: malformed("missing public key"),
            preSharedKey = u.qKey("presharedkey", "pre_shared_key", "preshared_key", "psk"),
            allowedIps = u.q("allowedips", "allowed_ips")?.let(::splitList).orEmpty(),
            keepalive = keepalive(u.q("keepalive", "persistent_keepalive", "persistentkeepalive")),
        )
        val amnezia = HashMap<String, String>()
        for (k in WgConf.AMNEZIA_KEYS) {
            val v = if (k == "headerprotectionkey") u.qKey(k, "header_protection_key", "hpk") else u.q(k, snake(k))
            if (v != null) amnezia[k] = v
        }
        return WgConf(
            privateKey = priv,
            addresses = u.q("address", "ip", "local_address", "localaddress")?.let(::splitList).orEmpty(),
            mtu = u.q("mtu")?.toIntOrNull(),
            peers = listOf(peer),
            amnezia = amnezia,
            name = u.fragment,
        ).toCandidate()
    }

    private fun snake(k: String): String = when (k) {
        "contentpaddingaddition" -> "content_padding_addition"
        "rekeyaftertime" -> "rekey_after_time"
        "rekeytimeout" -> "rekey_timeout"
        "rejectaftertime" -> "reject_after_time"
        "keepalivetimeout" -> "keepalive_timeout"
        "maxhandshakeattempts" -> "max_handshake_attempts"
        "randomtrailers" -> "random_trailers"
        "disablecookies" -> "disable_cookies"
        else -> k
    }

    /** `25`, or an AmneziaWG range `20-30` whose lower bound is used. */
    private fun keepalive(raw: String?): Int? = raw?.trim()?.substringBefore('-')?.trim()?.toIntOrNull()

    fun looksLikeIni(text: String): Boolean {
        for (raw in text.lines2()) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue
            return l.startsWith("[")
        }
        return false
    }

    /**
     * wg-quick / AmneziaWG `.conf`. The name is the `# comment` right before `[Peer]`, which is where 3x-ui writes the remark.
     * [fallbackHost]/[fallbackPort] replace a missing or `$PLACEHOLDER` endpoint, as Amnezia profiles carry it separately.
     */
    fun ini(text: String, name: String? = null, fallbackHost: String? = null, fallbackPort: Int? = null, fallbackMtu: Int? = null): Candidate {
        var section = ""
        var lastComment: String? = null
        var peerComment: String? = null
        var privateKey: String? = null
        val addresses = ArrayList<String>()
        var mtu: Int? = null
        val amnezia = HashMap<String, String>()
        val peers = ArrayList<MutableMap<String, String>>()

        for (raw in text.lines2()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";")) {
                lastComment = line.trimStart('#', ';').trim().takeIf { it.isNotEmpty() }
                continue
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                if (section == "peer") {
                    if (peers.isEmpty()) peerComment = lastComment
                    peers += HashMap()
                }
                lastComment = null
                continue
            }
            lastComment = null
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = looseKey(line.substring(0, eq).trim())
            val value = line.substring(eq + 1).trim()
            when (section) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += splitList(value)
                    "mtu" -> mtu = value.toIntOrNull()
                    in WgConf.AMNEZIA_KEYS -> amnezia[key] = value
                }
                "peer" -> peers.last()[key] = value
            }
        }

        val parsedPeers = peers.map { p ->
            var host: String? = null
            var port: Int? = null
            p["endpoint"]?.takeUnless { it.startsWith("$") }?.let { ep ->
                val hp = ShareUrl.parse("wg://x@$ep")
                host = hp.host.takeIf { it.isNotEmpty() && !it.startsWith("$") }
                port = hp.port
            }
            WgPeer(
                host = host ?: fallbackHost ?: malformed("peer without endpoint"),
                port = port ?: fallbackPort ?: malformed("peer without endpoint port"),
                publicKey = p["publickey"] ?: malformed("peer without public key"),
                preSharedKey = p["presharedkey"]?.takeIf { it.isNotEmpty() },
                allowedIps = p["allowedips"]?.let(::splitList).orEmpty(),
                keepalive = keepalive(p["persistentkeepalive"]),
            )
        }
        return WgConf(
            privateKey = privateKey ?: malformed("missing private key"),
            addresses = addresses,
            mtu = mtu ?: fallbackMtu,
            peers = parsedPeers,
            amnezia = amnezia,
            name = name ?: peerComment,
        ).toCandidate()
    }

    /** `vpn://` + base64url of a raw `.conf` (3x-ui), or of qCompress(JSON) Amnezia profile with WG/AWG containers. */
    fun amnezia(line: String): List<Candidate> {
        val payload = line.trim().substring("vpn://".length)
        val bytes = B64.decode(payload) ?: malformed("vpn:// payload is not base64")
        strictUtf8(bytes)?.takeIf(::looksLikeIni)?.let { return listOf(ini(it)) }

        val json = inflateQCompress(bytes) ?: strictUtf8(bytes)?.takeIf { it.trimStart().startsWith("{") }
            ?: malformed("vpn:// payload is neither a .conf nor an Amnezia profile")
        val root = parseJsonOrNull(json).asObj() ?: malformed("vpn:// profile is not JSON")
        val containers = root["containers"] as? kotlinx.serialization.json.JsonArray ?: malformed("vpn:// profile without containers")
        val profile = root.str("description") ?: root.str("hostName")
        val defaultContainer = root.str("defaultContainer")

        data class Entry(val container: String?, val conf: String, val proto: JsonObject, val last: JsonObject)
        val entries = ArrayList<Entry>()
        for (c in containers) {
            val obj = c.asObj() ?: continue
            for ((_, v) in obj) {
                val proto = v.asObj() ?: continue
                val last = when (val lc = proto["last_config"]) {
                    is JsonObject -> lc
                    else -> lc.scalar()?.let { parseJsonOrNull(it).asObj() }
                } ?: continue
                val conf = last.str("config")?.takeIf { "[Interface]" in it && "[Peer]" in it } ?: continue
                entries += Entry(obj.str("container"), conf, proto, last)
            }
        }
        if (entries.isEmpty()) throw NotANodeException("vpn:// profile without WireGuard/AmneziaWG containers")
        val first = entries.indexOfFirst { it.container != null && it.container == defaultContainer }.coerceAtLeast(0)
        return entries.mapIndexed { i, e ->
            val name = when {
                i == first -> profile ?: e.container
                profile != null && e.container != null -> "$profile ${e.container}"
                else -> profile ?: e.container
            }
            ini(
                e.conf,
                name = name,
                fallbackHost = root.str("hostName"),
                fallbackPort = (e.last.str("port") ?: e.proto.str("port"))?.toIntOrNull(),
                fallbackMtu = e.last.str("mtu")?.toIntOrNull(),
            )
        }
    }

    private const val MAX_INFLATED = 4 shl 20

    /** Qt qCompress: 4-byte big-endian uncompressed length, then a zlib stream. */
    fun inflateQCompress(bytes: ByteArray): String? {
        if (bytes.size <= 4) return null
        val claimed = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        if (claimed <= 0 || claimed > MAX_INFLATED) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes, 4, bytes.size - 4)
            val out = ByteArrayOutputStream(claimed)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
            if (!inflater.finished()) null else strictUtf8(out.toByteArray())
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}
