package org.xmyun.starmate.feature.proactive.tools

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.proactive.notify.ProactiveNotifier
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs

/** 只改动星伴自身数据，按 READ 处理，不弹确认；用户能在主动消息设置页查看和取消。 */
class ScheduleCareTool @Inject constructor(
    private val service: CareService,
    private val notifier: ProactiveNotifier,
    private val clock: Clock,
) : Tool {
    override val name = "schedule_care"
    override val description =
        "Arrange to come back to the user on your own later, e.g. to ask how tomorrow's interview went. " +
            "When the time comes you will decide again whether a message still makes sense; nothing is sent " +
            "if they already told you about it. Times are the user's local time."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("when") {
                put("type", "string")
                put("description", "Local date and time in ISO-8601, e.g. 2026-11-13T18:30. Must be in the future and within 90 days.")
            }
            putJsonObject("topic") {
                put("type", "string")
                put("description", "What to check in about, a few words, e.g. \"job interview\".")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "One sentence for your future self: why this matters and what to ask.")
            }
        }
        putJsonArray("required") {
            add("when")
            add("topic")
            add("reason")
        }
    }
    override val risk = ToolRisk.READ

    // 这一轮读过网页之类的外部内容后，约定以后来找用户这件事可能是网页诱导的，先给用户看
    override val confirmAfterUntrusted = true

    override fun describeCall(args: JsonObject): String = args.string("topic") ?: name

    /** 确认时写明什么时候、为什么来找用户。 */
    override fun describeConfirm(args: JsonObject): String {
        val topic = args.string("topic")?.take(80) ?: name
        val time = args.string("when")?.let { raw -> parseWhen(raw, clock.zone)?.let { formatLocal(it, clock.zone) } ?: raw.take(40) }
        val reason = args.string("reason")?.take(200)
        return buildString {
            append(topic)
            if (time != null) append(" (").append(time).append(')')
            if (!reason.isNullOrBlank()) append(": ").append(reason)
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val zone = clock.zone
        val rawWhen = args.string("when") ?: return ToolResult("Missing \"when\".", isError = true)
        val at = parseWhen(rawWhen, zone)
            ?: return ToolResult("Could not read \"when\": use a local ISO-8601 date-time like 2026-11-13T18:30.", isError = true)
        val topic = args.string("topic").orEmpty()
        val reason = args.string("reason").orEmpty()
        return when (val result = service.schedule(at, topic, reason)) {
            is ScheduleResult.Rejected -> ToolResult(rejection(result.reason), isError = true)
            is ScheduleResult.Scheduled -> {
                val local = formatLocal(result.care.scheduledFor, zone)
                val base = "Scheduled care #${result.care.id} about \"${result.care.topic}\" for around $local."
                val warning = if (notifier.canNotify()) {
                    ""
                } else {
                    " Note: notification permission is not granted yet, so the user may not receive this message. " +
                        "You can tell them honestly that they can turn on notifications in Settings > Proactive messages."
                }
                ToolResult(base + warning)
            }
        }
    }

    private fun rejection(reason: RejectReason): String = when (reason) {
        RejectReason.BLANK_TOPIC -> "Rejected: \"topic\" is empty."
        RejectReason.IN_PAST -> "Rejected: that time is not in the future."
        RejectReason.TOO_FAR -> "Rejected: that time is more than 90 days away."
        RejectReason.TOO_MANY -> "Rejected: there are already ${CareService.MAX_PENDING} pending check-ins. Cancel one first (see list_cares)."
    }
}

class CancelCareTool @Inject constructor(
    private val service: CareService,
) : Tool {
    override val name = "cancel_care"
    override val description = "Cancel a pending check-in you arranged with schedule_care, e.g. when the user says they don't want it."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") {
                put("type", "integer")
                put("description", "The id returned by schedule_care or shown by list_cares.")
            }
        }
        putJsonArray("required") { add("id") }
    }
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.trim().removePrefix("#").toLongOrNull() }
            ?: return ToolResult("Missing or invalid \"id\".", isError = true)
        return if (service.cancel(id)) {
            ToolResult("Cancelled care #$id.")
        } else {
            ToolResult("No pending check-in with id #$id.", isError = true)
        }
    }
}

class ListCaresTool @Inject constructor(
    private val service: CareService,
    private val clock: Clock,
) : Tool {
    override val name = "list_cares"
    override val description = "List the check-ins you have arranged that have not happened yet."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pending = service.pending()
        if (pending.isEmpty()) return ToolResult("No pending check-ins.")
        val zone = clock.zone
        val lines = pending.joinToString("\n") { care ->
            val reason = if (care.reason.isBlank()) "" else " — ${care.reason}"
            "#${care.id} · ${formatLocal(care.scheduledFor, zone)} · ${care.topic}$reason"
        }
        return ToolResult(lines)
    }
}

/** 只在 CHAT 用途且总开关打开时提供。 */
class ProactiveToolProvider @Inject constructor(
    private val prefs: ProactivePrefs,
    private val schedule: ScheduleCareTool,
    private val cancel: CancelCareTool,
    private val list: ListCaresTool,
) : ToolProvider {
    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && prefs.enabled.first()) listOf(schedule, cancel, list) else emptyList()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private val LOCAL_MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

internal fun formatLocal(at: Instant, zone: ZoneId): String = LocalDateTime.ofInstant(at, zone).format(LOCAL_MINUTES)

/** 本地时间优先；也接受带时区偏移的写法，以及用空格代替 T 的写法。 */
internal fun parseWhen(raw: String, zone: ZoneId): Instant? {
    val text = raw.trim().let { if (it.length > 10 && it[10] == ' ') it.replaceRange(10, 11, "T") else it }
    try {
        return LocalDateTime.parse(text).atZone(zone).toInstant()
    } catch (e: Exception) {
        // 继续尝试其他格式
    }
    try {
        return OffsetDateTime.parse(text).toInstant()
    } catch (e: Exception) {
        // 继续尝试其他格式
    }
    return try {
        ZonedDateTime.parse(text).toInstant()
    } catch (e: Exception) {
        null
    }
}
