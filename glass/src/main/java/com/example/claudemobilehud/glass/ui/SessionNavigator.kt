package com.example.claudemobilehud.glass.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** docs/03 §4.11.2: 通知 / CXR → NavController 橋渡しの signal-only bus (Unit で十分)。 */
internal object SessionNavigator {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun requestConversation() {
        _requests.tryEmit(Unit)
    }
}
