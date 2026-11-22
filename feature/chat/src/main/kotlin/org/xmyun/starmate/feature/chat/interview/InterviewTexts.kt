package org.xmyun.starmate.feature.chat.interview

import android.content.Context
import androidx.annotation.RawRes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.feature.chat.R

/** 访谈用到的提示词和文字。接口化是为了在纯 JVM 测试里替换。 */
interface InterviewTexts {
    /** 当前系统语言是否为中文。决定写给模型和记忆的文字用哪种语言。 */
    val chinese: Boolean

    /** 访谈提示词，进 system prompt 的稳定部分，同一语言下逐字不变。 */
    fun interviewPrompt(): String

    /** 资料抽取用的完整 system prompt。 */
    fun extractionPrompt(): String

    /** 小结卡片附件的一行预览文字。 */
    fun summaryPreview(): String
}

/** 按系统语言读 res/raw 或 res/raw-zh，按语言缓存。 */
@Singleton
class ResourceInterviewTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : InterviewTexts {

    private val cache = ConcurrentHashMap<String, String>()

    override val chinese: Boolean
        get() = context.resources.configuration.locales[0].language == "zh"

    override fun interviewPrompt(): String = raw(R.raw.chat_onboarding_interview)

    override fun extractionPrompt(): String = raw(R.raw.chat_onboarding_extract)

    override fun summaryPreview(): String = context.getString(R.string.chat_summary_preview)

    private fun raw(@RawRes id: Int): String {
        val key = "${context.resources.configuration.locales[0].toLanguageTag()}#$id"
        return cache.getOrPut(key) {
            context.resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        }
    }
}
