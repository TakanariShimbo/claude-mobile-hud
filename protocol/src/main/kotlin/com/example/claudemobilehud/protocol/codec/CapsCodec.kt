package com.example.claudemobilehud.protocol.codec

import com.example.claudemobilehud.protocol.WireEvent

/**
 * Caps の生成/解析を抽象化。実体は Phone / Glass app 側で Rokid CXR-L SDK を呼ぶ。
 * :protocol を Android-free に保つための seam (Phase 3 §2.5)。
 *
 * **同期 API である理由**: CXR-L 1.0.1 では Caps の組み立て・解析は CPU 同期で行われ、
 * I/O は別レイヤ (`sendCaps` 等) で扱う。よってこの seam に `suspend` は不要。
 * 将来 SDK が async 化したら `suspend` 版を追加し、両方を提供する。
 */
interface CapsFactory {
    fun encode(event: WireEvent): ByteArray
    fun decode(bytes: ByteArray): WireEvent?
}

class CapsCodec(private val factory: CapsFactory) : Codec {
    override fun encode(event: WireEvent): ByteArray = factory.encode(event)
    override fun decode(bytes: ByteArray): WireEvent? = factory.decode(bytes)
}
