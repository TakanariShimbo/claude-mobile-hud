package com.example.claudemobilehud.phone.data

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ユーザがスキャンキャンセルした (= 戻る等で閉じた) 時に sentinel として投げる。
 *
 * **P2-D of 5-6 review**: `RuntimeException` 継承のまま (= `runCatching` で `Result.failure`
 * に入る挙動を保持) でユーザ能動キャンセルを `onFailure` 側で `is QrScanCancelled` で抑制
 * する設計。`CancellationException` 継承だと structured concurrency が「親 scope 自体が
 * cancelされた」と見なし、SettingsDialog 側のエラー枝を通らない可能性があるため敢えて
 * 区別する。
 */
class QrScanCancelled : RuntimeException("scan cancelled")

/**
 * Google Play Services の Code Scanner を suspend ラッパで包むだけのオブジェクト。
 *
 * **使う側の責務**: 結果文字列を [Pairing.parse] に流して Settings に取り込む。エラー UI は
 * 呼出側 (SettingsDialog) で snackbar / inline text に出す。
 *
 * **テスト不在の理由**: 中身はほぼ Play Services API への薄いラップ。Robolectric / GMS mock
 * は coverage 比でのコストが大きすぎる。Pairing.parse 側の pure unit test で payload 解析
 * を完全に固めれば、QrScanner 自体は ML Kit が動くか否かしか責務がない。
 */
object QrScanner {
    suspend fun scan(context: Context): String =
        suspendCancellableCoroutine { cont ->
            // P2-E of 5-6 review: ML Kit Code Scanner は外部から閉じる API を提供しないため、
            // coroutine cancel が来てもこちらから scanner Activity を dismiss する手段は無い。
            // ユーザの back キー操作で `addOnCanceledListener` が late で発火するのを待つ。
            // 念のため invokeOnCancellation を登録し、resume 二重発火を防ぐ正常経路を明示。
            cont.invokeOnCancellation {
                // no-op: ML Kit が cancel API を出さないので静観する。後続の Success/Cancel
                // listener が cont の `isActive` チェックで弾かれて二重 resume にはならない。
            }
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
