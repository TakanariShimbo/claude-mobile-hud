package com.example.claudemobilehud.phone.data.error

import com.example.claudemobilehud.protocol.error.SharedWireError

/** docs/03 §3.7.1: ChannelRepository.errors の一過性 error wrapper。永続状態は connectivity 側 (§3.6.5.5)。 */
sealed class TransientError {
    data class Shared(val error: SharedWireError) : TransientError()
    data class Phone(val error: PhoneWireError) : TransientError()
}
