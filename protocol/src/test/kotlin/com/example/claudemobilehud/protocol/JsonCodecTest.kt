package com.example.claudemobilehud.protocol

import com.example.claudemobilehud.protocol.codec.JsonCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class JsonCodecTest {

    @TestFactory
    fun `roundtrip for every WireEvent sample`(): List<DynamicTest> =
        WireSamples.all.map { (name, event) ->
            DynamicTest.dynamicTest("$name: encode → decode equals original") {
                val bytes = JsonCodec.encode(event)
                val decoded = JsonCodec.decode(bytes)
                assertNotNull(decoded, "decode returned null for $name; payload was: ${String(bytes)}")
                assertEquals(event, decoded, "round-trip mismatch for $name")
            }
        }

    @TestFactory
    fun `encoded JSON has 'event' discriminator matching SerialName`(): List<DynamicTest> =
        WireSamples.all.map { (name, event) ->
            DynamicTest.dynamicTest("$name: discriminator equals SerialName") {
                val payload = JsonCodec.encode(event).toString(Charsets.UTF_8)
                val obj = Json.parseToJsonElement(payload).jsonObject
                val discriminator = obj["event"]?.jsonPrimitive?.content
                    ?: error("missing 'event' field in $name payload: $payload")
                val expected = serialNameOf(event)
                assertEquals(expected, discriminator, "$name: discriminator mismatch")
            }
        }

    /** sealed の subclass の `@SerialName` を Java reflection で取得 (kotlin-reflect 不要)。 */
    private fun serialNameOf(event: WireEvent): String {
        val ann = event::class.java.getAnnotation(SerialName::class.java)
            ?: error("no @SerialName on ${event::class.java.simpleName}")
        return ann.value
    }
}
