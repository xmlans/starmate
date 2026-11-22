package org.xmyun.starmate.feature.tools.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.feature.tools.common.WriteOutcome

data class CalendarEvent(
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String?,
)

interface CalendarGateway {
    /** 与 [from, to) 有交集的日程实例，按开始时间排序，最多 limit 条。 */
    suspend fun events(from: Instant, to: Instant, limit: Int): List<CalendarEvent>

    /** 写入设备的主日历。 */
    suspend fun insertEvent(title: String, start: Instant, end: Instant, location: String?): WriteOutcome
}

class AndroidCalendarGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarGateway {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun events(from: Instant, to: Instant, limit: Int): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_CALENDAR)) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val result = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && result.size < limit) {
                result += CalendarEvent(
                    title = c.getString(0)?.trim().orEmpty(),
                    start = Instant.ofEpochMilli(c.getLong(1)),
                    end = Instant.ofEpochMilli(c.getLong(2)),
                    allDay = c.getInt(3) != 0,
                    location = c.getString(4)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        result
    }

    override suspend fun insertEvent(
        title: String,
        start: Instant,
        end: Instant,
        location: String?,
    ): WriteOutcome = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) return@withContext WriteOutcome.NO_PERMISSION
        val calendarId = primaryCalendarId() ?: return@withContext WriteOutcome.NO_CALENDAR
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        try {
            if (context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null) {
                WriteOutcome.OK
            } else {
                WriteOutcome.FAILED
            }
        } catch (e: SecurityException) {
            WriteOutcome.NO_PERMISSION
        } catch (e: Exception) {
            WriteOutcome.FAILED
        }
    }

    /** 主日历：可见、可写，优先 IS_PRIMARY，其次第一个可写日历。 */
    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var fallback: Long? = null
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }
}
