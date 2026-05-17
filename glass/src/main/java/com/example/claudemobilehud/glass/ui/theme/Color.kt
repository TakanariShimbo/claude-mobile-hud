package com.example.claudemobilehud.glass.ui.theme

import androidx.compose.ui.graphics.Color

// Rokid Glass は単色緑モノクロ HUD。輝度のみが効くが、ソース側を緑系に揃えると
// HUD 発色と PC mirror (scrcpy) の見え方のトーンが一致して読みやすい。
val GlassBackground = Color.Black

/** 主要 (active) テキスト。HUD では「明るい」緑として描画される。 */
val TextGreen = Color(0xFF4DFF6F)

/** 副次 (hint / inactive) テキスト。HUD では「やや暗い」緑。 */
val TextGreenDim = Color(0xFF7FCC8A)

/**
 * 非選択行 / 非 latest メッセージなど「アクティブでない」テキスト。
 * HUD は G チャンネルしか効かないが、PC mirror で確認するときに緑トーンに揃えた方が
 * "dim な緑" として見える。G=0xB0 (176) は max 255 から十分落ちる読みやすい輝度
 * (0x80 だと暗すぎ)。
 */
val TextInactive = Color(0xFF5DB070)

/** focus 矢印 (▶) の薄い側。session select で非 focused 行に使う。 */
val TextChevronDim = Color(0xFF4A8C58)
