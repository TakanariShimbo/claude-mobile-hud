package com.example.claudemobilehud.phone.ui

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * MainScreen 内で共有する「dialog open/close + drawer + snackbar」状態ホルダ。
 * Phase 3 §3.5.1。`@Stable` を付けて compose 側の skip 最適化を許可。
 *
 * P1-A of 4c2 review: dialog 自動表示の判定 (Idle / AuthFailed → settings を開く)
 * はすべて [MainScreenEffects] に集約され、ここでは state container のみ提供する。
 * connectivity 引数を受け取らない設計に変更。
 */
@Stable
class MainScreenDialogState(
    val snackbar: SnackbarHostState,
    val drawer: DrawerState,
) {
    var showSettings by mutableStateOf(false)
    var showGlass by mutableStateOf(false)
    var showExit by mutableStateOf(false)

    /** 削除確認 dialog の対象 session id。null = dialog 閉じている。 */
    var pendingDeleteSessionId by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberMainScreenDialogState(): MainScreenDialogState {
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(initialValue = DrawerValue.Closed)
    return remember { MainScreenDialogState(snackbar = snackbar, drawer = drawer) }
}
