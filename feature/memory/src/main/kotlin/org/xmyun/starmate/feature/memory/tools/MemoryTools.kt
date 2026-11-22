package org.xmyun.starmate.feature.memory.tools

import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.memory.consolidate.OperationsApplier
import org.xmyun.starmate.feature.memory.consolidate.RenderSource
import org.xmyun.starmate.feature.memory.consolidate.int
import org.xmyun.starmate.feature.memory.consolidate.str
import org.xmyun.starmate.feature.memory.consolidate.text
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.model.Sources
import org.xmyun.starmate.feature.memory.retrieval.Recaller
import org.xmyun.starmate.feature.memory.text.clampImportance

/** 每轮最多记几条碎片。工具列表每轮取一次，取的时候清零。 */
@Singleton
class FragmentBudget @Inject constructor() {
    private val used = AtomicInteger(0)
    fun reset() = used.set(0)
    fun tryTake(max: Int): Boolean {
        while (true) {
            val current = used.get()
            if (current >= max) return false
            if (used.compareAndSet(current, current + 1)) return true
        }
    }
}

private fun schema(properties: JsonObject, required: List<String>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties)
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

private fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

/** 她聊天时悄悄记下一条碎片。重要度最高 4，每轮最多 3 条，重复的会被识别。 */
class RememberFragmentTool @Inject constructor(
    private val repo: MemoryRepository,
    private val renders: RenderSource,
    private val budget: FragmentBudget,
    private val settings: MemorySettings,
    private val clock: Clock,
) : Tool {
    override val name = "remember_fragment"
    override val description: String get() = renders.render().texts["tool_remember_desc"]
    override val risk = ToolRisk.READ
    override val allowRememberConfirm = false

    override val parametersSchema: JsonObject
        get() {
            val t = renders.render().texts
            return schema(
                buildJsonObject {
                    put("content", prop("string", t["tool_remember_content"]))
                    put("importance", prop("integer", t["tool_remember_importance"]))
                    put("tags", prop("string", t["tool_remember_tags"]))
                    put("emotion", prop("integer", t["tool_remember_emotion"]))
                },
                listOf("content"),
            )
        }

    // 平时悄悄记，不打扰用户；但这一轮读过网页之类的外部内容后，要记的东西可能是网页塞进来的，先给用户看
    override val confirmAfterUntrusted = true

    override fun describeCall(args: JsonObject): String = renders.render().texts["tool_remember_label"]

    /** 确认时把要记的内容也写出来，用户才判断得了。 */
    override fun describeConfirm(args: JsonObject): String {
        val label = describeCall(args)
        val content = args.str("content")?.trim().orEmpty()
        if (content.isEmpty()) return label
        val separator = if (label.any { it.code >= 0x2E80 }) "：" else ": "
        return label + separator + content.take(MAX_CONFIRM_CHARS)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val render = renders.render()
        val t = render.texts
        val content = args.str("content")?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult(t["tool_remember_empty"], isError = true)
        if (!budget.tryTake(settings.maxFragmentsPerTurn)) return ToolResult(t["tool_remember_limit"])
        val op = buildJsonObject {
            put("op", "add")
            put("content", content)
            put("category", Categories.FRAGMENT)
            put("tags", args.text("tags") ?: "")
            put("importance", clampImportance(args.int("importance"), 2))
            put("emotion", args.int("emotion") ?: 0)
        }
        val stats = OperationsApplier(repo, render.zone).apply(
            listOf(op), clock.millis(), source = Sources.AI, maxImportance = settings.maxFragmentImportance,
        )
        return ToolResult(if (stats.add > 0) t["tool_remember_ok"] else t["tool_remember_dup"])
    }

    private companion object {
        const val MAX_CONFIRM_CHARS = 200
    }
}

/** 回想更久远的记忆和当时的原话，包括已失效的旧情况。 */
class RecallMemoryTool @Inject constructor(
    private val repo: MemoryRepository,
    private val recaller: Recaller,
    private val renders: RenderSource,
    private val settings: MemorySettings,
    private val clock: Clock,
) : Tool {
    override val name = "recall_memory"
    override val description: String get() = renders.render().texts["tool_recall_desc"]
    override val risk = ToolRisk.READ

    override val parametersSchema: JsonObject
        get() = schema(
            buildJsonObject { put("query", prop("string", renders.render().texts["tool_recall_query"])) },
            listOf("query"),
        )

    override fun describeCall(args: JsonObject): String = renders.render().texts["tool_recall_label"]

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val render = renders.render()
        val query = args.str("query").orEmpty()
        val now = clock.millis()
        val memories = repo.allMemories()
        val qvec = recaller.embedQuery(query)
        val pick = recaller.recall(
            render, memories, query, "", qvec, now, emptySet(), RECALL_BUDGET,
            maxOf(3, settings.recallEpisodes), alwaysEpisodes = true,
        )
        if (pick.isEmpty) return ToolResult(render.texts["tool_recall_none"])
        repo.touch(pick.memories.map { it.id }, now)
        return ToolResult(render.renderRecall(pick, now))
    }

    companion object {
        const val RECALL_BUDGET = 3000
    }
}

/** 按原话编号、说话方和偏移分页读完整原话，每次最多 1200 字符。 */
class ReadEpisodeTool @Inject constructor(
    private val repo: MemoryRepository,
    private val renders: RenderSource,
    private val settings: MemorySettings,
) : Tool {
    override val name = "read_episode"
    override val description: String get() = renders.render().texts["tool_read_desc"]
    override val risk = ToolRisk.READ

    override val parametersSchema: JsonObject
        get() {
            val t = renders.render().texts
            return schema(
                buildJsonObject {
                    put("episode_id", prop("integer", t["tool_read_id"]))
                    putJsonObject("role") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("user"))
                            add(JsonPrimitive("assistant"))
                        }
                        put("description", t["tool_read_role"])
                    }
                    put("offset", prop("integer", t["tool_read_offset"]))
                    put("length", prop("integer", t["tool_read_length"]))
                },
                listOf("episode_id"),
            )
        }

    override fun describeCall(args: JsonObject): String = renders.render().texts["tool_read_label"]

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val t = renders.render().texts
        val role = args.str("role") ?: "user"
        if (role != "user" && role != "assistant") return ToolResult(t["tool_read_bad_role"], isError = true)
        val episode = args.int("episode_id")?.let { repo.episode(it.toLong()) }
            ?: return ToolResult(t["tool_read_missing"], isError = true)
        val text = if (role == "user") episode.userText else episode.replyText
        val start = (args.int("offset") ?: 0).coerceIn(0, text.length)
        val size = (args.int("length") ?: 1000).coerceIn(1, settings.readEpisodeMaxChars)
        val end = minOf(text.length, start + size)
        val next = if (end < text.length) end.toString() else t["tool_read_end"]
        val header = t.fmt("tool_read_header", "id" to episode.id, "role" to role, "offset" to start, "total" to text.length, "next" to next)
        return ToolResult(header + "\n" + text.substring(start, end))
    }
}

/** 三个记忆工具只在聊天时提供。 */
@Singleton
class MemoryToolProvider @Inject constructor(
    private val remember: RememberFragmentTool,
    private val recall: RecallMemoryTool,
    private val read: ReadEpisodeTool,
    private val budget: FragmentBudget,
) : ToolProvider {
    override suspend fun tools(purpose: TurnPurpose): List<Tool> {
        if (purpose != TurnPurpose.CHAT) return emptyList()
        budget.reset()
        return listOf(remember, recall, read)
    }
}
