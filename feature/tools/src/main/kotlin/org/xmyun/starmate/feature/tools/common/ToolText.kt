package org.xmyun.starmate.feature.tools.common

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.xmyun.starmate.core.model.ToolResult

/**
 * 工具里用到的文字。chinese 跟随系统语言，决定工具结果用中文还是英文；
 * 给用户看的确认文字从字符串资源里取。
 */
interface ToolText {
    val chinese: Boolean
    fun get(@StringRes id: Int, vararg args: Any): String
}

fun ToolText.pick(zh: String, en: String): String = if (chinese) zh else en

fun ToolText.ok(zh: String, en: String) = ToolResult(pick(zh, en))

fun ToolText.error(zh: String, en: String) = ToolResult(pick(zh, en), isError = true)

class AndroidToolText @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolText {
    override val chinese: Boolean
        get() = context.resources.configuration.locales[0]?.language == "zh"

    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)
}
