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
 * Phone-local 会話履歴の永続化。Phase 3 §3.6.1 / AD-17 / NFR-13。
 *
 * - JSON 単一ファイル `chat-history.json` (200 MB cap)
 * - 保存は **atomic write**: `.tmp` に書いてから `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`
 * - 起動時に `.tmp` のみ残っていれば前回 crash と判定して target にリネーム
 * - JSON parse 失敗時はバックアップ (`*.corrupt.<ms>`) してから空で再開
 * - すべての操作は `Mutex` 配下 + IO dispatcher
 *
 * `filesDir` だけ受ければよく Context 自体は必要ない (Robolectric なしで JVM テスト可能)。
 */
class HistoryStore(private val filesDir: File) {

    private val mutex = Mutex()
    private val log = StructuredLog("mhud.history")

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

    /**
     * 履歴を読み込む。
     * - `.tmp` のみ残っていれば前回 crash → tmp を target にリネーム
     * - target 破損時はバックアップ + 空 map
     */
    suspend fun load(): MutableMap<String, MutableList<ChatMessage>> = mutex.withLock {
        withContext(Dispatchers.IO) {
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

    /**
     * 履歴を atomic に書き出す。失敗時 (異 filesystem etc) は `.tmp` を残してログのみ。
     * 次回 [load] で復旧チャンス。
     */
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
                // 異 filesystem 跨ぎ等。tmp を残して次回 load で復旧 (target が古ければ tmp 採用)。
                log.error("history_atomic_move_unsupported", e)
            } catch (e: IOException) {
                log.error("history_move_failed", e)
            }
        }
    }

    /**
     * `.tmp` のみ残っていれば前回 crash 直後と判断して target に rename して引き継ぐ。
     * target が新しければ tmp は破棄。
     */
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
        // target も tmp も両方ある: 通常 atomic move 中に crash なら tmp は消えているはず。
        // tmp のほうが新しいなら一旦バックアップしてから rename、古ければ破棄。
        if (tmpFile.lastModified() > targetFile.lastModified()) {
            val backup = File(filesDir, "$FILE_NAME.bak.${System.currentTimeMillis()}")
            runCatching { targetFile.renameTo(backup) }
            val ok = runCatching {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
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
