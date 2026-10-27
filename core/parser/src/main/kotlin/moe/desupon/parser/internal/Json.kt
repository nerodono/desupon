package moe.desupon.parser.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
internal val LenientJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    allowTrailingComma = true
    allowComments = true
}

internal fun parseJsonOrNull(text: String): JsonElement? =
    try {
        LenientJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

internal fun JsonElement?.scalar(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = this[k].scalar()?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}

/** Like [str] but keeps surrounding whitespace and empty strings. */
internal fun JsonObject.rawStr(key: String): String? = this[key].scalar()

internal fun String.toIntLenient(): Int? = trim().toIntOrNull() ?: trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()

internal fun JsonObject.int(vararg keys: String): Int? = str(*keys)?.toIntLenient()

internal fun parseBool(v: String?): Boolean? = when (v?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> null
}

internal fun JsonObject.bool(vararg keys: String): Boolean? {
    for (k in keys) parseBool(this[k].scalar())?.let { return it }
    return null
}

/** Array of scalars, or a comma-separated scalar. */
internal fun JsonObject.strList(vararg keys: String): List<String> {
    for (k in keys) {
        when (val v = this[k]) {
            is JsonArray -> return v.mapNotNull { it.scalar()?.trim()?.takeIf(String::isNotEmpty) }
            is JsonPrimitive -> v.scalar()?.let { s -> return splitList(s) }
            else -> {}
        }
    }
    return emptyList()
}

internal fun splitList(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

internal fun JsonObject.child(vararg keys: String): JsonObject? {
    for (k in keys) (this[k] as? JsonObject)?.let { return it }
    return null
}

/** Key lookup ignoring case, `-` and `_`, so xray camelCase, mihomo kebab-case and sing-box snake_case all match. */
internal fun JsonObject.looseGet(name: String): JsonElement? {
    val want = looseKey(name)
    for ((k, v) in this) if (looseKey(k) == want) return v
    return null
}

internal fun looseKey(k: String): String = buildString(k.length) {
    for (c in k) if (c != '-' && c != '_') append(c.lowercaseChar())
}

internal class JsonBuilder {
    val map = LinkedHashMap<String, JsonElement>()

    fun str(k: String, v: String?) {
        if (!v.isNullOrEmpty()) map[k] = JsonPrimitive(v)
    }

    fun num(k: String, v: Number?) {
        if (v != null) map[k] = JsonPrimitive(v)
    }

    fun flag(k: String, v: Boolean) {
        if (v) map[k] = JsonPrimitive(true)
    }

    fun bool(k: String, v: Boolean) {
        map[k] = JsonPrimitive(v)
    }

    fun el(k: String, v: JsonElement?) {
        if (v != null && v !is JsonNull) map[k] = v
    }

    fun strings(k: String, v: List<String>?) {
        if (!v.isNullOrEmpty()) map[k] = JsonArray(v.map(::JsonPrimitive))
    }

    fun obj(k: String, block: JsonBuilder.() -> Unit) {
        val o = jsonObj(block)
        if (o.isNotEmpty()) map[k] = o
    }

    fun build(): JsonObject = JsonObject(map)
}

internal inline fun jsonObj(block: JsonBuilder.() -> Unit): JsonObject = JsonBuilder().apply(block).build()

internal fun JsonObject.with(key: String, value: JsonElement?): JsonObject {
    val m = LinkedHashMap(this)
    if (value == null) m.remove(key) else m[key] = value
    return JsonObject(m)
}

internal fun JsonObject.without(vararg keys: String): JsonObject {
    if (keys.none { it in this }) return this
    val m = LinkedHashMap(this)
    keys.forEach { m.remove(it) }
    return JsonObject(m)
}
