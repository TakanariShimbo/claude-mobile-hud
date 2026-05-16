package com.example.claudemobilehud.phone.data.error

import com.example.claudemobilehud.protocol.error.SharedWireError

/**
 * `ChannelRepository.errors: SharedFlow<TransientError>` の要素型。Phase 3 §3.2.1。
 *
 * `:protocol.SharedWireError` と Phone-local の `PhoneWireError` を 1 つの sealed で
 * 束ねるための discriminated wrapper。UI 側で `mapToPresentation` を経由して
 * `Snackbar / Dialog / Banner` に振り分ける。
 *
 * NOTE: 永続状態 (例: AuthFailed) は `ChannelRepository.connectivity: StateFlow<ConnectivityState>`
 * 側で扱う。ここに流れる error は **一過性** (snackbar 系) に限る。
 */
sealed class TransientError {
    data class Shared(val error: SharedWireError) : TransientError()
    data class Phone(val error: PhoneWireError) : TransientError()
}
