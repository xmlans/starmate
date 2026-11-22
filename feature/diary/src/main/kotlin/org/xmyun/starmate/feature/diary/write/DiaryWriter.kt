package org.xmyun.starmate.feature.diary.write

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.MemoryCategory
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.diary.R
import org.xmyun.starmate.feature.diary.db.DiaryDatabaseHolder
import org.xmyun.starmate.feature.diary.db.DiaryEntryEntity
import org.xmyun.starmate.feature.diary.prefs.DiaryPrefs

/** 写给模型的日记提示词，`@@键` 分段。 */
class DiaryPrompt(private val map: Map<String, String>) {
    operator fun get(key: String): String = map[key] ?: error("missing diary prompt: $key")

    companion object {
        fun parse(raw: String): DiaryPrompt {
            val map = LinkedHashMap<String, String>()
            var key: String? = null
            val buffer = StringBuilder()
            fun flush() {
                key?.let { map[it] = buffer.toString().trimEnd('\n', '\r') }
                buffer.setLength(0)
            }
            for (line in raw.replace("\r\n", "\n").split('\n')) {
                if (line.startsWith("@@")) {
                    flush()
                    key = line.removePrefix("@@").trim()
                } else if (key != null) {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line)
                }
            }
            flush()
            return DiaryPrompt(map)
        }
    }
}

/** 按系统语言读取 res/raw 或 res/raw-zh 下的提示词。 */
interface DiaryPromptSource {
    fun prompt(): DiaryPrompt
}

@Singleton
class ResourceDiaryPromptSource @Inject constructor(@ApplicationContext private val context: Context) : DiaryPromptSource {
    override fun prompt(): DiaryPrompt = DiaryPrompt.parse(
        context.resources.openRawResource(R.raw.diary_prompt).bufferedReader(Charsets.UTF_8).use { it.readText() },
    )
}

sealed interface DiaryRunResult {
    data class Written(val entryId: Long) : DiaryRunResult
    data object NoSource : DiaryRunResult
    data object NoConversation : DiaryRunResult
    data object NotChosen : DiaryRunResult
    data object AlreadyWritten : DiaryRunResult
    data object ModelDeclined : DiaryRunResult
    data object Invalid : DiaryRunResult
    data object Budget : DiaryRunResult
    data object Failed : DiaryRunResult
}

/**
 * 写一篇日记：取当天梗概 → 过抽签 → 让她（带人设）写 → 存进 diary.db（未读）→ 摘要回灌记忆。
 * 任何一步不满足都不写，原因写进诊断日志。
 */
@Singleton
class DiaryWriter @Inject constructor(
    private val summarySource: Optional<DaySummarySource>,
    private val memoryWriter: Optional<MemoryWriter>,
    private val engine: ConversationEngine,
    private val holder: DiaryDatabaseHolder,
    private val prefs: DiaryPrefs,
    private val prompts: DiaryPromptSource,
    private val clock: Clock,
    private val diag: DiagLog,
) {
    /** 0–1 的随机数，测试里可替换。 */
    internal var random: () -> Double = { Random.nextDouble() }

    private val mutex = Mutex()

    /** force 为真时跳过抽签（设置页的“立即写一篇”），仍要求当天有对话。 */
    suspend fun run(force: Boolean = false): DiaryRunResult = mutex.withLock { runLocked(force) }

    private suspend fun runLocked(force: Boolean): DiaryRunResult {
        val now = clock.instant()
        val range = DiaryDay.range(now, clock.zone)
        val source = summarySource.orElse(null) ?: return log(DiaryRunResult.NoSource, "no day summary source")
        val dao = holder.dao()
        val day = range.date.toString()
        // 不花钱的检查都放在前面：refresh = true 会让记忆模块调用模型整理当天的对话，
        // 只有确定要写这一篇时才值得做。
        if (dao.byDate(day) != null) return log(DiaryRunResult.AlreadyWritten, "already wrote for $day")
        val last = dao.latest()
        val quick = summarize(source, range.from, now, refresh = false)
            ?: return log(DiaryRunResult.NoConversation, "no conversation for ${range.date}")
        if (force) {
            if (quick.turnCount < 1) return log(DiaryRunResult.NoConversation, "no user turns for $day")
        } else {
            val frequency = prefs.frequency.first()
            val lastDate = last?.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            if (!DiaryDecider.shouldAttempt(quick.turnCount, frequency, lastDate, range.date, random())) {
                return log(DiaryRunResult.NotChosen, "not writing for $day (turns=${quick.turnCount}, frequency=$frequency)")
            }
        }
        val summary = summarize(source, range.from, now, refresh = true)
            ?: return log(DiaryRunResult.NoConversation, "no conversation for ${range.date}")
        val prompt = prompts.prompt()
        val instruction = prompt["instruction"]
            .replace("{date}", day)
            .replace("{gist}", summary.gist.ifBlank { prompt["no_gist"] })
            .replace("{memories}", summary.newMemories.joinToString("\n") { "- $it" }.ifBlank { prompt["no_memories"] })
            .replace("{last_ending}", last?.let { lastParagraph(it.body) }?.ifBlank { null } ?: prompt["no_last"])
        val result = engine.runHeadless(
            HeadlessTurn(
                purpose = TurnPurpose.DIARY,
                instruction = instruction,
                role = prefs.role.first(),
                systemOverride = null,
                jsonOutput = true,
            ),
        )
        val text = when (result) {
            is HeadlessResult.Success -> result.text
            HeadlessResult.BudgetExceeded -> return log(DiaryRunResult.Budget, "diary skipped: daily budget used up")
            is HeadlessResult.Failed -> return log(DiaryRunResult.Failed, "diary model call failed: ${result.error.kind}")
        }
        val output = when (val parsed = DiaryOutput.parse(text)) {
            DiaryOutput.Invalid -> return log(DiaryRunResult.Invalid, "diary output could not be parsed")
            DiaryOutput.Skip -> return log(DiaryRunResult.ModelDeclined, "she chose not to write today")
            is DiaryOutput.Write -> parsed
        }
        val id = try {
            dao.insert(
                DiaryEntryEntity(
                    date = day,
                    title = output.title,
                    mood = output.mood,
                    body = output.body,
                    summary = output.summary,
                    createdAt = now.toEpochMilli(),
                    read = false,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return log(DiaryRunResult.AlreadyWritten, "diary insert failed: ${e.javaClass.simpleName}")
        }
        // 回灌：保证她之后说的话和日记对得上
        val memory = output.summary.ifBlank { output.title }
        memoryWriter.orElse(null)?.let { writer ->
            try {
                writer.add(memory, MemoryCategory.BETWEEN_US, 3)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "diary summary not written to memory: ${e.javaClass.simpleName}")
            }
        }
        diag.log(TAG, "diary written for $day")
        return DiaryRunResult.Written(id)
    }

    private suspend fun summarize(
        source: DaySummarySource,
        from: java.time.Instant,
        to: java.time.Instant,
        refresh: Boolean,
    ): org.xmyun.starmate.core.model.DaySummary? = try {
        source.daySummary(from, to, refresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        diag.log(TAG, "day summary failed: ${e.javaClass.simpleName}")
        null
    }

    private fun log(result: DiaryRunResult, message: String): DiaryRunResult {
        diag.log(TAG, message)
        return result
    }

    companion object {
        private const val TAG = "diary"
    }
}
