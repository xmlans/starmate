package org.xmyun.starmate.feature.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ProcessedReply
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.voice.prefs.VoiceFrequency
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs

@RunWith(RobolectricTestRunner::class)
class VoicePostProcessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: VoicePrefs
    private val engines = FakeEngineProvider()
    private val sink = FakeSink()
    private val locale = FakeLocale(chinese = true)
    private val clock = FixedClock()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = VoicePrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "voice.preferences_pb") }))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun processor() = VoicePostProcessor(engines, prefs, sink, locale, clock)

    private suspend fun finish(raw: String, purpose: TurnPurpose = TurnPurpose.CHAT, existing: List<Attachment> = emptyList()) =
        processor().finish(raw, ProcessedReply(raw, existing), purpose)

    @Test
    fun voiceSegmentBecomesRecordingAttachment() = runTest {
        val reply = finish("你好<voice tone=\"温柔\">想你了</voice>晚安")
        assertEquals("你好晚安", reply.displayText)
        val attachment = reply.attachments.single()
        assertEquals(AttachmentTypes.VOICE, attachment.type)
        val payload = VoiceAttachments.read(attachment)!!
        assertEquals(VoiceState.RECORDING, payload.state)
        assertEquals("想你了", payload.transcript)
        assertEquals("温柔", payload.tone)
        assertTrue(payload.preview!!.startsWith("[语音] "))
        assertEquals("[语音] 想你了", payload.preview)

        val job = sink.jobs.single()
        assertEquals(payload.id, job.voiceId)
        assertEquals("想你了", job.ttsText)
        assertEquals("温柔", job.tone)
        assertEquals(TurnPurpose.CHAT, job.purpose)
    }

    @Test
    fun englishPreviewPrefix() = runTest {
        locale.chinese = false
        val payload = VoiceAttachments.read(finish("<voice>miss you</voice>").attachments.single())!!
        assertTrue(payload.preview!!.startsWith("[Voice] "))
    }

    @Test
    fun existingAttachmentsKept() = runTest {
        val quote = Attachment(AttachmentTypes.QUOTE, JsonObject(emptyMap()))
        val reply = finish("好<voice>嗯</voice>", existing = listOf(quote))
        assertEquals(listOf(AttachmentTypes.QUOTE, AttachmentTypes.VOICE), reply.attachments.map { it.type })
    }

    @Test
    fun replyWithoutTagsReturnedAsIs() = runTest {
        val input = ProcessedReply("今天 1 < 2 吗", emptyList())
        assertSame(input, processor().finish("今天 1 < 2 吗", input, TurnPurpose.CHAT))
        assertTrue(sink.jobs.isEmpty())
    }

    @Test
    fun overlongSegmentRestoredAsText() = runTest {
        val long = "啊".repeat(301)
        val reply = finish("前<voice>$long</voice>后")
        assertEquals("前${long}后", reply.displayText)
        assertTrue(reply.attachments.isEmpty())
        assertTrue(sink.jobs.isEmpty())

        val exact = "嗯".repeat(300)
        assertEquals(1, finish("<voice>$exact</voice>").attachments.size)
    }

    @Test
    fun dailyLimitRestoresExtraSegments() = runTest {
        prefs.setDailyLimit(2)
        val reply = finish("<voice>一</voice>，<voice>二</voice>，<voice>三</voice>")
        assertEquals(listOf("一", "二"), reply.attachments.map { VoiceAttachments.read(it)!!.transcript })
        assertEquals("，，三", reply.displayText)

        val next = finish("好<voice>四</voice>")
        assertTrue(next.attachments.isEmpty())
        assertEquals("好四", next.displayText)
        assertEquals(2, sink.jobs.size)
    }

    @Test
    fun dailyCountResetsNextDay() = runTest {
        prefs.setDailyLimit(1)
        assertEquals(1, finish("<voice>一</voice>").attachments.size)
        assertEquals(0, finish("<voice>二</voice>").attachments.size)
        clock.now = clock.now.plusSeconds(24 * 3600)
        assertEquals(1, finish("<voice>三</voice>").attachments.size)
    }

    @Test
    fun noEngineRestoresText() = runTest {
        engines.engine = null
        val reply = finish("你好<voice>想你了</voice>晚安")
        assertEquals("你好想你了晚安", reply.displayText)
        assertTrue(reply.attachments.isEmpty())
        assertTrue(sink.jobs.isEmpty())
    }

    @Test
    fun frequencyNeverRestoresText() = runTest {
        prefs.setFrequency(VoiceFrequency.NEVER)
        val reply = finish("Okay.<voice>Good night</voice>")
        assertEquals("Okay. Good night", reply.displayText)
        assertTrue(reply.attachments.isEmpty())
    }

    @Test
    fun unsupportedPurposeRestoresText() = runTest {
        val reply = finish("写<voice>日记</voice>", purpose = TurnPurpose.DIARY)
        assertEquals("写日记", reply.displayText)
        assertTrue(reply.attachments.isEmpty())
    }

    @Test
    fun proactivePurposeProducesVoice() = runTest {
        val reply = finish("<voice>在干嘛呀</voice>", purpose = TurnPurpose.PROACTIVE)
        assertEquals("", reply.displayText)
        assertEquals(1, reply.attachments.size)
        assertEquals(TurnPurpose.PROACTIVE, sink.jobs.single().purpose)
    }

    @Test
    fun emojiOnlySegmentRestoredAsText() = runTest {
        val reply = finish("好呀<voice>😊</voice>")
        assertEquals("好呀😊", reply.displayText)
        assertTrue(reply.attachments.isEmpty())
    }

    @Test
    fun ttsTextStripsEmojiAndMarkdownButTranscriptKeepsThem() = runTest {
        val reply = finish("<voice>**晚安**😊</voice>")
        assertEquals("**晚安**😊", VoiceAttachments.read(reply.attachments.single())!!.transcript)
        assertEquals("晚安", sink.jobs.single().ttsText)
    }

    @Test
    fun strayTagsOnlyAreStripped() = runTest {
        val reply = finish("好的</voice>")
        assertEquals("好的", reply.displayText)
        assertTrue(reply.attachments.isEmpty())
    }

    @Test
    fun previewDelegatesToParser() {
        assertEquals("在吗", processor().preview("在吗<voi"))
        assertEquals("你好晚安", processor().preview("你好<voice>想你了</voice>晚安"))
    }
}
