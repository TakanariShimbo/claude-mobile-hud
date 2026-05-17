package com.example.claudemobilehud.phone.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** docs/03 §3.5.1.10: Compose LocalContext から Activity を抽出 (ContextWrapper チェーンを baseContext で辿る)。 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
