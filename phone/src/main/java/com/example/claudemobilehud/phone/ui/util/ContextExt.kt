package com.example.claudemobilehud.phone.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Compose の `LocalContext.current` から Activity を引きずり出す拡張。
 * `Activity → ContextThemeWrapper → …` のように入れ子になっている場合があるので
 * baseContext を辿って最初の Activity を返す。
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
