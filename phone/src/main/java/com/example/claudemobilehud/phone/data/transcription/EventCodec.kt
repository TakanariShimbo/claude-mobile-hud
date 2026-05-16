package com.example.claudemobilehud.phone.data.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Realtime API (transcription-only) との JSON 往復をエンコード / デコードする。
 *
 * **送出 (encode)**:
 *   - [sessionUpdate]: 接続直後に 1 度だけ送る `session.update`。サンプリングレート /
 *     transcription model を確定させる。
 *   - [appendAudio]: 音声 chunk 1 つを base64 で乗せる `input_audio_buffer.append`。
 *
 * **受信 (decode)**: 我々が興味あるのは下記 4 種類のみ。他は ignore。
 *   - `transcription_session.updated` / `session.updated` → [TranscriptionEvent.SessionReady]
 *     (API 側のバージョン差を吸収するため両方を許容)。
 *   - `conversation.item.input_audio_transcription.delta` → [TranscriptionEvent.Delta]
 *   - `conversation.item.input_audio_transcription.completed` → [TranscriptionEvent.Completed]
 *   - `error` → [TranscriptionEvent.Error]
 *
 * 設計判断: `session.created` は接続直後の通知で session.update 反映前のため、
 * これを SessionReady とみなすと音声送信開始のタイミングが早すぎて初回 chunk が
 * drop される (POC で確認済み)。よって除外。
 */
internal object EventCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun sessionUpdate(config: TranscriptionConfig): String {
        val payload = buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("type", "transcription")
                    put(
                        "audio",
                        buildJsonObject {
                            put(
                                "input",
                                buildJsonObject {
                                    put(
                                        "format",
                                        buildJsonObject {
                                            put("type", "audio/pcm")
                                            put("rate", config.sampleRateHz)
                                        },
                                    )
                                    put(
                                        "transcription",
                                        buildJsonObject {
                                            put("model", config.transcriptionModel)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    fun appendAudio(base64: String): String {
        val payload = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", base64)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    fun decode(text: String): TranscriptionEvent? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (type) {
            "transcription_session.updated",
            "session.updated" -> TranscriptionEvent.SessionReady
            "conversation.item.input_audio_transcription.delta" ->
                TranscriptionEvent.Delta(obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "conversation.item.input_audio_transcription.completed" ->
                TranscriptionEvent.Completed(obj["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "error" -> {
                val msg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: obj.toString()
                TranscriptionEvent.Error(msg)
            }
            else -> null
        }
    }
}
