package com.example.claudemobilehud.glass.ui.conversation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.glass.ui.theme.TextGreen

/** docs/03 §4.11.5: HUD は塗りつぶしが眩しいので border 1dp + 黒背景で領域分け。 */
@Composable
fun ChatFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier.clip(shape).border(1.dp, TextGreen, shape)) {
        content()
    }
}
