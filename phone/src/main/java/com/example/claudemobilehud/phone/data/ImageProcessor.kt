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
 * 添付画像の取り込み。Phase 3 §6.2.4 + §3.2.1。
 *
 * - 画像 picker から来た [Uri] を **Phone-local の cache file にコピー**して
 *   [ImageAttachment.localPath] を返す。base64 化は send 直前 (Bridge への
 *   POST 段階) で行うため、長期保管は path のみで済む。
 * - サイズ / MIME / 解像度 hint を `BitmapFactory.Options(inJustDecodeBounds=true)`
 *   で測定して `ImageAttachment` に乗せる。
 *
 * P2-E of 4c2 review: 失敗時は `PhoneWireError.Send.ImageTooLarge.asException()` の
 * 形で typed wire 例外を投げる。呼出側 (Repository) は `emitErrorFromThrowable` を
 * 通して `errors` flow に流せば、UI の snackbar が既存の localized メッセージ経路で
 * 表示する (`MainScreenEffects.toUserMessage`)。`IllegalArgumentException` の生
 * `message` を表示すると i18n / フォーマットが UI 経路と一貫しない。
 *
 * P2-F: validation 失敗時は cache file を即 delete (cacheDir に残しっぱなしを防ぐ)。
 *
 * **意図的に省略している機能** (現スコープでは不要):
 *   - 自動リサイズ / 再エンコード (Bridge 側で行う)。
 *   - EXIF 回転補正 (画像表示時に Compose が対応)。
 *   - HEIC 変換 (Android が picker 経由でデコードできる形式に限定)。
 */
object ImageProcessor {
    // Hub 側 `BODY_LIMIT_BYTES = 16MB` (image_base64 込みの POST body)。base64 化で
    // 4/3 倍に膨らむ + JSON フレーム overhead を見込んで、raw 上限は 11MB とする。
    // これより大きい画像を選んだ場合は picker 受け取り時点で即 fail-fast (ImageTooLarge)
    // させ、大容量 upload を Hub に投げてから 413 で叩き落とされる UX を避ける。
    private const val MAX_BYTES: Long = 11L * 1024 * 1024

    suspend fun encode(context: Context, uri: Uri): ImageAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"

        // 1. cache に複製
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

            // 2. 解像度測定 (bounds only — 全データロードはしない)
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
            // どの失敗経路でも cache を残さない (P2-F)。
            runCatching { cacheFile.delete() }
            throw t
        }
    }
}
