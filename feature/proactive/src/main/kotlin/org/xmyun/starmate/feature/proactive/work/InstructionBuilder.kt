package org.xmyun.starmate.feature.proactive.work

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.xmyun.starmate.feature.proactive.model.Care

data class InstructionContext(
    val now: ZonedDateTime,
    val ambient: List<String>,
    val sinceLastChat: Duration?,
    val summary: String?,
    /** 约定触发时是那条约定；随机问候为 null。 */
    val care: Care?,
)

/**
 * 把模板里的占位符换成本次的上下文。runHeadless 不会自动附带时间和环境信息，全部写在这里。
 * 占位符：{{now}} {{ambient}} {{since_last_chat}} {{summary}} {{trigger}}
 */
object InstructionBuilder {

    fun build(template: String, chinese: Boolean, ctx: InstructionContext): String {
        val none = if (chinese) "无" else "none"
        return template
            .replace("{{now}}", formatTime(ctx.now, chinese))
            .replace("{{ambient}}", ctx.ambient.takeIf { it.isNotEmpty() }?.joinToString(if (chinese) "；" else "; ") ?: none)
            .replace("{{since_last_chat}}", ctx.sinceLastChat?.let { formatDuration(it, chinese) } ?: if (chinese) "还没有聊过" else "you have not chatted yet")
            .replace("{{summary}}", ctx.summary?.takeIf { it.isNotBlank() }?.trim() ?: none)
            .replace("{{trigger}}", trigger(ctx, chinese))
    }

    private fun trigger(ctx: InstructionContext, chinese: Boolean): String {
        val care = ctx.care
        if (care == null) {
            return if (chinese) {
                "没有约定的事。这是一次随机问候：只有当你有自然、轻松的理由打个招呼时才发（比如最近聊过的事、此刻的时间或天气）。"
            } else {
                "Nothing was arranged. This is a casual check-in: only send something if you have a natural, light reason to say hi (something from your recent chats, the time of day, the weather)."
            }
        }
        val time = formatTime(care.scheduledFor.atZone(ctx.now.zone), chinese)
        val reason = care.reason.ifBlank { if (chinese) "（没写）" else "(not given)" }
        return if (chinese) {
            "你之前约好在 $time 左右来问问“${care.topic}”。当时的缘由：$reason"
        } else {
            "Earlier you arranged to check in about \"${care.topic}\" around $time. Your reason then: $reason"
        }
    }

    fun formatTime(time: ZonedDateTime, chinese: Boolean): String =
        if (chinese) {
            time.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE))
        } else {
            time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, HH:mm", Locale.ENGLISH))
        }

    fun formatDuration(duration: Duration, chinese: Boolean): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return if (chinese) {
            when {
                days > 0 -> "$days 天 $hours 小时"
                hours > 0 -> "$hours 小时 $mins 分钟"
                else -> "$mins 分钟"
            }
        } else {
            when {
                days > 0 -> "$days d $hours h"
                hours > 0 -> "$hours h $mins min"
                else -> "$mins min"
            }
        }
    }
}
