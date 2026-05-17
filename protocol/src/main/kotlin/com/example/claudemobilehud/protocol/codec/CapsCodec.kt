package com.example.claudemobilehud.protocol.codec

import com.example.claudemobilehud.protocol.WireEvent

/** docs/03 §2.5.3: :protocol を Android-free に保つ Caps seam。同期 API。 */
interface CapsFactory {
    fun encode(event: WireEvent): ByteArray
    fun decode(bytes: ByteArray): WireEvent?
}

class CapsCodec(private val factory: CapsFactory) : Codec {
    override fun encode(event: WireEvent): ByteArray = factory.encode(event)
    override fun decode(bytes: ByteArray): WireEvent? = factory.decode(bytes)
}
