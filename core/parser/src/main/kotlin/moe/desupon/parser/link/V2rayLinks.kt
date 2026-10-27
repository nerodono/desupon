package moe.desupon.parser.link

import moe.desupon.parser.internal.B64
import moe.desupon.parser.internal.Candidate
import moe.desupon.parser.internal.Issues
import moe.desupon.parser.internal.ShareUrl
import moe.desupon.parser.internal.V2Stream
import moe.desupon.parser.internal.asObj
import moe.desupon.parser.internal.int
import moe.desupon.parser.internal.jsonObj
import moe.desupon.parser.internal.malformed
import moe.desupon.parser.internal.parseJsonOrNull
import moe.desupon.parser.internal.str

internal object V2rayLinks {
    fun vless(u: ShareUrl): Candidate {
        val uuid = u.userInfo?.trim()?.takeIf { it.isNotEmpty() } ?: malformed("missing uuid")
        val stream = V2Stream.fromQuery(u)
        val issues = Issues()
        val out = jsonObj {
            str("type", "vless")
            str("server", u.requireHost())
            num("server_port", u.requirePort())
            str("uuid", uuid)
            str("flow", u.q("flow"))
            str("encryption", vlessEncryption(u.q("encryption"), issues))
            el("tls", stream.tls(issues))
            el("transport", stream.transport(issues))
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    fun trojan(u: ShareUrl): Candidate {
        val password = u.userInfo?.takeIf { it.isNotEmpty() } ?: malformed("missing password")
        val stream = V2Stream.fromQuery(u, defaultSecurity = "tls")
        val issues = Issues()
        val out = jsonObj {
            str("type", "trojan")
            str("server", u.requireHost())
            num("server_port", u.requirePort(default = 443))
            str("password", password)
            el("tls", stream.tls(issues))
            el("transport", stream.transport(issues))
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    /** v2rayN `vmess://base64(json)`, or the URL form `vmess://uuid@host:port?…`. */
    fun vmess(line: String): Candidate {
        val payload = line.trim().substring("vmess://".length).substringBefore('#')
        val json = B64.decodeUtf8(payload.substringBefore('?'))?.let { parseJsonOrNull(it).asObj() }
        if (json == null) {
            val u = ShareUrl.parse(line)
            if (u.userInfoRaw == null) malformed("vmess payload is neither base64 JSON nor URL")
            return vmessUrl(u)
        }
        val stream = V2Stream.fromVmessJson(json)
        val issues = Issues()
        val port = json.int("port")?.takeIf { it in 1..65535 } ?: malformed("bad port")
        val out = jsonObj {
            str("type", "vmess")
            str("server", json.str("add") ?: malformed("missing host"))
            num("server_port", port)
            str("uuid", json.str("id") ?: malformed("missing uuid"))
            str("security", vmessSecurity(json.str("scy", "security")))
            json.int("aid")?.takeIf { it > 0 }?.let { num("alter_id", it) }
            el("tls", stream.tls(issues))
            el("transport", stream.transport(issues))
        }
        return Candidate(out, json.str("ps"), issues.reason)
    }

    private fun vmessUrl(u: ShareUrl): Candidate {
        val stream = V2Stream.fromQuery(u)
        val issues = Issues()
        val out = jsonObj {
            str("type", "vmess")
            str("server", u.requireHost())
            num("server_port", u.requirePort())
            str("uuid", u.userInfo?.trim()?.takeIf { it.isNotEmpty() } ?: malformed("missing uuid"))
            str("security", vmessSecurity(u.q("encryption", "scy")))
            u.q("aid", "alterid")?.toIntOrNull()?.takeIf { it > 0 }?.let { num("alter_id", it) }
            el("tls", stream.tls(issues))
            el("transport", stream.transport(issues))
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    private val VMESS_SECURITY = setOf("auto", "none", "zero", "aes-128-cfb", "aes-128-gcm", "chacha20-poly1305")

    fun vmessSecurity(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in VMESS_SECURITY } ?: "auto"

    /** Xray VLESS encryption: `none`, or `mlkem768x25519plus.<mode>.<rtt>…` which the bundled core implements. */
    fun vlessEncryption(raw: String?, issues: Issues): String? {
        val e = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) } ?: return null
        if (!e.startsWith("mlkem768x25519plus.")) issues.unsupported("VLESS encryption scheme")
        return e
    }
}
