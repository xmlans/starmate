package org.xmyun.starmate.core.engine

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.ConfirmDecision
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.ConfirmationBroker

/**
 * 同一时间只显示一个确认请求，后来的按先后排队（Mutex 的等待是先进先出的）。
 * App 不在前台时立即返回未批准。
 */
@Singleton
class DefaultConfirmationBroker @Inject constructor(
    private val appState: AppState,
) : ConfirmationBroker {

    private val queue = Mutex()
    private val _pending = MutableStateFlow<ConfirmRequest?>(null)
    override val pending: StateFlow<ConfirmRequest?> = _pending.asStateFlow()

    private class Waiting(val requestId: String, val deferred: CompletableDeferred<ConfirmDecision>)

    private val waiting = AtomicReference<Waiting?>(null)

    override suspend fun confirm(request: ConfirmRequest): ConfirmDecision {
        if (!appState.isForeground.value) return DENIED
        return queue.withLock {
            // 排队期间可能已经退到后台
            if (!appState.isForeground.value) return@withLock DENIED
            val current = Waiting(request.id, CompletableDeferred())
            waiting.set(current)
            _pending.value = request
            try {
                val decision = current.deferred.await()
                decision.copy(remember = decision.approved && decision.remember && request.allowRemember)
            } finally {
                // 被取消（用户离开聊天页）时也要把卡片收起来
                waiting.compareAndSet(current, null)
                _pending.compareAndSet(request, null)
            }
        }
    }

    override fun resolve(requestId: String, decision: ConfirmDecision) {
        val current = waiting.get() ?: return
        if (current.requestId == requestId) current.deferred.complete(decision)
    }

    private companion object {
        val DENIED = ConfirmDecision(approved = false, remember = false)
    }
}
