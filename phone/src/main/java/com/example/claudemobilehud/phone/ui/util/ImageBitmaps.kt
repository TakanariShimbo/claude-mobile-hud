package com.example.claudemobilehud.phone.ui.util

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * `ImageAttachment.localPath` から `ImageBitmap` を生成して、Composition 範囲で
 * cache する。Bitmap デコードはメモリと CPU を食うので recomposition のたびに
 * やり直さないよう `remember(path)` で keying する。
 */
@Composable
fun rememberImageBitmapFromPath(path: String?): ImageBitmap? = remember(path) {
    if (path.isNullOrBlank()) return@remember null
    runCatching {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }.getOrNull()
}
