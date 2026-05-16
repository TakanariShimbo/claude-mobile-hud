package com.example.claudemobilehud.protocol.codec

import com.example.claudemobilehud.protocol.WireEvent

/**
 * Caps の生成/解析を抽象化。実体は Phone / Glass app 側で Rokid CXR-L SDK を呼ぶ。
 * :protocol を Android-free に保つための seam (Phase 3 §2.5)。
 */
interface CapsFactory {
    fun encode(event: WireEvent): ByteArray
    fun decode(bytes: ByteArray): WireEvent?
}

class CapsCodec(private val factory: CapsFactory) : Codec {
    override fun encode(event: WireEvent): ByteArray = factory.encode(event)
    override fun decode(bytes: ByteArray): WireEvent? = factory.decode(bytes)
}
