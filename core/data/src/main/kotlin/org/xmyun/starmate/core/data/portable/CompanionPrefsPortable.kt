package org.xmyun.starmate.core.data.portable

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.TurnPurpose

/**
 * 共享偏好的导出导入（id `prefs`）。导出 CompanionPrefs 的当前值为 prefs.json，不复制 DataStore 原始文件。
 * 导入时逐项通过 setter 写回：缺失或不认识的项跳过、保留现有值；JSON 整体不合法时抛 IllegalArgumentException。
 */
@Singleton
class CompanionPrefsPortable @Inject constructor(
    private val prefs: CompanionPrefs,
) : DataPortable {

    override val id: String = ID

    override suspend fun exportTo(dir: File) {
        val quiet = prefs.quietHours.first()
        val pending = prefs.pendingOnboardingProfile.first()
        val root = buildJsonObject {
            put(ADULT_CONFIRMED, prefs.adultConfirmed.first())
            put(ONBOARDING_STATE, prefs.onboardingState.first().name)
            put(PENDING_PROFILE, pending?.let(::JsonPrimitive) ?: JsonNull)
            putJsonObject(QUIET_HOURS) {
                put(START_MINUTE, quiet.startMinute)
                put(END_MINUTE, quiet.endMinute)
            }
            put(CLINGINESS, prefs.clinginess.first().name)
            putJsonObject(DAILY_BUDGETS) {
                for (purpose in BUDGET_PURPOSES) put(purpose.name, prefs.dailyBudget(purpose).first())
            }
            put(HISTORY_TOKEN_BUDGET, prefs.historyTokenBudget.first())
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
        }
    }

    override suspend fun importFrom(dir: File) {
        val file = File(dir, FILE_NAME)
        val text = withContext(Dispatchers.IO) { if (file.isFile) file.readText(Charsets.UTF_8) else null }
            ?: return
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("prefs.json is not valid JSON", e)
        }
        val root = element as? JsonObject ?: throw IllegalArgumentException("prefs.json is not a JSON object")

        root[ADULT_CONFIRMED].bool()?.let { prefs.setAdultConfirmed(it) }
        root[ONBOARDING_STATE].enumOf(OnboardingState.entries)?.let { prefs.setOnboardingState(it) }
        when (val pending = root[PENDING_PROFILE]) {
            is JsonNull -> prefs.setPendingOnboardingProfile(null)
            is JsonPrimitive -> if (pending.isString) prefs.setPendingOnboardingProfile(pending.content)
            else -> Unit
        }
        (root[QUIET_HOURS] as? JsonObject)?.let { quiet ->
            val start = quiet[START_MINUTE].int()
            val end = quiet[END_MINUTE].int()
            if (start != null && end != null && start in MINUTE_RANGE && end in MINUTE_RANGE) {
                prefs.setQuietHours(QuietHours(start, end))
            }
        }
        root[CLINGINESS].enumOf(Clinginess.entries)?.let { prefs.setClinginess(it) }
        (root[DAILY_BUDGETS] as? JsonObject)?.let { budgets ->
            for (purpose in BUDGET_PURPOSES) {
                budgets[purpose.name].int()?.takeIf { it >= 0 }?.let { prefs.setDailyBudget(purpose, it) }
            }
        }
        root[HISTORY_TOKEN_BUDGET].int()?.takeIf { it >= 0 }?.let { prefs.setHistoryTokenBudget(it) }
    }

    private fun JsonElement?.bool(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonElement?.int(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun <E : Enum<E>> JsonElement?.enumOf(entries: List<E>): E? {
        val primitive = (this as? JsonPrimitive)?.takeIf { it.isString } ?: return null
        return entries.firstOrNull { it.name == primitive.content }
    }

    companion object {
        const val ID = "prefs"
        const val FILE_NAME = "prefs.json"

        private val json = Json { prettyPrint = true }
        private val MINUTE_RANGE = 0..1439
        private val BUDGET_PURPOSES = listOf(TurnPurpose.MEMORY, TurnPurpose.PROACTIVE, TurnPurpose.DIARY)

        private const val ADULT_CONFIRMED = "adultConfirmed"
        private const val ONBOARDING_STATE = "onboardingState"
        private const val PENDING_PROFILE = "pendingOnboardingProfile"
        private const val QUIET_HOURS = "quietHours"
        private const val START_MINUTE = "startMinute"
        private const val END_MINUTE = "endMinute"
        private const val CLINGINESS = "clinginess"
        private const val DAILY_BUDGETS = "dailyBudgets"
        private const val HISTORY_TOKEN_BUDGET = "historyTokenBudget"
    }
}
