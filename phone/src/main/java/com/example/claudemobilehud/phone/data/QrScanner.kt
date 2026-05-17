package com.example.claudemobilehud.phone.data

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ユーザ能動キャンセル sentinel。`CancellationException` ではなく `RuntimeException` 系で
 * 投げる理由は docs/03 §3.2.6.4 を参照。
 */
class QrScanCancelled : RuntimeException("scan cancelled")

/**
 * GMS Code Scanner の suspend wrapper (docs/03 §3.2.6)。cancel 取り扱い (§3.2.6.4)、
 * テスト方針は `Pairing.parse` 側 pure unit test に集約 (§3.2.6.4 末尾) を参照。
 */
object QrScanner {
    suspend fun scan(context: Context): String =
        suspendCancellableCoroutine { cont ->
            // docs/03 §3.2.6.4: ML Kit は外部 cancel API を持たないので no-op。後続 listener の
            // cont.isActive チェックで二重 resume を防ぐ。
            cont.invokeOnCancellation { }
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(context, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue
                    if (raw.isNullOrBlank()) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("QR の中身が読み取れませんでした"),
                            )
                        }
                    } else {
                        if (cont.isActive) cont.resume(raw)
                    }
                }
                .addOnCanceledListener {
                    if (cont.isActive) cont.resumeWithException(QrScanCancelled())
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
}
