package org.xmyun.starmate.feature.tools.calendar

import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.TimeFmt
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText

/** 今天还没结束的日程，最多 3 条。 */
class CalendarAmbient @Inject constructor(
    private val gate: GroupGate,
    private val gateway: CalendarGateway,
    private val text: ToolText,
    private val clock: Clock,
) : AmbientSource {
    override val id: String = "tools_calendar"

    override suspend fun snapshot(): String? {
        if (!gate.ambientOn(ToolGroup.CALENDAR)) return null
        val now = clock.instant()
        val endOfDay = LocalDate.now(clock).plusDays(1).atStartOfDay(clock.zone).toInstant()
        val events = gateway.events(now, endOfDay, 10).filter { !it.allDay && it.end.isAfter(now) }.take(MAX)
        if (events.isEmpty()) return null
        val f = TimeFmt(clock, text.chinese)
        val items = events.joinToString(if (text.chinese) "、" else ", ") { "${f.time(it.start)} ${it.title}".trim() }
        return if (text.chinese) "今天还有：$items" else "Still on today's calendar: $items"
    }

    companion object {
        const val MAX = 3
    }
}
