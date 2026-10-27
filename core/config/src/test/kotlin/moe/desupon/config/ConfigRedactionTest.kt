package moe.desupon.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigRedactionTest {
    private val credentialFields = setOf("password", "uuid", "private_key", "public_key", "pre_shared_key", "short_id", "auth_str")

    private fun credentialValues(e: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                if (k in credentialFields && v is JsonPrimitive && v.isString && v.content.length >= 4) out += v.content
                credentialValues(v, out)
            }
            is JsonArray -> e.forEach { credentialValues(it, out) }
            else -> Unit
        }
        return out
    }

    private fun stringLeaves(e: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        when (e) {
            is JsonObject -> e.values.forEach { stringLeaves(it, out) }
            is JsonArray -> e.forEach { stringLeaves(it, out) }
            is JsonPrimitive -> if (e.isString) out += e.content
        }
        return out
    }

    @Test
    fun goldenConfigsKeepNoCredentialValues() {
        val files = Goldens.root.walkTopDown().filter { it.isFile && it.extension == "json" && it.parentFile.name != "invalid" }.toList()
        assertTrue(files.isNotEmpty())
        var checked = 0
        for (f in files) {
            val raw = f.readText()
            val secrets = credentialValues(Json.parseToJsonElement(raw))
            val leaves = stringLeaves(Json.parseToJsonElement(ConfigRedaction.mask(raw)))
            assertTrue((secrets intersect leaves).isEmpty(), "${f.name} leaks a credential")
            checked += secrets.size
        }
        assertTrue(checked > 0)
    }

    @Test
    fun masksNestedCredentialsAndKeepsStructure() {
        val raw = """{"outbounds":[{"type":"vless","server":"vless.example.com","uuid":"11111111-2222-3333-4444-555555555555",
            |"encryption":"none","tls":{"reality":{"public_key":"pk-value","short_id":"abcd"}},
            |"obfs":{"type":"salamander","password":"obfs-pass"}}]}""".trimMargin()
        val out = Json.parseToJsonElement(ConfigRedaction.mask(raw)).jsonObject
        val ob = (out["outbounds"] as JsonArray)[0].jsonObject
        assertEquals("vless.example.com", ob["server"]!!.jsonPrimitive.content)
        assertEquals("none", ob["encryption"]!!.jsonPrimitive.content)
        assertEquals(ConfigRedaction.MASK, ob["uuid"]!!.jsonPrimitive.content)
        val reality = ob["tls"]!!.jsonObject["reality"]!!.jsonObject
        assertEquals(ConfigRedaction.MASK, reality["public_key"]!!.jsonPrimitive.content)
        assertEquals(ConfigRedaction.MASK, reality["short_id"]!!.jsonPrimitive.content)
        assertFalse("obfs-pass" in out.toString())
    }

    @Test
    fun nonJsonIsMaskedWhole() {
        assertEquals(ConfigRedaction.MASK, ConfigRedaction.mask("password=hunter2"))
    }
}
