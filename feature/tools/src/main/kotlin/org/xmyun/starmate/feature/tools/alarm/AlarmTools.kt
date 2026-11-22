package org.xmyun.starmate.feature.tools.alarm

import java.time.Clock
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.R
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.JsonArgs.int
import org.xmyun.starmate.feature.tools.common.JsonArgs.intList
import org.xmyun.starmate.feature.tools.common.JsonArgs.string
import org.xmyun.starmate.feature.tools.common.Schema
import org.xmyun.starmate.feature.tools.common.TimeFmt
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.WriteOutcome
import org.xmyun.starmate.feature.tools.common.error
import org.xmyun.starmate.feature.tools.common.ok
import org.xmyun.starmate.feature.tools.common.pick

/** 闹钟组：没有危险权限，启用即可用；设置类操作只在前台可用。 */
class AlarmTools @Inject constructor(
    private val gate: GroupGate,
    private val gateway: AlarmGateway,
    private val appState: AppState,
    private val text: ToolText,
    private val clock: Clock,
) : ToolProvider {

    val alarmNext: Tool = AlarmNext()
    val alarmSet: Tool = AlarmSet()
    val timerSet: Tool = TimerSet()

    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && gate.usable(ToolGroup.ALARM)) listOf(alarmNext, alarmSet, timerSet) else emptyList()

    private fun notForeground() = text.error(
        "星伴现在不在前台，不能设置闹钟或计时器",
        "Starmate is not in the foreground, so alarms and timers cannot be set now",
    )

    private fun outcome(result: WriteOutcome, okZh: String, okEn: String): ToolResult = when (result) {
        WriteOutcome.OK -> text.ok(okZh, okEn)
        WriteOutcome.NO_APP -> text.error("这台手机上找不到能设置闹钟的时钟应用", "No clock app on this phone can handle this")
        WriteOutcome.NO_PERMISSION -> text.error("没有设置闹钟的权限", "No permission to set alarms")
        else -> text.error("没有设置成功", "It could not be set")
    }

    /** 重复的星期，例如“工作日”“周一、周三”。 */
    internal fun daysLabel(days: List<Int>): String {
        val sorted = days.distinct().sorted()
        if (sorted.size == 7) return text.pick("每天", "every day")
        if (sorted == listOf(1, 2, 3, 4, 5)) return text.pick("工作日", "weekdays")
        if (sorted == listOf(6, 7)) return text.pick("周末", "weekends")
        val locale = if (text.chinese) Locale.CHINESE else Locale.ENGLISH
        val names = sorted.map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
        return if (text.chinese) "每" + names.joinToString("、") else "every " + names.joinToString(", ")
    }

    private fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(Locale.ROOT, hour, minute)

    private inner class AlarmNext : Tool {
        override val name = "alarm_next"
        override val description =
            "Get the next alarm set on the user's phone. Use it when the user talks about waking up, sleep or " +
                "tomorrow morning. Returns the local time (with weekday) of the next alarm, or that none is set."
        override val parametersSchema = Schema.obj()
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val next = gateway.nextAlarm() ?: return text.ok("手机上没有设置闹钟", "No alarm is set on the phone")
            val f = TimeFmt(clock, text.chinese)
            return text.ok("下一个闹钟：${f.dateTime(next)}", "Next alarm: ${f.dateTime(next)}")
        }
    }

    private inner class AlarmSet : Tool {
        override val name = "alarm_set"
        override val description =
            "Set an alarm in the phone's clock app. Use it only when the user asks for an alarm or clearly agrees. " +
                "The user will be asked to confirm, and it only works while the app is open. `hour` is 0-23 and " +
                "`minute` 0-59 in local time. `days` makes it repeat, using ISO weekdays 1=Monday … 7=Sunday; omit " +
                "it for a one-time alarm. Returns whether the alarm was set."
        override val parametersSchema = Schema.obj(
            "hour" to Schema.integer("Hour, 0-23, local time", 0, 23),
            "minute" to Schema.integer("Minute, 0-59", 0, 59),
            "label" to Schema.string("Optional short label in the user's language, e.g. 起床"),
            "days" to Schema.intArray("Optional repeat days, ISO weekdays 1=Monday … 7=Sunday", 1, 7),
            required = listOf("hour", "minute"),
        )
        override val risk = ToolRisk.WRITE

        override fun describeCall(args: JsonObject): String {
            val hour = args.int("hour") ?: 0
            val minute = args.int("minute") ?: 0
            val label = args.string("label")
            val days = args.intList("days").values?.filter { it in 1..7 }.orEmpty()
            val base = if (label != null) {
                text.get(R.string.tools_alarm_describe_set_label, hhmm(hour, minute), label)
            } else {
                text.get(R.string.tools_alarm_describe_set, hhmm(hour, minute))
            }
            return if (days.isEmpty()) base else text.get(R.string.tools_alarm_describe_repeat, base, daysLabel(days))
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val hour = args.int("hour") ?: return text.error("缺少 hour", "`hour` is missing")
            val minute = args.int("minute") ?: return text.error("缺少 minute", "`minute` is missing")
            if (hour !in 0..23) return text.error("hour 必须在 0 到 23 之间", "`hour` must be between 0 and 23")
            if (minute !in 0..59) return text.error("minute 必须在 0 到 59 之间", "`minute` must be between 0 and 59")
            val daysArg = args.intList("days")
            val days = daysArg.values
            if (daysArg.invalid || days?.any { it !in 1..7 } == true) {
                return text.error("days 必须是 1 到 7 的整数列表（1 = 周一）", "`days` must be a list of integers 1-7 (1 = Monday)")
            }
            if (!appState.isForeground.value) return notForeground()
            val label = args.string("label")?.take(60)
            val time = hhmm(hour, minute)
            val repeat = days?.takeIf { it.isNotEmpty() }?.let { " (${daysLabel(it)})" }.orEmpty()
            return outcome(
                gateway.setAlarm(hour, minute, label, days?.distinct()?.sorted()),
                "已设好 $time 的闹钟$repeat",
                "Alarm set for $time$repeat",
            )
        }
    }

    private inner class TimerSet : Tool {
        override val name = "timer_set"
        override val description =
            "Start a countdown timer in the phone's clock app. Use it when the user asks for a timer or reminder in " +
                "a few minutes, e.g. for cooking. The user will be asked to confirm, and it only works while the app " +
                "is open. `seconds` is 1-86400. Returns whether the timer was started."
        override val parametersSchema = Schema.obj(
            "seconds" to Schema.integer("Timer length in seconds, 1-86400", 1, MAX_TIMER_SECONDS),
            "label" to Schema.string("Optional short label in the user's language, e.g. 泡面"),
            required = listOf("seconds"),
        )
        override val risk = ToolRisk.WRITE

        override fun describeCall(args: JsonObject): String {
            val seconds = args.int("seconds") ?: 0
            val length = lengthLabel(seconds)
            val label = args.string("label")
            return if (label != null) {
                text.get(R.string.tools_timer_describe_label, length, label)
            } else {
                text.get(R.string.tools_timer_describe, length)
            }
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val seconds = args.int("seconds") ?: return text.error("缺少 seconds", "`seconds` is missing")
            if (seconds !in 1..MAX_TIMER_SECONDS) {
                return text.error("seconds 必须在 1 到 86400 之间", "`seconds` must be between 1 and 86400")
            }
            if (!appState.isForeground.value) return notForeground()
            val label = args.string("label")?.take(60)
            val length = lengthLabel(seconds)
            return outcome(gateway.setTimer(seconds, label), "已开始 $length 的计时", "Timer started for $length")
        }
    }

    private fun lengthLabel(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (text.chinese) {
            if (h > 0) parts += "${h}小时"
            if (m > 0) parts += "${m}分钟"
            if (s > 0 || parts.isEmpty()) parts += "${s}秒"
            return parts.joinToString("")
        }
        if (h > 0) parts += "$h h"
        if (m > 0) parts += "$m min"
        if (s > 0 || parts.isEmpty()) parts += "$s s"
        return parts.joinToString(" ")
    }

    companion object {
        const val MAX_TIMER_SECONDS = 86_400
    }
}
