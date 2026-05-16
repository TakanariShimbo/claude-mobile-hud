package com.example.claudemobilehud.protocol

import com.example.claudemobilehud.protocol.codec.JsonCodec
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
    fun `decoded payload contains discriminator field 'event'`(): List<DynamicTest> =
        WireSamples.all.map { (name, event) ->
            DynamicTest.dynamicTest("$name: JSON has 'event' discriminator") {
                val payload = String(JsonCodec.encode(event))
                assertEquals(
                    true,
                    payload.contains("\"event\":\""),
                    "missing event discriminator in $name payload: $payload",
                )
            }
        }
}
