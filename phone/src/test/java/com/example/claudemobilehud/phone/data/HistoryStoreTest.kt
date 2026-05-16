package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.protocol.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class HistoryStoreTest {

    private fun msg(id: Long, text: String, sessionId: String? = "s1"): ChatMessage =
        ChatMessage(
            id = id,
            role = MessageRole.OUTGOING,
            text = text,
            chatId = null,
            sessionId = sessionId,
            createdAtMs = id * 1000L,
        )

    @Test
    fun `roundtrip save then load`(@TempDir tmp: Path) = runTest {
        val store = HistoryStore(tmp.toFile())
        val data = mapOf(
            "s1" to listOf(msg(1, "hello"), msg(2, "world")),
            "s2" to listOf(msg(3, "foo", "s2")),
        )
        store.save(data)
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals(listOf("hello", "world"), loaded["s1"]?.map { it.text })
        assertEquals(listOf("foo"), loaded["s2"]?.map { it.text })
    }

    @Test
    fun `empty load returns empty map (no file)`(@TempDir tmp: Path) = runTest {
        val store = HistoryStore(tmp.toFile())
        val loaded = store.load()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `corrupt JSON moves file to backup and returns empty`(@TempDir tmp: Path) = runTest {
        val dir = tmp.toFile()
        File(dir, "chat-history.json").writeText("{not json")
        val store = HistoryStore(dir)
        val loaded = store.load()
        assertTrue(loaded.isEmpty())
        val backupExists = dir.listFiles()?.any { it.name.startsWith("chat-history.json.corrupt.") } == true
        assertTrue(backupExists, "corrupt backup should exist; files: ${dir.list()?.toList()}")
        // original target が消えていること
        assertFalse(File(dir, "chat-history.json").exists())
    }

    @Test
    fun `tmp-only recovery moves tmp to target on next load`(@TempDir tmp: Path) = runTest {
        val dir = tmp.toFile()
        val tmpFile = File(dir, "chat-history.json.tmp")
        // 既知の最小 JSON を tmp として置く
        tmpFile.writeText("""{"s1":[]}""")
        assertFalse(File(dir, "chat-history.json").exists())

        val store = HistoryStore(dir)
        val loaded = store.load()
        assertNotNull(loaded["s1"])
        assertTrue(File(dir, "chat-history.json").exists())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun `save creates no tmp residue when successful`(@TempDir tmp: Path) = runTest {
        val store = HistoryStore(tmp.toFile())
        store.save(mapOf("s1" to listOf(msg(1, "ok"))))
        val files = tmp.toFile().list()?.toSet() ?: emptySet()
        assertTrue("chat-history.json" in files)
        assertFalse(files.contains("chat-history.json.tmp"))
    }
}
