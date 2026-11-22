package org.xmyun.starmate.feature.chat.interview

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk

/** 模型在访谈结束时调用，参数结构与 [OnboardingProfile] 一致。只改星伴自身数据，按 READ 处理。 */
@Singleton
class FinishOnboardingTool @Inject constructor(
    private val coordinator: InterviewCoordinator,
    private val texts: InterviewTexts,
) : Tool {

    override val name: String = NAME

    override val description: String =
        "Call this when you have learned enough about the user in the first-meeting chat, or when they want to stop. " +
            "Fill in only what the user actually said; leave out anything not mentioned. " +
            "After the call the app shows the user a summary card, so just close the topic warmly in a sentence or two."

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            text("nickname", "What the user wants to be called.")
            text("birthday", "Birthday as MM-DD, or YYYY-MM-DD if the year is known.")
            text("sleepTime", "Usual bedtime, 24-hour HH:mm, e.g. 01:00.")
            text("wakeTime", "Usual wake-up time, 24-hour HH:mm, e.g. 08:30.")
            text("occupation", "What they are mainly busy with now, e.g. studying or a job.")
            list("likes", "Things they like (food, hobbies, music). One item per entry.")
            list("dislikes", "Things they dislike. One item per entry.")
            text("personaPreference", "What personality they want you to have and how they want the two of you to get along.")
            putJsonObject("proactivity") {
                put("type", "string")
                put("enum", buildJsonArray { add(OnboardingProfile.OFTEN); add(OnboardingProfile.SOMETIMES); add(OnboardingProfile.NEVER) })
                put("description", "How often they want you to reach out first: often, sometimes, or never.")
            }
            list("avoidTopics", "Topics they don't want you to bring up. One topic per entry.")
            list("otherFacts", "Other facts about them worth remembering. One fact per entry.")
        }
        put("additionalProperties", false)
    }

    override val risk: ToolRisk = ToolRisk.READ

    override val allowRememberConfirm: Boolean = false

    override fun describeCall(args: JsonObject): String =
        if (texts.chinese) "整理对你的了解" else "Noting what I learned about you"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val profile = OnboardingProfile.fromJson(args)
        coordinator.finish(profile)
        return ToolResult(
            "Saved. The user now sees a summary card of what you noted and can edit it. " +
                "Reply with one or two warm, natural sentences to wrap up; do not repeat the list.",
        )
    }

    private fun JsonObjectBuilder.text(key: String, description: String) {
        putJsonObject(key) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.list(key: String, description: String) {
        putJsonObject(key) {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", description)
        }
    }

    companion object {
        const val NAME = "finish_onboarding"
    }
}
