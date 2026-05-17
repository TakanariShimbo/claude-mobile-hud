package com.example.claudemobilehud.phone.ui.util

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** docs/03 §3.5.1.10: BitmapFactory.decodeFile を remember(path) で Composition cache。decode 失敗は null fallback。 */
@Composable
fun rememberImageBitmapFromPath(path: String?): ImageBitmap? = remember(path) {
    if (path.isNullOrBlank()) return@remember null
    runCatching {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }.getOrNull()
}
