package org.xmyun.starmate.feature.chat.interview

import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.feature.chat.common.ChatBackgroundScope

/**
 * 每轮结束后：暂存资料期间用户又发了消息就落盘；访谈回答满 12 条就抽取。
 * 抽取要调模型，另起协程，不占着引擎的这一轮。
 */
class InterviewTurnListener @Inject constructor(
    // Provider：协调器经 ProfileApplier 依赖 MemoryWriter，后者可能又依赖引擎，直接注入可能成环
    private val coordinator: Provider<InterviewCoordinator>,
    private val background: ChatBackgroundScope,
    private val diag: DiagLog,
) : TurnListener {

    override suspend fun onTurnCompleted(turn: CompletedTurn) {
        background.scope.launch {
            try {
                coordinator.get().onTurnCompleted(turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log("chat.onboarding", "turn handling failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
