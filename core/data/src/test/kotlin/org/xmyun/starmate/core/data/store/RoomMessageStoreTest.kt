package org.xmyun.starmate.core.data.store

import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.newHolder
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose

@RunWith(RobolectricTestRunner::class)
class RoomMessageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var holder: ChatDatabaseHolder
    private lateinit var store: RoomMessageStore

    private val base = Instant.parse("2026-11-11T10:00:00Z")

    @Before
    fun setUp() {
        holder = newHolder(tmp.root)
        store = RoomMessageStore(holder)
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private fun msg(text: String, at: Instant, role: Role = Role.USER) = ChatMessage(
        id = 999,
        role = role,
        rawText = text,
        displayText = text,
        purpose = TurnPurpose.CHAT,
        createdAt = at,
    )

    @Test
    fun insertReturnsIncreasingIdsAndIgnoresGivenId() = runTest {
        val a = store.insert(msg("a", base))
        val b = store.insert(msg("b", base.plusSeconds(1)))
        val c = store.insert(msg("c", base.plusSeconds(2)))
        assertTrue(a < b && b < c)
        assertEquals("a", store.byId(a)!!.rawText)
        assertEquals(a, store.byId(a)!!.id)
    }

    @Test
    fun recentReturnsLastNInChronologicalOrder() = runTest {
        store.insert(msg("1", base))
        store.insert(msg("2", base.plusSeconds(1)))
        store.insert(msg("3", base.plusSeconds(2)))
        val recent = store.recent(2)
        assertEquals(listOf("2", "3"), recent.map { it.rawText })
        assertEquals(listOf("2", "3"), store.observeRecent(2).first().map { it.rawText })
        assertEquals(emptyList<ChatMessage>(), store.recent(0))
    }

    @Test
    fun betweenIsLeftClosedRightOpen() = runTest {
        store.insert(msg("before", base.minusMillis(1)))
        store.insert(msg("start", base))
        store.insert(msg("middle", base.plusSeconds(30)))
        store.insert(msg("end", base.plusSeconds(60)))
        val result = store.between(base, base.plusSeconds(60))
        assertEquals(listOf("start", "middle"), result.map { it.rawText })
    }

    @Test
    fun updateChangesStatusAndAttachments() = runTest {
        val id = store.insert(msg("streaming", base, Role.ASSISTANT).copy(status = MessageStatus.STREAMING))
        val attachment = Attachment(
            type = "voice",
            payload = buildJsonObject {
                put("uri", JsonPrimitive("file:///a.mp3"))
                put("preview", JsonPrimitive("[语音]"))
            },
        )
        val original = store.byId(id)!!
        val updated = original.copy(
            rawText = "done",
            displayText = "done!",
            status = MessageStatus.FAILED,
            errorKind = LlmErrorKind.TIMEOUT,
            attachments = listOf(attachment),
        )
        store.update(updated)
        assertEquals(updated, store.byId(id))
    }

    @Test
    fun deleteRemovesMessage() = runTest {
        val id = store.insert(msg("x", base))
        store.delete(id)
        assertNull(store.byId(id))
        assertEquals(0, store.recent(10).size)
    }

    @Test
    fun lastMessageAtFiltersByRole() = runTest {
        assertNull(store.lastMessageAt())
        store.insert(msg("u", base, Role.USER))
        store.insert(msg("a", base.plusSeconds(10), Role.ASSISTANT))
        assertEquals(base, store.lastMessageAt(Role.USER))
        assertEquals(base.plusSeconds(10), store.lastMessageAt(Role.ASSISTANT))
        assertEquals(base.plusSeconds(10), store.lastMessageAt())
    }
}
