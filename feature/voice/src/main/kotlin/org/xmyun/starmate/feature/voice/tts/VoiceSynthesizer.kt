package org.xmyun.starmate.feature.voice.tts

import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.feature.voice.VoiceAttachments
import org.xmyun.starmate.feature.voice.VoiceWorkScope
import org.xmyun.starmate.feature.voice.cache.AudioDurationProbe
import org.xmyun.starmate.feature.voice.cache.VoiceCache

/**
 * 一段待合成的语音。
 * @param transcript 文字稿（记账按它的长度）。
 * @param ttsText 实际送进引擎的文字（去掉表情、Markdown 记号后）。
 */
data class VoiceJob(
    val voiceId: String,
    val transcript: String,
    val ttsText: String,
    val tone: String?,
    val purpose: TurnPurpose,
)

/** 后处理器把语音段交给它，立即返回。 */
interface VoiceJobSink {
    fun enqueue(jobs: List<VoiceJob>)
}

/**
 * 在自己的作用域里合成语音，完成后把消息里对应的附件改成 ready 或 failed。
 * 更新附件时先重新读出消息，只改那一个附件，避免冲掉别处对这条消息的修改。
 */
@Singleton
class VoiceSynthesizer @Inject constructor(
    private val engines: TtsEngineProvider,
    private val cache: VoiceCache,
    private val probe: AudioDurationProbe,
    private val ledger: UsageLedger,
    private val messages: MessageStore,
    private val locator: VoiceMessageLocator,
    private val diag: DiagLog,
    private val clock: Clock,
    private val work: VoiceWorkScope,
) : VoiceJobSink {

    private val _active = MutableStateFlow<Set<String>>(emptySet())

    /** 正在合成（或等待合成）的语音 id。界面据此区分“录音中”和重启后残留的 recording 状态。 */
    val activeIds: StateFlow<Set<String>> = _active.asStateFlow()

    private val updateMutex = Mutex()

    private sealed interface Outcome {
        data class Ready(val file: String, val durationMs: Long?) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    override fun enqueue(jobs: List<VoiceJob>) {
        if (jobs.isEmpty()) return
        jobs.forEach { locator.expect(it.voiceId) }
        _active.update { it + jobs.map(VoiceJob::voiceId) }
        work.scope.launch {
            // 同一条消息里的多段按顺序合成
            for (job in jobs) {
                try {
                    process(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diag.log(TAG, "voice job failed: ${e.javaClass.simpleName}")
                } finally {
                    _active.update { it - job.voiceId }
                }
            }
        }
    }

    private suspend fun process(job: VoiceJob) {
        val outcome = try {
            synthesize(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        }
        if (outcome is Outcome.Failed) diag.log(TAG, "synthesis failed: ${outcome.reason}")

        val messageId = locator.await(job.voiceId, LOCATE_TIMEOUT_MS)
        if (messageId == null) {
            diag.log(TAG, "message for voice ${job.voiceId} not found")
            return
        }
        updateAttachments(messageId, setOf(job.voiceId)) { attachment ->
            when (outcome) {
                is Outcome.Ready -> VoiceAttachments.ready(attachment, outcome.file, outcome.durationMs)
                is Outcome.Failed -> VoiceAttachments.failed(attachment)
            }
        }
    }

    private suspend fun synthesize(job: VoiceJob): Outcome {
        cache.find(job.voiceId)?.let { hit ->
            return Outcome.Ready(hit.name, probe.durationMs(hit))
        }
        val engine = engines.current() ?: return Outcome.Failed("no engine")
        cache.ensureDir()
        val temp = cache.tempFileFor(job.voiceId, engine.format)
        val target = cache.fileFor(job.voiceId, engine.format)
        return when (val result = engine.synthesize(job.ttsText, job.tone, temp)) {
            TtsResult.Success -> {
                if (!temp.isFile || temp.length() == 0L) {
                    temp.delete()
                    return Outcome.Failed("empty audio")
                }
                if (!moveInto(temp, target)) return Outcome.Failed("could not save audio")
                record(job.purpose, engine.model, job.transcript.length)
                Outcome.Ready(target.name, probe.durationMs(target))
            }
            is TtsResult.Failure -> {
                temp.delete()
                Outcome.Failed(result.reason)
            }
        }
    }

    /** 设置页试听：合成到临时目录，不进缓存。记账用途按聊天算。 */
    suspend fun synthesizeSample(text: String): File? {
        val engine = engines.current() ?: return null
        val file = cache.sampleFile(engine.format)
        return when (val result = engine.synthesize(TtsText.clean(text), null, file)) {
            TtsResult.Success -> {
                record(TurnPurpose.CHAT, engine.model, text.length)
                file.takeIf { it.isFile && it.length() > 0 }
            }
            is TtsResult.Failure -> {
                diag.log(TAG, "sample synthesis failed: ${result.reason}")
                file.delete()
                null
            }
        }
    }

    /** 音频文件被清理后，把引用它们的附件改成 failed（仍可看文字稿）。 */
    suspend fun markFilesMissing(messageId: Long, voiceIds: Set<String>) {
        updateAttachments(messageId, voiceIds) { VoiceAttachments.failed(it) }
    }

    private suspend fun updateAttachments(messageId: Long, voiceIds: Set<String>, transform: (Attachment) -> Attachment) {
        updateMutex.withLock {
            val message = messages.byId(messageId) ?: return
            var changed = false
            val updated = message.attachments.map { attachment ->
                val payload = VoiceAttachments.read(attachment)
                if (payload != null && payload.id in voiceIds) {
                    changed = true
                    transform(attachment)
                } else {
                    attachment
                }
            }
            if (changed) messages.update(message.copy(attachments = updated))
        }
    }

    private fun moveInto(temp: File, target: File): Boolean {
        if (target.exists()) target.delete()
        if (temp.renameTo(target)) return true
        return try {
            temp.copyTo(target, overwrite = true)
            true
        } catch (e: Exception) {
            target.delete()
            false
        } finally {
            temp.delete()
        }
    }

    private suspend fun record(purpose: TurnPurpose, model: String, chars: Int) {
        try {
            ledger.record(UsageEntry(purpose = purpose, model = model, ttsChars = chars, at = clock.instant()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "usage record failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "voice"

        /** 等引擎保存消息的最长时间，超时后到最近消息里查找。 */
        const val LOCATE_TIMEOUT_MS = 60_000L
    }
}
