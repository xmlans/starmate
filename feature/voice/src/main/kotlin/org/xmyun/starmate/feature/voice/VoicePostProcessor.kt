package org.xmyun.starmate.feature.voice

import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ProcessedReply
import org.xmyun.starmate.core.model.ReplyPostProcessor
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.voice.prefs.VoiceFrequency
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs
import org.xmyun.starmate.feature.voice.tag.VoiceTagParser
import org.xmyun.starmate.feature.voice.tts.TtsEngineProvider
import org.xmyun.starmate.feature.voice.tts.TtsText
import org.xmyun.starmate.feature.voice.tts.VoiceJob
import org.xmyun.starmate.feature.voice.tts.VoiceJobSink

/**
 * 把回复里的 `<voice>` 段变成语音附件。
 *
 * 不能发语音时（没有引擎、频率为“从不”、超过每日上限、单段超过 300 字、用途不支持），
 * 语音段还原成普通文字放回原位，内容不丢。合成在 [VoiceJobSink] 自己的协程里做，这里不等待。
 */
class VoicePostProcessor @Inject constructor(
    private val engines: TtsEngineProvider,
    private val prefs: VoicePrefs,
    private val sink: VoiceJobSink,
    private val locale: VoiceLocale,
    private val clock: Clock,
) : ReplyPostProcessor {

    // 靠前执行，让排在后面的处理器看到的已经是去掉标签的文字
    override val order: Int = 10

    override fun preview(rawSoFar: String): String = VoiceTagParser.preview(rawSoFar)

    override suspend fun finish(rawText: String, reply: ProcessedReply, purpose: TurnPurpose): ProcessedReply {
        val parsed = VoiceTagParser.parse(reply.displayText)
        if (!parsed.changed) return reply
        if (parsed.segments.isEmpty()) return reply.copy(displayText = parsed.text)

        val enabled = purpose in VOICE_PURPOSES &&
            prefs.frequency.first() != VoiceFrequency.NEVER &&
            engines.available()

        val ttsTexts = parsed.segments.map { TtsText.clean(it.transcript) }
        val eligible = if (!enabled) {
            emptyList()
        } else {
            parsed.segments.indices.filter { i ->
                ttsTexts[i].isNotEmpty() && parsed.segments[i].transcript.codePointCount() <= MAX_SEGMENT_CHARS
            }
        }
        val granted = if (eligible.isEmpty()) 0 else prefs.consume(eligible.size, LocalDate.now(clock))
        val voiced = eligible.take(granted).toSet()

        val displayText = VoiceTagParser.render(parsed.parts) { index -> index !in voiced }
        if (voiced.isEmpty()) return reply.copy(displayText = displayText)

        val prefix = if (locale.chinese()) PREVIEW_PREFIX_ZH else PREVIEW_PREFIX_EN
        val attachments = mutableListOf<Attachment>()
        val jobs = mutableListOf<VoiceJob>()
        for (i in voiced.sorted()) {
            val segment = parsed.segments[i]
            val id = UUID.randomUUID().toString()
            attachments += VoiceAttachments.create(id, segment.transcript, segment.tone, prefix)
            jobs += VoiceJob(id, segment.transcript, ttsTexts[i], segment.tone, purpose)
        }
        sink.enqueue(jobs)
        return ProcessedReply(displayText = displayText, attachments = reply.attachments + attachments)
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    companion object {
        const val MAX_SEGMENT_CHARS = 300
        const val PREVIEW_PREFIX_ZH = "[语音] "
        const val PREVIEW_PREFIX_EN = "[Voice] "
        val VOICE_PURPOSES = setOf(TurnPurpose.CHAT, TurnPurpose.ONBOARDING, TurnPurpose.PROACTIVE)
    }
}
