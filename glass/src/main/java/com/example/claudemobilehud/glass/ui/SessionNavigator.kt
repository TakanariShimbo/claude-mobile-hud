package com.example.claudemobilehud.glass.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 通知や CXR 経由の自動遷移を Compose の NavController に橋渡しする小さな bus
 * (POC 踏襲)。MainActivity (lifecycleScope) から requestConversation() を呼び、
 * NavHost を持つ composable が LaunchedEffect で collect して `nav.navigate` する。
 *
 * どの session に行くかは **phone 側 currentSessionId が真実** で、caller は別途
 * [com.example.claudemobilehud.glass.glass.GlassBridge.sendSelectSession] を叩く前提。
 * この bus は「conversation 画面を出して」という signal のみを担当するため Unit。
 */
internal object SessionNavigator {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun requestConversation() {
        _requests.tryEmit(Unit)
    }
}
