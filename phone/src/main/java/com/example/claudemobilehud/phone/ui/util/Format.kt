package com.example.claudemobilehud.phone.ui.util

import com.example.claudemobilehud.phone.data.SessionStore

/** docs/03 §3.5.1.10: UUID 先頭 8 文字。UNKNOWN_SESSION_ID sentinel は "unknown" に変換。 */
fun shortSessionLabel(id: String): String =
    if (id == SessionStore.UNKNOWN_SESSION_ID) "unknown" else id.take(8)
