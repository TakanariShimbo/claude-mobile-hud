package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable

/**
 * Hub への接続状態 (永続)。Phase 3 §3.2.4。
 *
 * - `Idle`: Settings 未設定など (NotConfigured 相当)
 * - `Connecting`: 接続試行中
 * - `Open`: SSE 接続中・正常
 * - `Failed(reason)`: 一時失敗、exp backoff で再試行中
 * - `AuthFailed`: 401 で恒久失敗。手動 `reconnect()` トリガ待ち。
 *
 * P3-A of 4c2 review: `@Immutable` で ConnectionLine / Effects の引数 stability を
 * 保つ。`Failed(reason)` だけ data class だが reason: String は immutable。
 */
@Immutable
sealed class ConnectivityState {
    data object Idle : ConnectivityState()
    data object Connecting : ConnectivityState()
    data object Open : ConnectivityState()
    data class Failed(val reason: String) : ConnectivityState()
    data object AuthFailed : ConnectivityState()
}
