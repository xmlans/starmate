package org.xmyun.starmate.core.engine.guidelines

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.core.engine.R

/** 内置相处准则的文字，以及它是哪种语言（决定引擎拼接的其他提示词用中文还是英文）。 */
data class Guidelines(val text: String, val chinese: Boolean)

/** 引擎内部接口，方便在纯 JVM 测试里替换。 */
interface GuidelinesSource {
    fun load(): Guidelines
}

/** 按系统语言读取 res/raw 或 res/raw-zh 里的 base_guidelines.md，按语言缓存，保证每轮逐字相同。 */
@Singleton
class BaseGuidelines @Inject constructor(
    @ApplicationContext private val context: Context,
) : GuidelinesSource {

    @Volatile
    private var cache: Pair<String, Guidelines>? = null

    override fun load(): Guidelines {
        val locale = context.resources.configuration.locales[0]
        val key = locale.toLanguageTag()
        cache?.let { if (it.first == key) return it.second }
        val text = context.resources.openRawResource(R.raw.base_guidelines)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .trim()
        val loaded = Guidelines(text = text, chinese = locale.language == "zh")
        cache = key to loaded
        return loaded
    }
}
