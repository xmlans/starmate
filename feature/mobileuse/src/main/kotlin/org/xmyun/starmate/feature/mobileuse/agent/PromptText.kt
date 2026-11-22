package org.xmyun.starmate.feature.mobileuse.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.xmyun.starmate.feature.mobileuse.R

/** 处理提示词模板里的 look 段落。纯函数，便于测试。 */
object PromptText {
    private val LOOK_BLOCK = Regex("""<!--LOOK-->\r?\n(.*?)<!--/LOOK-->\r?\n?""", RegexOption.DOT_MATCHES_ALL)

    fun render(template: String, lookAvailable: Boolean): String =
        LOOK_BLOCK.replace(template) { if (lookAvailable) it.groupValues[1] else "" }.trim()
}

/** 按系统语言读 res/raw 或 res/raw-zh 里的 mobileuse_prompt.md。 */
class RawPromptSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : PromptSource {

    override val chinese: Boolean
        get() = context.resources.configuration.locales[0].language == "zh"

    override fun systemPrompt(lookAvailable: Boolean): String {
        val template = context.resources.openRawResource(R.raw.mobileuse_prompt)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return PromptText.render(template, lookAvailable)
    }
}
