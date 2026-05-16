package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PairingTest {

    @Test
    fun `parse happy path returns baseUrl and token`() {
        val raw = """{"v":1,"baseUrl":"http://192.168.1.10:8788","token":"abc123"}"""
        val result = Pairing.parse(raw).getOrThrow()
        assertEquals("http://192.168.1.10:8788", result.baseUrl)
        assertEquals("abc123", result.token)
    }

    @Test
    fun `parse strips trailing slashes from baseUrl`() {
        val raw = """{"v":1,"baseUrl":"http://host:8788///","token":"t"}"""
        val result = Pairing.parse(raw).getOrThrow()
        assertEquals("http://host:8788", result.baseUrl)
    }

    @Test
    fun `parse fails on unsupported version`() {
        val raw = """{"v":2,"baseUrl":"http://h:8788","token":"t"}"""
        val err = Pairing.parse(raw).exceptionOrNull()
        assertNotNull(err)
        assert(err!!.message!!.contains("unsupported pairing version: 2"))
    }

    @Test
    fun `parse fails on blank baseUrl`() {
        val raw = """{"v":1,"baseUrl":"","token":"t"}"""
        val err = Pairing.parse(raw).exceptionOrNull()
        assertNotNull(err)
        assert(err!!.message!!.contains("baseUrl missing"))
    }

    @Test
    fun `parse fails on blank token`() {
        val raw = """{"v":1,"baseUrl":"http://h:8788","token":""}"""
        val err = Pairing.parse(raw).exceptionOrNull()
        assertNotNull(err)
        assert(err!!.message!!.contains("token missing"))
    }

    @Test
    fun `parse fails on malformed JSON`() {
        val err = Pairing.parse("not json at all").exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `parse fails on missing required fields`() {
        val raw = """{"v":1}"""
        val err = Pairing.parse(raw).exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `parse fails on json null baseUrl (P3-D)`() {
        // Hub の guard をすり抜けて `null` が乗った経路でも、ユーザ向けには blank と
        // 同じ "baseUrl missing in QR" メッセージで表示される (P1-A 修正)。
        val err = Pairing.parse("""{"v":1,"baseUrl":null,"token":"t"}""").exceptionOrNull()
        assertNotNull(err)
        assert(err!!.message!!.contains("baseUrl missing"))
    }

    @Test
    fun `parse fails on json null token (P3-D)`() {
        val err = Pairing.parse("""{"v":1,"baseUrl":"http://h:8788","token":null}""").exceptionOrNull()
        assertNotNull(err)
        assert(err!!.message!!.contains("token missing"))
    }

    @Test
    fun `parse ignores unknown fields`() {
        // 将来追加されるかもしれない field は app 側で silently ignore する (forward compat)。
        val raw = """{"v":1,"baseUrl":"http://h:8788","token":"t","extra":"x"}"""
        val result = Pairing.parse(raw).getOrThrow()
        assertEquals("http://h:8788", result.baseUrl)
    }

    @Test
    fun `mergeInto preserves openAiApiKey and other fields`() {
        val current = Settings(
            baseUrl = "http://old:1234",
            token = "old-token",
            openAiApiKey = "sk-keep-me",
            lastCurrentSessionId = "abc",
        )
        val result = Pairing.PairingResult("http://new:8788", "new-token")
        val merged = result.mergeInto(current)
        assertEquals("http://new:8788", merged.baseUrl)
        assertEquals("new-token", merged.token)
        assertEquals("sk-keep-me", merged.openAiApiKey)
        assertEquals("abc", merged.lastCurrentSessionId)
    }
}
