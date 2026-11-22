package org.xmyun.starmate.feature.proactive.schedule

import java.time.Duration
import java.time.Instant
import org.xmyun.starmate.feature.proactive.platform.RandomSource

/** 随机偏移，让她的消息不像闹钟一样准点。 */
object Jitter {
    val MAX_OFFSET: Duration = Duration.ofMinutes(15)

    /** target 加 ±max 的均匀随机偏移，结果不早于 now。 */
    fun apply(target: Instant, now: Instant, random: RandomSource, max: Duration = MAX_OFFSET): Instant {
        val maxSeconds = max.seconds
        val shifted = target.plusSeconds(random.nextLong(-maxSeconds, maxSeconds + 1))
        return if (shifted.isBefore(now)) now else shifted
    }

    /** target 加 [0, max] 的随机偏移，只往后推。 */
    fun after(target: Instant, random: RandomSource, max: Duration = MAX_OFFSET): Instant =
        target.plusSeconds(random.nextLong(0, max.seconds + 1))
}
