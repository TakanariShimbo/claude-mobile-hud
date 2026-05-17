package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable

/** docs/03 §3.6.5.5: 5 状態。AuthFailed は手動 reconnect 待ちで auto retry しない (NFR-20)。 */
@Immutable
sealed class ConnectivityState {
    data object Idle : ConnectivityState()
    data object Connecting : ConnectivityState()
    data object Open : ConnectivityState()
    data class Failed(val reason: String) : ConnectivityState()
    data object AuthFailed : ConnectivityState()
}
