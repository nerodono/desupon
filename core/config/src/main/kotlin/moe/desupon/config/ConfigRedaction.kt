package moe.desupon.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shows a generated config without credentials: every value under a credential field becomes [MASK]. */
object ConfigRedaction {
    const val MASK = "***"

    private val SECRET_FIELDS = setOf(
        "password", "uuid", "username", "auth", "auth_str", "token", "psk", "secret", "key",
        "private_key", "public_key", "pre_shared_key", "short_id", "header_protection_key",
        "plugin_opts", "encryption", "reserved", "obfs", "private_key_passphrase",
    )

    private val pretty = Json { prettyPrint = true }

    /** Pretty-printed masked copy of [json]; text that is not a JSON object yields [MASK] alone, since it cannot be inspected. */
    fun mask(json: String): String {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return MASK
        return pretty.encodeToString(JsonElement.serializer(), redact(root))
    }

    private fun redact(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k.lowercase() in SECRET_FIELDS) masked(v) else redact(v) })
        is JsonArray -> JsonArray(e.map(::redact))
        else -> e
    }

    private fun masked(e: JsonElement): JsonElement = when (e) {
        JsonNull -> e
        is JsonPrimitive -> if (e.isString && e.content == "none") e else JsonPrimitive(MASK)
        is JsonArray -> JsonArray(e.map(::masked))
        is JsonObject -> JsonObject(e.mapValues { masked(it.value) })
    }
}
