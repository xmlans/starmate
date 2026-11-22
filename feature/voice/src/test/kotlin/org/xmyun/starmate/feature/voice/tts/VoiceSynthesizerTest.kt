package org.xmyun.starmate.feature.voice.tts

import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.voice.FakeDiagLog
import org.xmyun.starmate.feature.voice.FakeEngineProvider
import org.xmyun.starmate.feature.voice.FakeLedger
import org.xmyun.starmate.feature.voice.FakeMessageStore
import org.xmyun.starmate.feature.voice.FakeProbe
import org.xmyun.starmate.feature.voice.FakeTtsEngine
import org.xmyun.starmate.feature.voice.FixedClock
import org.xmyun.starmate.feature.voice.VoiceAttachments
import org.xmyun.starmate.feature.voice.VoiceDataPortable
import org.xmyun.starmate.feature.voice.VoiceState
import org.xmyun.starmate.feature.voice.VoiceWorkScope
import org.xmyun.starmate.feature.voice.cache.VoiceCache
import org.xmyun.starmate.feature.voice.cache.VoiceMaintenance

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSynthesizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store = FakeMessageStore()
    private val ledger = FakeLedger()
    private val engine = FakeTtsEngine()
    private val engines = FakeEngineProvider(engine)
    private val clock = FixedClock()
    private val diag = FakeDiagLog()
    private val locator = VoiceMessageLocator(store)

    private val cache by lazy { VoiceCache(File(tmp.root, "voice"), File(tmp.root, "sample")) }

    private fun TestScope.synthesizer() = VoiceSynthesizer(
        engines = engines,
        cache = cache,
        probe = FakeProbe(1234),
        ledger = ledger,
        messages = store,
        locator = locator,
        diag = diag,
        clock = clock,
        // 不用 backgroundScope：advanceUntilIdle 不会等后台任务
        work = VoiceWorkScope(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))),
    )

    /** 模拟引擎：后处理器返回附件 → 引擎保存消息 → 通知 TurnListener。 */
    private suspend fun saveAssistant(vararg voices: Pair<String, String>, purpose: TurnPurpose = TurnPurpose.CHAT): ChatMessage {
        val message = ChatMessage(
            role = Role.ASSISTANT,
            rawText = "raw",
            displayText = "显示",
            attachments = voices.map { (id, text) -> VoiceAttachments.create(id, text, null, "[语音] ") },
            purpose = purpose,
            createdAt = clock.instant(),
            status = MessageStatus.COMPLETE,
        )
        val stored = message.copy(id = store.insert(message))
        locator.onTurnCompleted(CompletedTurn(purpose, null, stored, emptyList()))
        return stored
    }

    private fun job(id: String, text: String, purpose: TurnPurpose = TurnPurpose.CHAT, tone: String? = null) =
        VoiceJob(id, text, text, tone, purpose)

    private suspend fun payloadOf(messageId: Long, index: Int = 0) =
        VoiceAttachments.read(store.byId(messageId)!!.attachments[index])!!

    @Test
    fun successMarksAttachmentReady() = runTest {
        val synth = synthesizer()
        synth.enqueue(listOf(job("v1", "想你了", tone = "温柔")))
        val message = saveAssistant("v1" to "想你了")
        assertTrue("v1" in synth.activeIds.value)
        advanceUntilIdle()

        val payload = payloadOf(message.id)
        assertEquals(VoiceState.READY, payload.state)
        assertEquals("v1.mp3", payload.file)
        assertEquals(1234L, payload.durationMs)
        assertTrue(File(cache.dir, "v1.mp3").isFile)
        assertEquals(listOf("想你了" to "温柔"), engine.calls)
        assertFalse("v1" in synth.activeIds.value)
        // 不留临时文件
        assertTrue(cache.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun failureMarksAttachmentFailedAndLeavesNoFile() = runTest {
        engine.fail = true
        val synth = synthesizer()
        synth.enqueue(listOf(job("v1", "想你了")))
        val message = saveAssistant("v1" to "想你了")
        advanceUntilIdle()

        val payload = payloadOf(message.id)
        assertEquals(VoiceState.FAILED, payload.state)
        assertEquals("想你了", payload.transcript)
        assertNull(payload.file)
        assertTrue(cache.dir.listFiles().orEmpty().isEmpty())
        assertTrue(ledger.entries.isEmpty())
    }

    @Test
    fun noEngineMarksFailed() = runTest {
        engines.engine = null
        val synth = synthesizer()
        synth.enqueue(listOf(job("v1", "嗯")))
        val message = saveAssistant("v1" to "嗯")
        advanceUntilIdle()
        assertEquals(VoiceState.FAILED, payloadOf(message.id).state)
    }

    @Test
    fun cacheHitDoesNotCallEngine() = runTest {
        cache.dir.mkdirs()
        File(cache.dir, "v1.wav").writeBytes(byteArrayOf(9, 9))
        val synth = synthesizer()
        synth.enqueue(listOf(job("v1", "嗯")))
        val message = saveAssistant("v1" to "嗯")
        advanceUntilIdle()

        assertTrue(engine.calls.isEmpty())
        assertTrue(ledger.entries.isEmpty())
        val payload = payloadOf(message.id)
        assertEquals(VoiceState.READY, payload.state)
        assertEquals("v1.wav", payload.file)
    }

    @Test
    fun eachRealSynthesisRecordsTtsChars() = runTest {
        val synth = synthesizer()
        synth.enqueue(listOf(job("a", "早上好", TurnPurpose.PROACTIVE), job("b", "起床啦哈哈", TurnPurpose.PROACTIVE)))
        saveAssistant("a" to "早上好", "b" to "起床啦哈哈", purpose = TurnPurpose.PROACTIVE)
        advanceUntilIdle()

        assertEquals(listOf(3, 5), ledger.entries.map { it.ttsChars })
        assertTrue(ledger.entries.all { it.purpose == TurnPurpose.PROACTIVE && it.model == "fake-tts" })
        assertTrue(ledger.entries.all { it.at == clock.instant() })
    }

    @Test
    fun updateRereadsMessageAndKeepsOtherChanges() = runTest {
        val synth = synthesizer()
        synth.enqueue(listOf(job("a", "一"), job("b", "二")))
        val message = saveAssistant("a" to "一", "b" to "二")
        // 合成期间别处改了这条消息
        store.update(store.byId(message.id)!!.copy(displayText = "被别处改过"))
        advanceUntilIdle()

        val latest = store.byId(message.id)!!
        assertEquals("被别处改过", latest.displayText)
        assertEquals(listOf(VoiceState.READY, VoiceState.READY), latest.attachments.map { VoiceAttachments.read(it)!!.state })
        assertEquals(listOf("a.mp3", "b.mp3"), latest.attachments.map { VoiceAttachments.read(it)!!.file })
    }

    @Test
    fun locatorFallsBackToScanWhenNoNotification() = runTest {
        val synth = synthesizer()
        synth.enqueue(listOf(job("v1", "嗯")))
        // 只保存，不通知 TurnListener
        val message = ChatMessage(
            role = Role.ASSISTANT,
            rawText = "raw",
            displayText = "",
            attachments = listOf(VoiceAttachments.create("v1", "嗯", null, "[语音] ")),
            purpose = TurnPurpose.CHAT,
            createdAt = clock.instant(),
        )
        val id = store.insert(message)
        advanceUntilIdle()
        assertEquals(VoiceState.READY, payloadOf(id).state)
    }

    @Test
    fun deletedMessageIsIgnored() = runTest {
        val synth = synthesizer()
        synth.enqueue(listOf(job("v1", "嗯")))
        val message = saveAssistant("v1" to "嗯")
        store.delete(message.id)
        advanceUntilIdle()
        assertTrue(store.updates.isEmpty())
    }

    @Test
    fun cleanupDeletesOldFilesAndMarksAttachmentsFailed() = runTest {
        val synth = synthesizer()
        synth.enqueue(listOf(job("old", "旧的"), job("new", "新的")))
        val message = saveAssistant("old" to "旧的", "new" to "新的")
        advanceUntilIdle()
        File(cache.dir, "old.mp3").setLastModified(clock.instant().minusSeconds(40L * 24 * 3600).toEpochMilli())
        File(cache.dir, "new.mp3").setLastModified(clock.instant().minusSeconds(3600).toEpochMilli())

        val maintenance = VoiceMaintenance(cache, store, synth, clock)
        assertEquals(1, maintenance.cleanOlderThan())

        assertFalse(File(cache.dir, "old.mp3").exists())
        assertTrue(File(cache.dir, "new.mp3").exists())
        assertEquals(VoiceState.FAILED, payloadOf(message.id, 0).state)
        assertEquals("旧的", payloadOf(message.id, 0).transcript)
        assertEquals(VoiceState.READY, payloadOf(message.id, 1).state)
    }

    @Test
    fun sampleSynthesisDoesNotTouchCache() = runTest {
        val synth = synthesizer()
        val file = synth.synthesizeSample("你好呀😊")
        assertTrue(file != null && file.isFile)
        assertEquals("你好呀", engine.calls.single().first)
        assertTrue(cache.audioFiles().isEmpty())
        assertEquals(1, ledger.entries.size)
    }

    @Test
    fun dataPortableRoundTrip() = runTest {
        cache.dir.mkdirs()
        File(cache.dir, "a.mp3").writeBytes(byteArrayOf(1))
        File(cache.dir, ".b.mp3.tmp").writeBytes(byteArrayOf(2))
        val portable = VoiceDataPortable(cache)
        val export = File(tmp.root, "export").also { it.mkdirs() }
        portable.exportTo(export)
        assertEquals(listOf("a.mp3"), export.list()!!.toList())

        File(cache.dir, "a.mp3").delete()
        File(cache.dir, "c.mp3").writeBytes(byteArrayOf(3))
        File(export, "evil name.mp3").writeBytes(byteArrayOf(4))
        portable.importFrom(export)
        assertEquals(listOf("a.mp3"), cache.dir.list()!!.sorted())

        // 导出包里没有 voice 目录时不动现有文件
        portable.importFrom(File(tmp.root, "missing"))
        assertEquals(listOf("a.mp3"), cache.dir.list()!!.sorted())
    }

    @Test
    fun cacheRejectsUnsafeNames() {
        assertNull(cache.resolve("../secret.mp3"))
        assertNull(cache.resolve("a/b.mp3"))
        assertNull(cache.resolve(null))
        assertNull(cache.find("../x"))
        assertTrue(VoiceCache.isAudioName("0f8c-12ab.mp3"))
        assertFalse(VoiceCache.isAudioName(".x.mp3.tmp"))
        assertTrue(Instant.EPOCH.isBefore(clock.instant()))
    }
}
