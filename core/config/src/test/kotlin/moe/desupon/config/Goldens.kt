package moe.desupon.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/** Golden JSON files under [dir], minus subdirectories in [foreign] that another test owns. */
class Goldens(private val dir: File, private val foreign: Set<String> = emptySet()) {
    fun check(cases: Map<String, String>) {
        if (update) owned().forEach { it.delete() }
        val mismatched = mutableListOf<String>()
        for ((name, json) in cases) {
            val text = pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json)) + "\n"
            val file = File(dir, name)
            if (update) {
                file.parentFile.mkdirs()
                file.writeText(text)
            } else if (!file.isFile || file.readText() != text) {
                mismatched += name
            }
        }
        if (mismatched.isNotEmpty()) fail("goldens differ: $mismatched; rerun with -PupdateGolden and review the diff")
        assertEquals(cases.keys, owned().map { it.relativeTo(dir).path }.toSet(), "stale golden files in $dir")
    }

    private fun owned(): List<File> = dir.walkTopDown()
        .onEnter { it == dir || it.relativeTo(dir).path !in foreign }
        .filter { it.isFile && it.extension == "json" }
        .toList()

    companion object {
        val root = File(System.getProperty("golden.dir") ?: "src/test/resources/golden")
        private val update = System.getProperty("golden.update") == "true"

        @OptIn(ExperimentalSerializationApi::class)
        private val pretty = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
