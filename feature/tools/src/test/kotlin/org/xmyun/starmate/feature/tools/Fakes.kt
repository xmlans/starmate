package org.xmyun.starmate.feature.tools

import java.time.Instant
import org.xmyun.starmate.feature.tools.alarm.AlarmGateway
import org.xmyun.starmate.feature.tools.calendar.CalendarEvent
import org.xmyun.starmate.feature.tools.calendar.CalendarGateway
import org.xmyun.starmate.feature.tools.common.WriteOutcome
import org.xmyun.starmate.feature.tools.contacts.ContactInfo
import org.xmyun.starmate.feature.tools.contacts.ContactsGateway
import org.xmyun.starmate.feature.tools.health.HealthAvailability
import org.xmyun.starmate.feature.tools.health.HealthData
import org.xmyun.starmate.feature.tools.health.HealthGateway
import org.xmyun.starmate.feature.tools.health.SleepSession
import org.xmyun.starmate.feature.tools.location.GeoFix
import org.xmyun.starmate.feature.tools.location.LocationGateway

class FakeCalendarGateway(var events: List<CalendarEvent> = emptyList()) : CalendarGateway {
    var inserted = mutableListOf<CalendarEvent>()
    var lastLimit = 0
    var queried = 0

    override suspend fun events(from: Instant, to: Instant, limit: Int): List<CalendarEvent> {
        queried++
        lastLimit = limit
        return events.filter { it.end.isAfter(from) && it.start.isBefore(to) }.take(limit)
    }

    override suspend fun insertEvent(title: String, start: Instant, end: Instant, location: String?): WriteOutcome {
        inserted += CalendarEvent(title, start, end, false, location)
        return WriteOutcome.OK
    }
}

class FakeAlarmGateway(var outcome: WriteOutcome = WriteOutcome.OK, var next: Instant? = null) : AlarmGateway {
    val alarms = mutableListOf<List<Any?>>()
    val timers = mutableListOf<Int>()
    override fun nextAlarm(): Instant? = next
    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>?): WriteOutcome {
        alarms += listOf(hour, minute, label, days)
        return outcome
    }

    override fun setTimer(seconds: Int, label: String?): WriteOutcome {
        timers += seconds
        return outcome
    }
}

class FakeContactsGateway(var contacts: List<ContactInfo> = emptyList()) : ContactsGateway {
    val dialed = mutableListOf<String>()
    override suspend fun search(name: String, limit: Int): List<ContactInfo> =
        contacts.filter { it.name.contains(name) }
    override fun dial(number: String): WriteOutcome {
        dialed += number
        return WriteOutcome.OK
    }
}

class FakeLocationGateway(
    var permission: Boolean = true,
    var fix: GeoFix? = null,
    var city: String? = null,
) : LocationGateway {
    var fixCalls = 0
    override fun hasPermission(): Boolean = permission
    override suspend fun currentFix(): GeoFix? {
        fixCalls++
        return fix
    }
    override suspend fun cityName(latitude: Double, longitude: Double): String? = city
}

class FakeHealthGateway(
    var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    var granted: Set<HealthData> = setOf(HealthData.SLEEP, HealthData.STEPS),
    var sessions: List<SleepSession> = emptyList(),
    var steps: Long? = null,
    var stepCounter: Boolean = false,
) : HealthGateway {
    var lastSleepFrom: Instant? = null
    override fun availability(): HealthAvailability = availability
    override suspend fun granted(): Set<HealthData> = if (availability == HealthAvailability.AVAILABLE) granted else emptySet()
    override suspend fun sleepSessions(from: Instant, to: Instant): List<SleepSession> {
        lastSleepFrom = from
        return sessions.filter { it.end.isAfter(from) && !it.start.isAfter(to) }
    }
    override suspend fun steps(from: Instant, to: Instant): Long? = steps
    override fun stepCounterUsable(): Boolean = stepCounter
}
