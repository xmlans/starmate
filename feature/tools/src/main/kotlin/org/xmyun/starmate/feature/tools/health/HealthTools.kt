package org.xmyun.starmate.feature.tools.health

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.JsonArgs.int
import org.xmyun.starmate.feature.tools.common.JsonArgs.string
import org.xmyun.starmate.feature.tools.common.Schema
import org.xmyun.starmate.feature.tools.common.TimeFmt
import org.xmyun.starmate.feature.tools.common.TimeParse
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.error
import org.xmyun.starmate.feature.tools.common.pick

/** 一天的步数及来源。 */
data class DaySteps(val steps: Long, val fromPhoneSensor: Boolean)

/** 健康数据的读取逻辑，工具和环境信息共用。 */
class HealthReader @Inject constructor(
    private val gateway: HealthGateway,
    private val fallback: StepFallback,
    private val clock: Clock,
) {
    /** Health Connect 优先；没有数据时用手机计步器兜底。都没有返回 null。 */
    suspend fun steps(date: LocalDate): DaySteps? {
        val zone = clock.zone
        val from = date.atStartOfDay(zone).toInstant()
        val to = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), clock.instant())
        if (gateway.availability() == HealthAvailability.AVAILABLE && HealthData.STEPS in gateway.granted() && to.isAfter(from)) {
            gateway.steps(from, to)?.let { return DaySteps(it, fromPhoneSensor = false) }
        }
        if (gateway.stepCounterUsable()) {
            fallback.stepsOn(date)?.let { return DaySteps(it, fromPhoneSensor = true) }
        }
        return null
    }

    /** 起床日期落在最近 days 天（含今天）里的睡眠，按开始时间排序。 */
    suspend fun sleep(days: Int): List<SleepSession> {
        val zone = clock.zone
        val firstWakeDay = LocalDate.now(clock).minusDays((days - 1).toLong())
        val windowStart = firstWakeDay.atStartOfDay(zone).toInstant()
        val sessions = gateway.sleepSessions(windowStart.minus(Duration.ofHours(18)), clock.instant())
        return sessions.filter { !it.end.isBefore(windowStart) && it.end.isAfter(it.start) }.sortedBy { it.start }
    }

    fun sleepReadable(): Boolean = gateway.availability() == HealthAvailability.AVAILABLE

    suspend fun sleepGranted(): Boolean = HealthData.SLEEP in gateway.granted()

    fun availability(): HealthAvailability = gateway.availability()

    fun stepCounterUsable(): Boolean = gateway.stepCounterUsable()

    suspend fun stepsGranted(): Boolean = HealthData.STEPS in gateway.granted()
}

class HealthTools @Inject constructor(
    private val gate: GroupGate,
    private val reader: HealthReader,
    private val text: ToolText,
    private val clock: Clock,
) : ToolProvider {

    val sleep: Tool = Sleep()
    val steps: Tool = Steps()

    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && gate.usable(ToolGroup.HEALTH)) listOf(sleep, steps) else emptyList()

    private fun fmt() = TimeFmt(clock, text.chinese)

    private inner class Sleep : Tool {
        override val name = "health_sleep"
        override val description =
            "Read the user's recent sleep from Health Connect. Use it when the user mentions being tired, sleeping " +
                "badly, staying up late, or asks how they slept. `days` is how many recent nights, 1-7 (default 1 = " +
                "last night). Returns each night's local bedtime, wake time and duration, or that there is no data."
        override val parametersSchema = Schema.obj(
            "days" to Schema.integer("Number of recent nights, 1-7, default 1", 1, MAX_DAYS),
        )
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val days = (args.int("days") ?: 1).coerceIn(1, MAX_DAYS)
            when (reader.availability()) {
                HealthAvailability.AVAILABLE -> Unit
                HealthAvailability.NEEDS_UPDATE -> return text.error(
                    "Health Connect 需要更新后才能读取睡眠数据",
                    "Health Connect needs an update before sleep data can be read",
                )
                HealthAvailability.UNAVAILABLE -> return text.error(
                    "这台手机上没有可用的 Health Connect，读不到睡眠数据",
                    "Health Connect is not available on this phone, so sleep data cannot be read",
                )
            }
            if (!reader.sleepGranted()) {
                return text.error("用户没有授权读取睡眠数据", "The user has not granted access to sleep data")
            }
            val sessions = reader.sleep(days)
            if (sessions.isEmpty()) {
                return text.error(
                    if (days == 1) "没有睡眠数据（昨晚没有记录）" else "没有睡眠数据（最近 $days 晚都没有记录）",
                    if (days == 1) "No sleep data (nothing recorded last night)" else "No sleep data (nothing recorded in the last $days nights)",
                )
            }
            val f = fmt()
            val lines = sessions.map { s ->
                val wakeDay = f.date(s.end.atZone(clock.zone).toLocalDate())
                val length = f.duration(Duration.between(s.start, s.end))
                text.pick(
                    "$wakeDay：${f.time(s.start)} 入睡，${f.time(s.end)} 起床，共 $length",
                    "$wakeDay: asleep ${f.time(s.start)}, up ${f.time(s.end)}, $length",
                )
            }
            return ToolResult(lines.joinToString("\n"))
        }
    }

    private inner class Steps : Tool {
        override val name = "health_steps"
        override val description =
            "Get the user's total step count for one day. Use it when the user talks about walking, exercise, " +
                "being active or lazy. `date` is a local date like 2026-11-14, default today. Returns the step count " +
                "and notes when it came from the phone's own step counter, which may be incomplete."
        override val parametersSchema = Schema.obj(
            "date" to Schema.string("Local date, e.g. 2026-11-14; default today"),
        )
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val today = LocalDate.now(clock)
            val raw = args.string("date")
            val date = if (raw == null) today else TimeParse.parseDate(raw)
                ?: return text.error("date 不是有效的日期，请用 2026-11-14 这样的格式", "`date` is not a valid date; use a format like 2026-11-14")
            if (date.isAfter(today)) return text.error("不能查未来的步数", "Cannot read steps for a future date")
            val f = fmt()
            val result = reader.steps(date)
                ?: return text.error("${f.date(date)} 没有步数数据", "No step data for ${f.date(date)}")
            val note = if (result.fromPhoneSensor) text.pick("（来自手机计步器，可能不完整）", " (from the phone's step counter, may be incomplete)") else ""
            return ToolResult(text.pick("${f.date(date)}：${result.steps} 步$note", "${f.date(date)}: ${result.steps} steps$note"))
        }
    }

    companion object {
        const val MAX_DAYS = 7
    }
}

/** 一行：昨晚睡眠时长和今天步数。两者都没有时为 null。 */
class HealthAmbient @Inject constructor(
    private val gate: GroupGate,
    private val reader: HealthReader,
    private val text: ToolText,
    private val clock: Clock,
) : org.xmyun.starmate.core.model.AmbientSource {
    override val id: String = "tools_health"

    override suspend fun snapshot(): String? {
        if (!gate.ambientOn(ToolGroup.HEALTH)) return null
        val f = TimeFmt(clock, text.chinese)
        val parts = mutableListOf<String>()
        if (reader.sleepReadable() && reader.sleepGranted()) {
            val lastNight = reader.sleep(1).maxByOrNull { Duration.between(it.start, it.end) }
            lastNight?.let {
                val length = f.duration(Duration.between(it.start, it.end))
                parts += text.pick("昨晚睡了 $length", "slept $length last night")
            }
        }
        reader.steps(LocalDate.now(clock))?.let {
            parts += text.pick("今天走了 ${it.steps} 步", "${it.steps} steps today")
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(text.pick("，", ", "))
    }
}
