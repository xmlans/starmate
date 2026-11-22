package org.xmyun.starmate.core.engine

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.AmbientSource

/**
 * 环境块：第一行是当前本地日期、星期和时间，后面是各 AmbientSource 的非空结果。
 * 每个来源最多等 2 秒，超时或抛异常都忽略。它每轮都会变，只能进动态部分。
 */
class AmbientBlock @Inject constructor(
    sources: Set<@JvmSuppressWildcards AmbientSource>,
    private val clock: Clock,
) {
    // 按 id 排序，保证同样的数据得到同样的文字
    private val sources = sources.sortedBy { it.id }

    suspend fun render(chinese: Boolean): String {
        val lines = mutableListOf(timeLine(chinese))
        lines += snapshots()
        return lines.joinToString("\n")
    }

    fun timeLine(chinese: Boolean): String {
        val now = ZonedDateTime.now(clock)
        return if (chinese) {
            "现在是 " + now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE))
        } else {
            "Now: " + now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, HH:mm", Locale.ENGLISH))
        }
    }

    private suspend fun snapshots(): List<String> = coroutineScope {
        sources.map { source ->
            async {
                try {
                    withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { source.snapshot() }
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
    }
}
