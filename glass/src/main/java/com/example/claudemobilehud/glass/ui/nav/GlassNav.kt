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

/** docs/03 §4.11.3: 2 route + pop semantics。FR-GL-52 (跨ぎ状態保持) は P2-B で 5c に保留。 */
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
            ConversationScreen(onBack = {
                nav.popBackStack(GlassRoutes.SESSION_SELECT, inclusive = false)
            })
        }
    }
}
