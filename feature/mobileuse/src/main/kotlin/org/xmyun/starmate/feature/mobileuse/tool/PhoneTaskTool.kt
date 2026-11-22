package org.xmyun.starmate.feature.mobileuse.tool

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.mobileuse.agent.PhoneTaskRunner
import org.xmyun.starmate.feature.mobileuse.agent.PromptSource
import org.xmyun.starmate.feature.mobileuse.agent.RunnerTexts
import org.xmyun.starmate.feature.mobileuse.agent.TaskOutcome

/** 为每个任务创建一个新的 [PhoneTaskRunner]（读取当时的上限设置）。 */
fun interface PhoneTaskRunnerFactory {
    suspend fun create(): PhoneTaskRunner
}

/**
 * phone_task：在手机上代为操作。WRITE，每次都要用户确认，不提供“以后不再问”。
 * 执行时再核对一次用途和可用条件（G8），同一时间只允许一个任务。
 */
class PhoneTaskTool @Inject constructor(
    private val availability: AvailabilitySource,
    private val coordinator: TaskCoordinator,
    private val runners: PhoneTaskRunnerFactory,
    private val prompts: PromptSource,
) : Tool {

    override val name: String = NAME

    override val description: String =
        "Operate apps on the user's phone for them, step by step, through the accessibility service. " +
            "Only use when the user explicitly asks you to do something on their phone. Works only inside apps " +
            "the user has allowed; it never handles payments, transfers, passwords or verification codes, and " +
            "never opens banking, payment, system settings, SMS, authenticator or password apps. " +
            "The user confirms every call and can stop it at any time. Returns a summary or the reason it stopped."

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("goal") {
                put("type", "string")
                put("description", "What to do on the phone, in one or two plain sentences, in the user's language.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("goal")) }
    }

    override val risk: ToolRisk = ToolRisk.WRITE

    override val allowRememberConfirm: Boolean = false

    override fun describeCall(args: JsonObject): String {
        val goal = goalOf(args).orEmpty()
        return if (prompts.chinese) "在手机上操作：$goal" else "Operate the phone: $goal"
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val texts = RunnerTexts(prompts.chinese)
        if (ctx.purpose != TurnPurpose.CHAT) return ToolResult(texts.notFromChat(), isError = true)
        val goal = goalOf(args)?.trim()
        if (goal.isNullOrEmpty()) return ToolResult(texts.emptyGoal(), isError = true)
        if (!availability.current().available) return ToolResult(texts.unavailable(), isError = true)
        val runner = runners.create()
        val outcome = coordinator.runExclusive(runner) { it.run(goal) }
            ?: return ToolResult(texts.busy(), isError = true)
        return ToolResult(runner.describe(outcome), isError = outcome !is TaskOutcome.Done)
    }

    private fun goalOf(args: JsonObject): String? = (args["goal"] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val NAME = "phone_task"
    }
}
