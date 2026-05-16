package com.example.claudemobilehud.glass.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.claudemobilehud.glass.ui.conversation.ConversationScreen
import com.example.claudemobilehud.glass.ui.sessionselect.SessionSelectScreen

object GlassRoutes {
    const val SESSION_SELECT = "session_select"
    const val CONVERSATION = "conversation"
}

/**
 * Glass app の NavHost。startDestination は session 選択画面。
 *
 * 通知から conversation に飛ぶ場合は MainActivity 側で [SessionNavigator] 経由で
 * `nav.navigate(GlassRoutes.CONVERSATION) { popUpTo(SESSION_SELECT) }` を呼ぶ。
 */
@Composable
fun GlassNavHost(nav: NavHostController, onExit: () -> Unit) {
    NavHost(navController = nav, startDestination = GlassRoutes.SESSION_SELECT) {
        composable(GlassRoutes.SESSION_SELECT) {
            SessionSelectScreen(
                onSelected = {
                    nav.navigate(GlassRoutes.CONVERSATION) {
                        popUpTo(GlassRoutes.SESSION_SELECT) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onExit = onExit,
            )
        }
        composable(GlassRoutes.CONVERSATION) {
            // P2-B of 5b review: `popBackStack(SESSION_SELECT, inclusive=false)` は
            // CONVERSATION の back stack entry を **dispose** する。再 nav で
            // ConversationStateHolder の `remember` 状態 (`sendChoice` / `permissionChoice`
            // のホバー位置) は破棄される。FR-GL-52「セッション跨ぎで確認状態を保持」は
            // Should 要件であり現段階では未対応。Holder の state を Activity 層に持ち
            // 上げる必要があるため、5c 以降の課題として保留する。
            ConversationScreen(onBack = {
                nav.popBackStack(GlassRoutes.SESSION_SELECT, inclusive = false)
            })
        }
    }
}
