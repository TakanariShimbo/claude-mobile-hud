package com.example.claudemobilehud.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationMode {
    @SerialName("idle") IDLE,
    @SerialName("listening") LISTENING,
    @SerialName("confirming") CONFIRMING,
    @SerialName("permission_confirming") PERMISSION_CONFIRMING,
}

@Serializable
enum class TranscriptState {
    @SerialName("idle") IDLE,
    @SerialName("connecting") CONNECTING,
    @SerialName("listening") LISTENING,
    @SerialName("error") ERROR,
}

@Serializable
enum class NotificationKind {
    @SerialName("reply") REPLY,
    @SerialName("permission") PERMISSION,
}

@Serializable
enum class GestureKind {
    @SerialName("tap") TAP,
    @SerialName("double_tap") DOUBLE_TAP,
    @SerialName("swipe_forward") SWIPE_FORWARD,
    @SerialName("swipe_back") SWIPE_BACK,
}

@Serializable
enum class PermissionDecision {
    @SerialName("allow") ALLOW,
    @SerialName("deny") DENY,
}

@Serializable
enum class MessageRole {
    @SerialName("out") OUTGOING,
    @SerialName("in") INCOMING,
    @SerialName("sys") SYSTEM,
}

@Serializable
enum class MicSource {
    @SerialName("glass") GLASS,
    @SerialName("phone_fallback") PHONE_FALLBACK,
}
