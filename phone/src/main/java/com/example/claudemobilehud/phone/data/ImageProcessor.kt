package com.example.claudemobilehud.phone.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 添付画像取り込みパイプライン (docs/03 §3.2.7)。cache-first 戦略 (§3.2.7.1)、サイズ上限
 * 11MB の根拠 (§3.2.7.2)、解像度測定 inJustDecodeBounds (§3.2.7.3)、失敗時の cache 即削除 +
 * typed wire error (§3.2.7.4)、省略機能 (§3.2.7.5) を参照。
 */
object ImageProcessor {
    private const val MAX_BYTES: Long = 11L * 1024 * 1024

    suspend fun encode(context: Context, uri: Uri): ImageAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"

        val cacheFile = File(context.cacheDir, "attach-${UUID.randomUUID()}.bin")
        try {
            resolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw PhoneWireError.Send.ImageTooLarge(0L, MAX_BYTES).asException()

            val bytes = cacheFile.length()
            if (bytes <= 0) {
                throw PhoneWireError.Send.ImageTooLarge(0L, MAX_BYTES).asException()
            }
            if (bytes > MAX_BYTES) {
                throw PhoneWireError.Send.ImageTooLarge(bytes, MAX_BYTES).asException()
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
            val longest = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(0)

            ImageAttachment(
                localPath = cacheFile.absolutePath,
                mime = mime,
                sizeBytes = bytes,
                longestEdge = longest,
            )
        } catch (t: Throwable) {
            // docs/03 §3.2.7.4: 全失敗経路で cache を残さない。
            runCatching { cacheFile.delete() }
            throw t
        }
    }
}
