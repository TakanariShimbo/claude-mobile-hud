package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Phone-local 会話履歴の永続化 (docs/03 §3.6.1 / AD-17 / NFR-13)。serializer + 順序保持
 * (§3.6.1.1)、load の段階的 recovery (§3.6.1.2)、save の atomic move 失敗パス (§3.6.1.3) を参照。
 *
 * Context 自体は不要 (filesDir だけ受ければ Robolectric なしで JVM テスト可能)。
 */
class HistoryStore(private val filesDir: File) {

    private val mutex = Mutex()
    private val log = StructuredLog("channel.history")

    private val targetFile = File(filesDir, FILE_NAME)
    private val tmpFile = File(filesDir, "$FILE_NAME.tmp")

    private val serializer = MapSerializer(
        String.serializer(),
        ListSerializer(ChatMessage.serializer()),
    )
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): MutableMap<String, MutableList<ChatMessage>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            filesDir.mkdirs()
            recoverIfTmpOrphaned()

            if (!targetFile.exists()) {
                return@withContext mutableMapOf()
            }

            val raw = try {
                targetFile.readText()
            } catch (e: IOException) {
                log.error("history_read_failed", e)
                return@withContext mutableMapOf()
            }
            if (raw.isBlank()) return@withContext mutableMapOf()

            try {
                json.decodeFromString(serializer, raw)
                    .mapValuesTo(LinkedHashMap()) { it.value.toMutableList() }
            } catch (e: Throwable) {
                log.error("history_corrupt", e)
                val backup = File(filesDir, "$FILE_NAME.corrupt.${System.currentTimeMillis()}")
                runCatching { targetFile.renameTo(backup) }
                mutableMapOf()
            }
        }
    }

    suspend fun save(snapshot: Map<String, List<ChatMessage>>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val text = json.encodeToString(serializer, snapshot)
            try {
                tmpFile.parentFile?.mkdirs()
                tmpFile.writeText(text)
            } catch (e: IOException) {
                log.error("history_tmp_write_failed", e)
                return@withContext
            }
            try {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                log.debug("history_saved", "sessions" to snapshot.size)
            } catch (e: AtomicMoveNotSupportedException) {
                // docs/03 §3.6.1.3: tmp を残して次回 load の recoverIfTmpOrphaned で復旧。
                log.error("history_atomic_move_unsupported", e)
            } catch (e: IOException) {
                log.error("history_move_failed", e)
            }
        }
    }

    private fun recoverIfTmpOrphaned() {
        if (!tmpFile.exists()) return
        if (!targetFile.exists()) {
            val ok = runCatching {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.isSuccess
            log.info("history_recovered_from_tmp", "ok" to ok)
            return
        }
        // docs/03 §3.6.1.2: 両方ある場合は lastModified 比較で新しい方を採用。
        if (tmpFile.lastModified() > targetFile.lastModified()) {
            val backup = File(filesDir, "$FILE_NAME.bak.${System.currentTimeMillis()}")
            runCatching { targetFile.renameTo(backup) }
            // P2-8: backup rename が失敗しても target が残るケースに備え REPLACE_EXISTING。
            val ok = runCatching {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess
            log.info("history_tmp_newer_promoted", "ok" to ok)
        } else {
            runCatching { tmpFile.delete() }
            log.info("history_tmp_stale_dropped")
        }
    }

    companion object {
        private const val FILE_NAME = "chat-history.json"
    }
}
