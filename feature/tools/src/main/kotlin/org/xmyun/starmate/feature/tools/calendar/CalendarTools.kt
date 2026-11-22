package org.xmyun.starmate.feature.tools.calendar

import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.R
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.JsonArgs.string
import org.xmyun.starmate.feature.tools.common.Schema
import org.xmyun.starmate.feature.tools.common.TimeFmt
import org.xmyun.starmate.feature.tools.common.TimeParse
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.WriteOutcome
import org.xmyun.starmate.feature.tools.common.error
import org.xmyun.starmate.feature.tools.common.ok
import org.xmyun.starmate.feature.tools.common.pick

class CalendarTools @Inject constructor(
    private val gate: GroupGate,
    private val gateway: CalendarGateway,
    private val text: ToolText,
    private val clock: Clock,
) : ToolProvider {

    val listEvents: Tool = ListEvents()
    val createEvent: Tool = CreateEvent()

    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && gate.usable(ToolGroup.CALENDAR)) listOf(listEvents, createEvent) else emptyList()

    private fun fmt() = TimeFmt(clock, text.chinese)

    /** 一条日程的写法：时间段 + 标题 + 地点。全天日程按 UTC 日期显示（日历提供者的约定）。 */
    internal fun line(e: CalendarEvent, f: TimeFmt): String {
        val title = e.title.ifBlank { if (text.chinese) "（无标题）" else "(untitled)" }
        val time = if (e.allDay) {
            val startDate = e.start.atZone(ZoneOffset.UTC).toLocalDate()
            val lastDate = e.end.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate()
            val allDay = if (text.chinese) "全天" else "all day"
            if (lastDate.isAfter(startDate)) "${f.date(startDate)} – ${f.date(lastDate)} $allDay" else "${f.date(startDate)} $allDay"
        } else {
            val sameDay = e.start.atZone(clock.zone).toLocalDate() == e.end.atZone(clock.zone).toLocalDate()
            if (sameDay) "${f.dateTime(e.start)}–${f.time(e.end)}" else "${f.dateTime(e.start)} – ${f.dateTime(e.end)}"
        }
        val where = e.location?.let { if (text.chinese) "（地点：$it）" else " (at $it)" }.orEmpty()
        return "- $time $title$where"
    }

    private inner class ListEvents : Tool {
        override val name = "calendar_list_events"
        override val description =
            "Read events from the user's phone calendar in a time range. Use it when the user mentions their schedule, " +
                "plans, appointments, or asks whether they are free. Times are the user's local time in ISO format, " +
                "e.g. 2026-11-14T09:00; a plain date such as 2026-11-14 means the start of that day for `from` and the " +
                "end of that day for `to`. The range may span at most 31 days. Returns up to 20 events with local " +
                "start/end time (with weekday), title and location."
        override val parametersSchema = Schema.obj(
            "from" to Schema.string("Range start, local ISO date-time or date, e.g. 2026-11-14T00:00"),
            "to" to Schema.string("Range end, local ISO date-time or date, after `from`, at most 31 days later"),
            required = listOf("from", "to"),
        )
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val zone = clock.zone
            val from = TimeParse.parse(args.string("from"), zone)
                ?: return text.error("from 不是有效的时间，请用 2026-11-14T09:00 这样的格式", "`from` is not a valid time; use a format like 2026-11-14T09:00")
            val toParsed = TimeParse.parse(args.string("to"), zone)
                ?: return text.error("to 不是有效的时间，请用 2026-11-14T18:00 这样的格式", "`to` is not a valid time; use a format like 2026-11-14T18:00")
            val start = from.dateTime.atZone(zone).toInstant()
            val end = (if (toParsed.dateOnly) toParsed.dateTime.plusDays(1) else toParsed.dateTime).atZone(zone).toInstant()
            if (!end.isAfter(start)) return text.error("结束时间必须晚于开始时间", "`to` must be after `from`")
            if (Duration.between(start, end) > Duration.ofDays(MAX_RANGE_DAYS)) {
                return text.error("时间范围最长 31 天，请缩小范围", "The range may be at most 31 days; please narrow it")
            }
            val events = gateway.events(start, end, MAX_EVENTS + 1)
            val f = fmt()
            val range = "${f.dateTime(start)} – ${f.dateTime(end)}"
            if (events.isEmpty()) return text.ok("$range 之间没有日程", "No events between $range")
            val shown = events.take(MAX_EVENTS)
            val header = text.pick("$range 之间的日程：", "Events between $range:")
            val more = if (events.size > MAX_EVENTS) {
                "\n" + text.pick("（只列出前 $MAX_EVENTS 条，还有更多）", "(only the first $MAX_EVENTS are shown; there are more)")
            } else ""
            return ToolResult(header + "\n" + shown.joinToString("\n") { line(it, f) } + more)
        }
    }

    private inner class CreateEvent : Tool {
        override val name = "calendar_create_event"
        override val description =
            "Add an event to the user's phone calendar. Use it only when the user asks you to put something on their " +
                "calendar or clearly agrees to it. The user will be asked to confirm. Times are local ISO date-times, " +
                "e.g. 2026-11-14T15:00; `end` must be after `start`. Returns whether the event was added."
        override val parametersSchema = Schema.obj(
            "title" to Schema.string("Short event title in the user's language, e.g. 面试"),
            "start" to Schema.string("Start, local ISO date-time, e.g. 2026-11-14T15:00"),
            "end" to Schema.string("End, local ISO date-time after start, e.g. 2026-11-14T16:00"),
            "location" to Schema.string("Optional place"),
            required = listOf("title", "start", "end"),
        )
        override val risk = ToolRisk.WRITE

        override fun describeCall(args: JsonObject): String {
            val title = args.string("title").orEmpty()
            val start = TimeParse.parse(args.string("start"), clock.zone)
                ?: return text.get(R.string.tools_calendar_describe_create_no_time, title)
            val instant = start.dateTime.atZone(clock.zone).toInstant()
            return text.get(R.string.tools_calendar_describe_create, fmt().friendly(instant), title)
        }

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val zone = clock.zone
            val title = args.string("title")?.take(200)
                ?: return text.error("缺少标题", "`title` is missing")
            val start = TimeParse.parse(args.string("start"), zone)?.dateTime?.atZone(zone)?.toInstant()
                ?: return text.error("start 不是有效的时间", "`start` is not a valid time")
            val end = TimeParse.parse(args.string("end"), zone)?.dateTime?.atZone(zone)?.toInstant()
                ?: return text.error("end 不是有效的时间", "`end` is not a valid time")
            if (!end.isAfter(start)) return text.error("结束时间必须晚于开始时间", "`end` must be after `start`")
            val location = args.string("location")?.take(200)
            val f = fmt()
            return when (gateway.insertEvent(title, start, end, location)) {
                WriteOutcome.OK -> text.ok(
                    "已添加日程：${f.dateTime(start)}–${f.time(end)} $title",
                    "Event added: ${f.dateTime(start)}–${f.time(end)} $title",
                )
                WriteOutcome.NO_PERMISSION -> text.error("没有写日历的权限", "No permission to write to the calendar")
                WriteOutcome.NO_CALENDAR -> text.error("这台手机上没有可写入的日历", "There is no writable calendar on this phone")
                else -> text.error("日程没有添加成功", "The event could not be added")
            }
        }
    }

    companion object {
        const val MAX_EVENTS = 20
        const val MAX_RANGE_DAYS = 31L
    }
}
