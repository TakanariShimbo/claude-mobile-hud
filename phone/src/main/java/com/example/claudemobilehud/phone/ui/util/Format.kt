package com.example.claudemobilehud.phone.ui.util

import com.example.claudemobilehud.phone.data.SessionStore

/**
 * Session id (UUID v4 もしくは `UNKNOWN_SESSION_ID` sentinel) を表示用に短縮する。
 * - `UNKNOWN_SESSION_ID` (FR-PH-55 の暫定 bucket) → `"unknown"`
 * - その他は先頭 8 文字 (UUID なら判別に十分)
 */
fun shortSessionLabel(id: String): String =
    if (id == SessionStore.UNKNOWN_SESSION_ID) "unknown" else id.take(8)
