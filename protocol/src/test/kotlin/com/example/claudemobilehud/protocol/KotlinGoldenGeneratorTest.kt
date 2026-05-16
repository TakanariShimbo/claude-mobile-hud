package com.example.claudemobilehud.protocol

import com.example.claudemobilehud.protocol.codec.JsonCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 3 §2.6 の Kotlin side golden generator。
 * Gradle :protocol:test の作業ディレクトリは protocol/ なので、
 * 出力先は protocol/src/test/golden/kotlin/<case>.json (git 管理対象)。
 *
 * 各テストは
 *   1. JsonCodec で encode
 *   2. golden file が存在しなければ作成 (= 初回 commit 用)
 *   3. 既存ならその内容と論理的に一致するか比較 (順序非依存 = JsonElement で比較)
 * を行う。
 */
class KotlinGoldenGeneratorTest {

    private val goldenRoot: Path =
        Path.of(System.getProperty("user.dir"), "src", "test", "golden", "kotlin")

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @TestFactory
    fun `generate or verify golden JSON for every sample`(): List<DynamicTest> =
        WireSamples.all.map { (name, event) ->
            DynamicTest.dynamicTest("$name → kotlin/$name.json") {
                Files.createDirectories(goldenRoot)
                val payload = JsonCodec.encode(event).toString(Charsets.UTF_8)
                val parsed = Json.parseToJsonElement(payload)
                val pretty = prettyJson.encodeToString(JsonElement.serializer(), parsed)
                val target = goldenRoot.resolve("$name.json")

                if (!Files.exists(target)) {
                    Files.writeString(target, pretty + "\n")
                    return@dynamicTest
                }

                val existing = Files.readString(target)
                val existingParsed = Json.parseToJsonElement(existing)
                assertEquals(
                    parsed,
                    existingParsed,
                    "golden drift in $name. Re-run after deleting protocol/src/test/golden/kotlin/$name.json to regenerate.",
                )
            }
        }
}
