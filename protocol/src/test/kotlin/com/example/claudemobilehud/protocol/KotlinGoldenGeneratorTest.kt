package com.example.claudemobilehud.protocol

import com.example.claudemobilehud.protocol.codec.JsonCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 3 §2.6 の Kotlin side golden generator。
 * 出力先: `protocol/src/test/golden/kotlin/<case>.json` (git 管理対象)。
 *
 * **モード**:
 * - 既定 (CI / 通常 `./gradlew :protocol:test`): **verify-only**。
 *   golden が無いケースは fail (誰かが削除した PR を検出するため)、
 *   既存と論理一致しなければ fail (drift 検出)。
 * - 再生成: `-Pgolden.write=true` (Gradle property) または env `PROTOCOL_GOLDEN_WRITE=1`。
 *   wire shape を意図的に変えた後で 1 回だけ実行 → 差分を commit する。
 *
 * **比較セマンティクス**: `Json.parseToJsonElement` 同士の equals。
 * key 順序 / whitespace に依存しない (pretty-print の見た目が変わっても fail しない)。
 */
class KotlinGoldenGeneratorTest {

    private val goldenRoot: Path =
        Path.of(System.getProperty("user.dir"), "src", "test", "golden", "kotlin")

    private val writeMode: Boolean =
        System.getProperty("golden.write") == "true" ||
            System.getenv("PROTOCOL_GOLDEN_WRITE") == "1"

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @TestFactory
    fun `verify (or write with -Pgolden_write=true) golden JSON for every sample`(): List<DynamicTest> =
        WireSamples.all.map { (name, event) ->
            DynamicTest.dynamicTest("$name → kotlin/$name.json") {
                val payload = JsonCodec.encode(event).toString(Charsets.UTF_8)
                val parsed = Json.parseToJsonElement(payload)
                val target = goldenRoot.resolve("$name.json")

                if (writeMode) {
                    Files.createDirectories(goldenRoot)
                    val pretty = prettyJson.encodeToString(JsonElement.serializer(), parsed)
                    Files.writeString(target, pretty + "\n")
                    return@dynamicTest
                }

                assertTrue(
                    Files.exists(target),
                    "missing golden file: $target (regenerate with " +
                        "`./gradlew :protocol:test -Pgolden.write=true` or env PROTOCOL_GOLDEN_WRITE=1)",
                )
                val existingParsed = Json.parseToJsonElement(Files.readString(target))
                assertEquals(
                    parsed,
                    existingParsed,
                    "golden drift in $name. To regenerate intentionally, run " +
                        "`./gradlew :protocol:test -Pgolden.write=true` then commit the diff.",
                )
            }
        }
}
