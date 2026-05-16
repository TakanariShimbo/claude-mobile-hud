package com.example.claudemobilehud.protocol.codec

import com.example.claudemobilehud.protocol.WireEvent

interface Codec {
    fun encode(event: WireEvent): ByteArray
    fun decode(bytes: ByteArray): WireEvent?
}
