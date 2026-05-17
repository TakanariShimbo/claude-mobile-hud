package com.example.claudemobilehud.phone.data.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Realtime API (transcription-only) JSON encode/decode (docs/03 §3.2.5.10)。
 * 関心ある event type 4 種のマッピング表と `session.created` を除外する理由は §3.2.5.10 を参照。
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
