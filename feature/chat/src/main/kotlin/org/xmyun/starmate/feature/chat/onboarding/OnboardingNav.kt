package org.xmyun.starmate.feature.chat.onboarding

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.xmyun.starmate.feature.chat.common.ChatRoutes

/** 引导流程的路由。进入引导时聊天页已从返回栈移除，结束时清空返回栈回到聊天。 */
fun NavGraphBuilder.onboardingRoutes(nav: NavController) {
    composable(ChatRoutes.ONBOARDING_WELCOME) {
        WelcomeScreen(
            viewModel = hiltViewModel(),
            onStart = { nav.navigate(ChatRoutes.ONBOARDING_ADULT) },
            onRestored = { next -> nav.goToStep(next) },
        )
    }
    composable(ChatRoutes.ONBOARDING_ADULT) {
        AdultConfirmScreen(
            viewModel = hiltViewModel(),
            onBack = { nav.popBackStackOrNull() },
            onNext = { next -> nav.goToStep(next) },
        )
    }
    composable(ChatRoutes.ONBOARDING_MODEL) {
        val onboarding: OnboardingViewModel = hiltViewModel()
        ModelSetupScreen(
            viewModel = hiltViewModel(),
            onBack = nav.backOrNull(),
            onDone = { onboarding.afterModel { next -> nav.goToStep(next) } },
        )
    }
    composable(ChatRoutes.ONBOARDING_COMPANION) {
        CompanionSetupScreen(
            viewModel = hiltViewModel(),
            onBack = nav.backOrNull(),
            onDone = { nav.goToStep(null) },
        )
    }
    composable(ChatRoutes.ONBOARDING_PERMISSIONS) {
        PermissionInviteScreen(
            viewModel = hiltViewModel(),
            onDone = {
                if (!nav.popBackStack()) nav.goToStep(null)
            },
        )
    }
}

/** null 表示引导完成：清空返回栈，聊天页成为根。 */
fun NavController.goToStep(next: String?) {
    if (next == null) {
        navigate(ChatRoutes.CHAT) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    } else {
        navigate(next)
    }
}

/** 从聊天页进入引导：把聊天页移出返回栈，引导里按返回键不会回到聊天页再被弹回来。 */
fun NavController.startOnboarding(route: String) {
    navigate(route) {
        popUpTo(ChatRoutes.CHAT) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavController.popBackStackOrNull() {
    popBackStack()
}

/** 返回栈里还有上一页时才给返回键（从聊天页直接跳进来的那一步没有上一页）。 */
private fun NavController.backOrNull(): (() -> Unit)? =
    if (previousBackStackEntry != null) {
        { popBackStack() }
    } else {
        null
    }
