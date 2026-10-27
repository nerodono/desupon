package moe.desupon.parser.link

import kotlinx.serialization.json.JsonPrimitive
import moe.desupon.parser.internal.B64
import moe.desupon.parser.internal.Candidate
import moe.desupon.parser.internal.Issues
import moe.desupon.parser.internal.ShareUrl
import moe.desupon.parser.internal.jsonObj
import moe.desupon.parser.internal.malformed
import moe.desupon.parser.internal.percentDecode

internal object ShadowsocksLink {
    /** SIP002 (`base64url(method:pass)@host:port` or plain `method:pass@…`), SS-2022, and legacy `base64(method:pass@host:port)`. */
    fun parse(line: String): Candidate {
        val u = ShareUrl.parse(line)
        if (u.userInfoRaw == null) return legacy(line, u.fragment)

        val raw = u.userInfoRaw
        val decoded = percentDecode(raw)
        val creds = if (':' in decoded) decoded else B64.decodeUtf8(decoded) ?: malformed("userinfo is not base64")
        val (method, password) = splitCreds(creds)
        return build(method, password, u.requireHost(), u.requirePort(), u.q("plugin"), u.fragment)
    }

    private fun legacy(line: String, fragment: String?): Candidate {
        val payload = percentDecode(line.trim().substring("ss://".length).substringBefore('#').substringBefore('?'))
        val text = B64.decodeUtf8(payload) ?: B64.decodeUtf8(payload.trimEnd('/')) ?: malformed("legacy payload is not base64")
        val at = text.lastIndexOf('@')
        if (at < 0) malformed("legacy payload has no host")
        val (method, password) = splitCreds(text.substring(0, at))
        val hp = ShareUrl.parse("ss://x@" + text.substring(at + 1))
        return build(method, password, hp.requireHost(), hp.requirePort(), null, fragment)
    }

    private fun splitCreds(creds: String): Pair<String, String> {
        val c = creds.indexOf(':')
        if (c <= 0) malformed("credentials are not method:password")
        return creds.substring(0, c).trim().lowercase() to creds.substring(c + 1)
    }

    fun build(method: String, password: String, host: String, port: Int, plugin: String?, name: String?): Candidate {
        val issues = Issues()
        val m = normalizeMethod(method, issues)
        var pluginName: String? = null
        var pluginOpts: String? = null
        plugin?.takeIf { it.isNotBlank() }?.let {
            val (n, o) = splitPlugin(it)
            pluginName = n
            pluginOpts = o
            if (n !in SUPPORTED_PLUGINS) issues.unsupported("shadowsocks plugin $n")
        }
        val out = jsonObj {
            str("type", "shadowsocks")
            str("server", host)
            num("server_port", port)
            str("method", m)
            map["password"] = JsonPrimitive(password)
            str("plugin", pluginName)
            str("plugin_opts", pluginOpts)
        }
        return Candidate(out, name, issues.reason)
    }

    /** `obfs-local;obfs=http;obfs-host=x` → name and option string; `simple-obfs` is the old name of obfs-local. */
    fun splitPlugin(spec: String): Pair<String, String?> {
        val name = spec.substringBefore(';').trim().let { if (it == "simple-obfs" || it == "obfs") "obfs-local" else it }
        val opts = spec.substringAfter(';', "").trim().takeIf { it.isNotEmpty() }
        return name to opts
    }

    private val SUPPORTED_PLUGINS = setOf("obfs-local", "v2ray-plugin")

    private val METHODS = setOf(
        "none", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "rc4-md5", "chacha20-ietf", "xchacha20",
    )

    fun normalizeMethod(method: String, issues: Issues): String {
        val m = when (val l = method.trim().lowercase()) {
            "chacha20-poly1305" -> "chacha20-ietf-poly1305"
            "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
            "plain", "dummy" -> "none"
            else -> l
        }
        if (m !in METHODS) issues.unsupported("shadowsocks method $m")
        return m
    }
}
