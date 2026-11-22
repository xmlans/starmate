package org.xmyun.starmate.feature.memory.prompt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.feature.memory.R

/**
 * 写给模型的全部文字。文件格式：`@@键` 独占一行开始一段，到下一个 `@@` 为止。
 * 生产环境从 res/raw（英文）或 res/raw-zh（中文）读取，按系统语言选择。
 */
class PromptTexts(private val map: Map<String, String>) {

    val locale: Locale = Locale.forLanguageTag(map["locale"] ?: "en")

    operator fun get(key: String): String = map[key] ?: error("missing prompt text: $key")

    /** 把 {name} 换成对应的值。 */
    fun fmt(key: String, vararg values: Pair<String, Any?>): String {
        var text = get(key)
        for ((name, value) in values) text = text.replace("{$name}", value?.toString() ?: "")
        return text
    }

    fun categoryTitle(category: String): String = map["cat_$category"] ?: category

    companion object {
        fun parse(raw: String): PromptTexts {
            val map = LinkedHashMap<String, String>()
            var key: String? = null
            val buffer = StringBuilder()
            fun flush() {
                key?.let { map[it] = buffer.toString().trimEnd('\n', '\r') }
                buffer.setLength(0)
            }
            for (line in raw.replace("\r\n", "\n").split('\n')) {
                if (line.startsWith("@@")) {
                    flush()
                    key = line.removePrefix("@@").trim()
                } else if (key != null) {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line)
                }
            }
            flush()
            return PromptTexts(map)
        }
    }
}

/** 按当前系统语言提供提示词。切换语言后下次调用会重新读取。 */
interface PromptSource {
    fun texts(): PromptTexts
}

@Singleton
class ResourcePromptSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : PromptSource {
    @Volatile
    private var cached: Pair<Locale, PromptTexts>? = null

    override fun texts(): PromptTexts {
        val locale = context.resources.configuration.locales[0]
        cached?.let { (l, t) -> if (l == locale) return t }
        val raw = context.resources.openRawResource(R.raw.memory_prompts).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return PromptTexts.parse(raw).also { cached = locale to it }
    }
}
