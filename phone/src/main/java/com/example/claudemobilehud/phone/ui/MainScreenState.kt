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

/** docs/03 §3.5.1.5: dialog/drawer/snackbar の transient state holder。connectivity 引数は取らない (P1-A)。 */
@Stable
class MainScreenDialogState(
    val snackbar: SnackbarHostState,
    val drawer: DrawerState,
) {
    var showSettings by mutableStateOf(false)
    var showGlass by mutableStateOf(false)
    var showExit by mutableStateOf(false)

    /** null = 削除確認 dialog 閉じている。 */
    var pendingDeleteSessionId by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberMainScreenDialogState(): MainScreenDialogState {
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(initialValue = DrawerValue.Closed)
    return remember { MainScreenDialogState(snackbar = snackbar, drawer = drawer) }
}
