package moe.desupon.platform.prober

import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import moe.desupon.platform.log.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Converts core probe output into model results. */
object ProbeResults {
    /** Outbound types the core probe skips; they never appear in its results. */
    private val skippedTypes = setOf("direct", "block", "dns", "selector", "urltest")

    fun stage(core: String?): ProbeStage = when (core) {
        "tls" -> ProbeStage.TLS
        "get204" -> ProbeStage.GET204
        else -> ProbeStage.TRANSPORT
    }

    fun of(
        ok: Boolean,
        stage: String?,
        code: Int,
        rttMs: Int,
        error: String?,
        connectMs: Int = 0,
        firstMs: Int = rttMs,
        warm: Boolean = false,
    ): ProbeResult = if (ok) {
        ProbeResult.Ok(ProbeStage.GET204, rttMs, connectMs = connectMs, firstMs = firstMs, warm = warm)
    } else {
        val reason = Redactor.mask(error).ifEmpty { if (code > 0) "unexpected response $code" else "failed" }
        ProbeResult.Fail(stage(stage), reason)
    }

    /** Parses the core's JSON array of `{tag, ok, stage, code, body_len, rtt_ms, connect_ms, first_ms, warm, error}`. */
    fun parse(json: String): Map<String, ProbeResult> {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("probe: malformed result")
        }
        val out = LinkedHashMap<String, ProbeResult>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val tag = item.optString("tag").ifEmpty { continue }
            val rtt = item.millis("rtt_ms")
            out[tag] = of(
                ok = item.optBoolean("ok"),
                stage = item.optString("stage"),
                code = item.optInt("code"),
                rttMs = rtt,
                error = item.optString("error"),
                connectMs = item.millis("connect_ms"),
                firstMs = if (item.has("first_ms")) item.millis("first_ms") else rtt,
                warm = item.optBoolean("warm"),
            )
        }
        return out
    }

    /** Tags the core would probe in [configJson]; used to fail them all when the probe cannot run. */
    fun probedTags(configJson: String): List<String> {
        val config = try {
            JSONObject(configJson)
        } catch (e: JSONException) {
            return emptyList()
        }
        val tags = mutableListOf<String>()
        config.optJSONArray("outbounds")?.forEachObject { o ->
            if (o.optString("type") !in skippedTypes) o.optString("tag").takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        config.optJSONArray("endpoints")?.forEachObject { o ->
            o.optString("tag").takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        return tags
    }

    private fun JSONObject.millis(key: String): Int = optLong(key).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }
}
