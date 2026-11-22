package org.xmyun.starmate.core.engine

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatDraftBus

/** 只保存一个待发附件；新的覆盖旧的；consume 取走后清空。 */
@Singleton
class DefaultChatDraftBus @Inject constructor() : ChatDraftBus {

    private val _pending = MutableStateFlow<Attachment?>(null)
    override val pending: StateFlow<Attachment?> = _pending.asStateFlow()

    override fun offer(attachment: Attachment) {
        _pending.value = attachment
    }

    override fun consume(): Attachment? = _pending.getAndUpdate { null }
}
