package org.xmyun.starmate.feature.memory.consolidate

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.TurnPurpose

sealed interface LlmOutcome {
    /** data 为 null 表示返回的不是 JSON。 */
    data class Ok(val data: JsonObject?) : LlmOutcome
    data object Budget : LlmOutcome
    data class Error(val reason: String) : LlmOutcome
}

/** 整理、压缩、反思都走 runHeadless：用途 MEMORY、BACKGROUND 模型、自定义提示词、要求 JSON。 */
@Singleton
class MemoryLlm @Inject constructor(private val engine: ConversationEngine) {
    suspend fun call(system: String, instruction: String): LlmOutcome =
        when (
            val result = engine.runHeadless(
                HeadlessTurn(
                    purpose = TurnPurpose.MEMORY,
                    instruction = instruction,
                    role = ModelRole.BACKGROUND,
                    systemOverride = system,
                    jsonOutput = true,
                ),
            )
        ) {
            is HeadlessResult.Success -> LlmOutcome.Ok(OperationsParser.parseObject(result.text))
            HeadlessResult.BudgetExceeded -> LlmOutcome.Budget
            is HeadlessResult.Failed -> LlmOutcome.Error("${result.error.kind}")
        }
}

fun JsonObject.ops(): List<JsonObject> = (this["ops"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
