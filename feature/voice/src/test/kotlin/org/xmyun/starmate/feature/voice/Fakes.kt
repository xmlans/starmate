package org.xmyun.starmate.feature.voice

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.feature.voice.cache.AudioDurationProbe
import org.xmyun.starmate.feature.voice.tts.TtsEngine
import org.xmyun.starmate.feature.voice.tts.TtsEngineProvider
import org.xmyun.starmate.feature.voice.tts.TtsResult
import org.xmyun.starmate.feature.voice.tts.VoiceJob
import org.xmyun.starmate.feature.voice.tts.VoiceJobSink

class FixedClock(var now: Instant = Instant.parse("2026-11-12T04:00:00Z"), private val zone: ZoneId = ZoneId.of("Asia/Shanghai")) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = FixedClock(now, zone)
    override fun instant(): Instant = now
}

class FakeDiagLog : DiagLog {
    val lines = mutableListOf<String>()
    override fun log(tag: String, message: String) {
        lines += "$tag: $message"
    }
    override val entries: StateFlow<List<DiagEntry>> = MutableStateFlow(emptyList())
}

class FakeLedger : UsageLedger {
    val entries = mutableListOf<UsageEntry>()
    override suspend fun record(entry: UsageEntry) {
        entries += entry
    }
    override suspend fun usedToday(purpose: TurnPurpose): Int = 0
    override suspend fun allow(purpose: TurnPurpose): Boolean = true
    override fun observeDaily(days: Int): Flow<List<DailyUsage>> = emptyFlow()
}

class FakeMessageStore : MessageStore {
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var nextId = 1L
    val updates = mutableListOf<ChatMessage>()

    override fun observeRecent(limit: Int): Flow<List<ChatMessage>> = messages.map { it.takeLast(limit) }
    override suspend fun recent(limit: Int): List<ChatMessage> = messages.value.takeLast(limit)
    override suspend fun between(from: Instant, to: Instant): List<ChatMessage> =
        messages.value.filter { it.createdAt >= from && it.createdAt < to }
    override suspend fun byId(id: Long): ChatMessage? = messages.value.firstOrNull { it.id == id }

    override suspend fun insert(message: ChatMessage): Long {
        val id = nextId++
        messages.value = messages.value + message.copy(id = id)
        return id
    }

    override suspend fun update(message: ChatMessage) {
        updates += message
        messages.value = messages.value.map { if (it.id == message.id) message else it }
    }

    override suspend fun delete(id: Long) {
        messages.value = messages.value.filterNot { it.id == id }
    }

    override suspend fun lastMessageAt(role: Role?): Instant? =
        messages.value.lastOrNull { role == null || it.role == role }?.createdAt
}

class FakeRouter(
    var tts: EndpointConfig? = null,
    var stt: EndpointConfig? = null,
) : ModelRouter {
    override suspend fun chat(role: ModelRole): LlmClient? = null
    override suspend fun embedding(): EmbeddingClient? = null
    override suspend fun endpoint(role: ModelRole): EndpointConfig? = when (role) {
        ModelRole.TTS -> tts
        ModelRole.STT -> stt
        else -> throw IllegalArgumentException("only TTS/STT")
    }
    override suspend fun markToolsUnsupported(role: ModelRole) = Unit
}

class FakeTtsEngine(
    var fail: Boolean = false,
    override val model: String = "fake-tts",
    override val format: String = "mp3",
) : TtsEngine {
    val calls = mutableListOf<Pair<String, String?>>()

    override suspend fun synthesize(text: String, tone: String?, target: File): TtsResult {
        calls += text to tone
        if (fail) return TtsResult.Failure("boom")
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3, 4))
        return TtsResult.Success
    }
}

class FakeEngineProvider(var engine: TtsEngine? = FakeTtsEngine()) : TtsEngineProvider {
    override suspend fun available(): Boolean = engine != null
    override suspend fun current(): TtsEngine? = engine
}

class FakeSink : VoiceJobSink {
    val jobs = mutableListOf<VoiceJob>()
    override fun enqueue(jobs: List<VoiceJob>) {
        this.jobs += jobs
    }
}

class FakeLocale(var chinese: Boolean = true) : VoiceLocale {
    override fun chinese(): Boolean = chinese
}

class FakeProbe(var duration: Long? = 1234L) : AudioDurationProbe {
    override fun durationMs(file: File): Long? = duration
}
