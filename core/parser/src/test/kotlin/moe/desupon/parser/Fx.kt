package moe.desupon.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import moe.desupon.model.node.Node
import moe.desupon.model.subscription.SubId
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.fail

/** Test fixtures and JSON helpers. */
object Fx {
    val SUB = SubId(1)

    fun text(path: String): String =
        Fx::class.java.getResource("/fixtures/$path")?.readText() ?: error("missing fixture $path")

    fun headers(path: String): Map<String, String> = text(path).lines().filter { ':' in it }
        .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }

    data class Vector(val id: String, val expect: String, val link: String)

    val vectors: List<Vector> by lazy {
        text("vectors.tsv").lines().filter { it.isNotBlank() }.map {
            val p = it.split('\t')
            Vector(p[0], p[1], p[2])
        }
    }

    fun vector(id: String): String = vectors.firstOrNull { it.id == id }?.link ?: error("no vector $id")

    fun parse(text: String, sub: SubId = SUB, headers: Map<String, String> = emptyMap()) =
        SubscriptionParser.parse(text, sub, headers)

    fun single(link: String): Node {
        val r = parse(link)
        if (r.nodes.size != 1) fail("expected one node, got ${r.nodes.size}, skipped=${r.skipped}")
        return r.nodes.single()
    }

    fun singleOf(id: String): Node = single(vector(id))

    fun reasonOf(link: String): String? = parse(link).let { r -> r.unsupportedReasons[r.nodes.single().id] }

    fun Node.json(): JsonObject = Json.parseToJsonElement(outboundJson.raw()).jsonObject

    /** `a.b.0.c` path lookup. */
    fun JsonElement?.at(path: String): JsonElement? {
        var cur: JsonElement? = this
        for (k in path.split('.')) {
            cur = when (cur) {
                is JsonObject -> cur[k]
                is JsonArray -> k.toIntOrNull()?.let { cur.getOrNull(it) }
                else -> null
            }
        }
        return cur
    }

    fun JsonElement?.s(path: String): String? = (at(path) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    fun JsonElement?.list(path: String): List<String>? = (at(path) as? JsonArray)?.map { (it as JsonPrimitive).content }

    fun assertAt(expected: Any?, root: JsonElement, path: String) {
        val v = root.at(path)
        val actual: Any? = when (v) {
            null, is JsonNull -> null
            is JsonPrimitive -> v.content
            is JsonArray -> v.map { (it as? JsonPrimitive)?.content ?: it.toString() }
            else -> v.toString()
        }
        val exp: Any? = when (expected) {
            is Number, is Boolean -> expected.toString()
            else -> expected
        }
        assertEquals(exp, actual, "at $path in $root")
    }

    fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Qt qCompress framing as AmneziaVPN exports it. */
    fun qCompress(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
