package org.xmyun.starmate.feature.mobileuse.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.ui.contract.AppNavigator
import org.xmyun.starmate.core.ui.contract.StarmateRoutes
import org.xmyun.starmate.feature.mobileuse.agent.AppReturner

/**
 * 打开星伴的聊天页。Android 10 起不许应用随意从后台打开界面，但有两条例外在这时都成立：
 * 星伴的无障碍服务正由系统绑定着，任务的悬浮条也还显示着（调用方保证在收起悬浮条之前调用）。
 *
 * 打开后最多等 [WAIT_MS] 看星伴是否真的到了前台，结果写进诊断日志，方便真机上排查。
 */
class AndroidAppReturner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navigator: AppNavigator,
    private val appState: AppState,
    private val diag: DiagLog,
) : AppReturner {

    override suspend fun bringBack() {
        // 任务没离开过星伴（例如一开始就失败），不用再打开一次
        if (appState.isForeground.value) return
        val started = withContext(Dispatchers.Main) {
            try {
                context.startActivity(navigator.intentFor(StarmateRoutes.CHAT))
                true
            } catch (e: Exception) {
                diag.log(TAG, "could not open Starmate after the task: ${e.javaClass.simpleName}")
                false
            }
        }
        if (!started) return
        val back = withTimeoutOrNull(WAIT_MS) { appState.isForeground.first { it } } ?: false
        diag.log(TAG, if (back) "returned to Starmate after the task" else "system did not bring Starmate to the front")
    }

    private companion object {
        const val TAG = "mobileuse"
        const val WAIT_MS = 1_500L
    }
}
