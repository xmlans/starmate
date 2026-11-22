package org.xmyun.starmate.feature.voice.tts

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.feature.voice.VoiceAttachments

/**
 * 找到语音附件所在的消息 id。
 *
 * ReplyPostProcessor.finish 在消息保存之前调用，拿不到消息 id。引擎保存消息后会通知 TurnListener，
 * 这里借此把语音 id 对应到消息 id；万一没等到通知（超时），再到最近的消息里按语音 id 找。
 */
@Singleton
class VoiceMessageLocator @Inject constructor(
    private val messages: MessageStore,
) : TurnListener {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    /** 在 finish 返回之前登记，保证不会错过随后的通知。 */
    fun expect(voiceId: String) {
        pending.putIfAbsent(voiceId, CompletableDeferred())
    }

    override suspend fun onTurnCompleted(turn: CompletedTurn) {
        val message = turn.assistantMessage
        for (attachment in message.attachments) {
            val id = VoiceAttachments.read(attachment)?.id ?: continue
            pending[id]?.complete(message.id)
        }
    }

    suspend fun await(voiceId: String, timeoutMs: Long): Long? {
        try {
            val deferred = pending[voiceId]
            val id = deferred?.let { withTimeoutOrNull(timeoutMs) { it.await() } }
            return id ?: scan(voiceId)
        } finally {
            pending.remove(voiceId)
        }
    }

    private suspend fun scan(voiceId: String): Long? =
        messages.recent(SCAN_LIMIT).lastOrNull { message ->
            message.attachments.any { VoiceAttachments.read(it)?.id == voiceId }
        }?.id

    private companion object {
        const val SCAN_LIMIT = 100
    }
}
