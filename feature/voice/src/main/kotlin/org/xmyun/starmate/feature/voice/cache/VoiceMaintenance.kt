package org.xmyun.starmate.feature.voice.cache

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.feature.voice.VoiceAttachments
import org.xmyun.starmate.feature.voice.tts.VoiceSynthesizer

/** 清理旧语音：删掉文件，再把引用这些文件的附件改成 failed（文字稿仍可看）。 */
class VoiceMaintenance @Inject constructor(
    private val cache: VoiceCache,
    private val messages: MessageStore,
    private val synthesizer: VoiceSynthesizer,
    private val clock: Clock,
) {
    /** 返回删掉的文件数。 */
    suspend fun cleanOlderThan(days: Long = DEFAULT_DAYS): Int {
        val now = clock.instant()
        val deleted = withContext(Dispatchers.IO) { cache.deleteOlderThan(now.minus(Duration.ofDays(days))) }
        if (deleted.isEmpty()) return 0
        for (message in messages.between(Instant.EPOCH, now.plusSeconds(60))) {
            val ids = message.attachments.mapNotNull { attachment ->
                VoiceAttachments.read(attachment)?.takeIf { it.file != null && it.file in deleted }?.id
            }.toSet()
            if (ids.isNotEmpty()) synthesizer.markFilesMissing(message.id, ids)
        }
        return deleted.size
    }

    companion object {
        const val DEFAULT_DAYS = 30L
    }
}
