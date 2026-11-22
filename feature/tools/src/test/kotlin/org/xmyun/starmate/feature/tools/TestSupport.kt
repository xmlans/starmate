package org.xmyun.starmate.feature.tools

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.common.GroupPermissions
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.ToolsPrefs
import org.xmyun.starmate.feature.tools.db.ToolsDatabase

val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

class MutableClock(var now: Instant, private val zone: ZoneId = SHANGHAI) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

/** 2026-11-10 是周二。 */
fun clockAt(local: String): MutableClock =
    MutableClock(java.time.LocalDateTime.parse(local).atZone(SHANGHAI).toInstant())

class FakeToolText(override val chinese: Boolean = true) : ToolText {
    override fun get(id: Int, vararg args: Any): String = "#$id:" + args.joinToString("|")
}

class FakePermissions(vararg granted: ToolGroup) : GroupPermissions {
    val granted = granted.toMutableSet()
    override suspend fun isGranted(group: ToolGroup): Boolean = group in granted
}

class FakeAppState(foreground: Boolean = true) : AppState {
    val flow = MutableStateFlow(foreground)
    override val isForeground: StateFlow<Boolean> = flow
}

fun appContext(): Context = ApplicationProvider.getApplicationContext()

fun newPrefs(scope: CoroutineScope, dir: File): ToolsPrefs =
    ToolsPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "tools-${UUID.randomUUID()}.preferences_pb") }))

fun newDatabase(): ToolsDatabase = ToolsDatabase.build(appContext(), "tools-${UUID.randomUUID()}.db")

fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is JsonElement -> v
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

val CHAT = ToolContext(TurnPurpose.CHAT)
