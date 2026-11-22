package org.xmyun.starmate.feature.proactive.platform

import android.content.Context
import androidx.annotation.RawRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.feature.proactive.R

/** 写给模型的提示词。按系统语言读 res/raw 或 res/raw-zh。 */
interface ProactiveTexts {
    val chinese: Boolean

    /** 聊天时的 stable 片段：什么时候该约、什么时候不该约。 */
    fun chatGuide(): String

    /** 主动消息的 instruction 模板，占位符见 InstructionBuilder。 */
    fun instructionTemplate(): String
}

@Singleton
class ResourceProactiveTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProactiveTexts {

    @Volatile
    private var cache: Pair<String, Map<Int, String>> = "" to emptyMap()

    override val chinese: Boolean
        get() = context.resources.configuration.locales[0].language == "zh"

    override fun chatGuide(): String = read(R.raw.proactive_chat_guide)

    override fun instructionTemplate(): String = read(R.raw.proactive_instruction)

    // 按语言缓存，保证 stable 片段逐字不变
    private fun read(@RawRes id: Int): String {
        val key = context.resources.configuration.locales[0].toLanguageTag()
        val current = cache
        val texts = if (current.first == key) current.second else emptyMap()
        texts[id]?.let { return it }
        val text = context.resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        cache = key to (texts + (id to text))
        return text
    }
}
