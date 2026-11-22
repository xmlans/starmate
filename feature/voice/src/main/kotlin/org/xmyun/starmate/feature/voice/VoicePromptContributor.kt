package org.xmyun.starmate.feature.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.voice.prefs.VoiceFrequency
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs
import org.xmyun.starmate.feature.voice.tts.TtsEngineProvider

/** 写给模型的语音说明。按频率各一段固定文字。 */
interface VoicePromptTexts {
    /** NEVER 返回 null。 */
    fun instructions(frequency: VoiceFrequency): String?

    /** 今天的语音额度用完时附在本轮的一句提醒。 */
    fun quotaUsedUp(): String
}

/** 从 res/raw（英文）或 res/raw-zh（中文）读取，按语言缓存，保证同一设置下逐字相同。 */
@Singleton
class RawVoicePromptTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoicePromptTexts {

    private val cache = ConcurrentHashMap<String, String>()

    override fun instructions(frequency: VoiceFrequency): String? {
        val res = when (frequency) {
            VoiceFrequency.NEVER -> return null
            VoiceFrequency.SOMETIMES -> R.raw.voice_prompt_sometimes
            VoiceFrequency.OFTEN -> R.raw.voice_prompt_often
            VoiceFrequency.ALWAYS -> R.raw.voice_prompt_always
        }
        return load(res)
    }

    override fun quotaUsedUp(): String = load(R.raw.voice_prompt_quota)

    private fun load(res: Int): String {
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        return cache.getOrPut("$locale/$res") {
            context.resources.openRawResource(res).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        }
    }
}

/**
 * 有可用语音引擎且频率不是“从不”时，在 system prompt 的稳定部分说明语音标记的用法。
 * 对 CHAT、ONBOARDING、PROACTIVE 生效。文字只取决于频率和语言，不含任何会变的内容。
 */
class VoicePromptContributor @Inject constructor(
    private val prefs: VoicePrefs,
    private val engines: TtsEngineProvider,
    private val texts: VoicePromptTexts,
    private val clock: Clock,
) : PromptContributor {

    override val order: Int = PromptOrder.VOICE

    override suspend fun stable(purpose: TurnPurpose): String? {
        val frequency = activeFrequency(purpose) ?: return null
        return texts.instructions(frequency)
    }

    override suspend fun dynamic(purpose: TurnPurpose, userText: String?): String? {
        activeFrequency(purpose) ?: return null
        return if (prefs.remainingToday(LocalDate.now(clock)) <= 0) texts.quotaUsedUp() else null
    }

    private suspend fun activeFrequency(purpose: TurnPurpose): VoiceFrequency? {
        if (purpose !in VoicePostProcessor.VOICE_PURPOSES) return null
        val frequency = prefs.frequency.first()
        if (frequency == VoiceFrequency.NEVER) return null
        if (!engines.available()) return null
        return frequency
    }
}
