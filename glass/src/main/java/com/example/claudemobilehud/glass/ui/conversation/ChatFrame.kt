package com.example.claudemobilehud.glass.ui.conversation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.glass.ui.theme.TextGreen

/**
 * 会話領域を囲む細いグリーンの枠。Rokid HUD では塗りつぶしは眩しいので、領域分けはこの
 * border だけで表現する (POC 踏襲)。
 */
@Composable
fun ChatFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier.clip(shape).border(1.dp, TextGreen, shape)) {
        content()
    }
}
