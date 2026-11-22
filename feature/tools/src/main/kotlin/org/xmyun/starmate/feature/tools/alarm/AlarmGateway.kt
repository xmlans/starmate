package org.xmyun.starmate.feature.tools.alarm

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.Calendar
import javax.inject.Inject
import org.xmyun.starmate.feature.tools.common.WriteOutcome

interface AlarmGateway {
    /** 系统记录的下一个闹钟，没有时为 null。 */
    fun nextAlarm(): Instant?

    /** days 用 ISO 星期：1 = 周一 … 7 = 周日。 */
    fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>?): WriteOutcome

    fun setTimer(seconds: Int, label: String?): WriteOutcome
}

class AndroidAlarmGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmGateway {

    override fun nextAlarm(): Instant? {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return null
        return manager.nextAlarmClock?.triggerTime?.let(Instant::ofEpochMilli)
    }

    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>?): WriteOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (!days.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map(::isoToCalendarDay)))
        }
        return launch(intent)
    }

    override fun setTimer(seconds: Int, label: String?): WriteOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return launch(intent)
    }

    private fun launch(intent: Intent): WriteOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return WriteOutcome.NO_APP
        return try {
            context.startActivity(intent)
            WriteOutcome.OK
        } catch (e: ActivityNotFoundException) {
            WriteOutcome.NO_APP
        } catch (e: SecurityException) {
            WriteOutcome.NO_PERMISSION
        } catch (e: Exception) {
            WriteOutcome.FAILED
        }
    }

    private fun isoToCalendarDay(iso: Int): Int = when (iso) {
        1 -> Calendar.MONDAY
        2 -> Calendar.TUESDAY
        3 -> Calendar.WEDNESDAY
        4 -> Calendar.THURSDAY
        5 -> Calendar.FRIDAY
        6 -> Calendar.SATURDAY
        else -> Calendar.SUNDAY
    }
}
