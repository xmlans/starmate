package org.xmyun.starmate.core.data.portable

import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.newHolder
import org.xmyun.starmate.core.data.store.RoomMessageStore
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose

@RunWith(RobolectricTestRunner::class)
class ChatDataPortableTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var holder: ChatDatabaseHolder
    private lateinit var store: RoomMessageStore
    private lateinit var portable: ChatDataPortable

    @Before
    fun setUp() {
        holder = newHolder(tmp.newFolder("db"))
        store = RoomMessageStore(holder)
        portable = ChatDataPortable(holder)
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private fun msg(text: String) = ChatMessage(
        role = Role.USER,
        rawText = text,
        displayText = text,
        purpose = TurnPurpose.CHAT,
        createdAt = Instant.parse("2026-11-11T10:00:00Z"),
    )

    @Test
    fun exportThenImportRestoresSnapshot() = runTest {
        store.insert(msg("kept"))
        val exportDir = tmp.newFolder("export")
        portable.exportTo(exportDir)
        assertTrue(File(exportDir, "chat.db").isFile)

        store.insert(msg("added later"))
        assertEquals(2, store.recent(10).size)

        portable.importFrom(exportDir)
        assertEquals(listOf("kept"), store.recent(10).map { it.rawText })
        // 导入后仍可写
        store.insert(msg("after import"))
        assertEquals(2, store.recent(10).size)
    }

    @Test
    fun nonSqliteFileRejectedAndDataKept() = runTest {
        store.insert(msg("original"))
        val dir = tmp.newFolder("bad")
        File(dir, "chat.db").writeText("definitely not a database")
        try {
            portable.importFrom(dir)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(listOf("original"), store.recent(10).map { it.rawText })
    }

    @Test
    fun missingFileRejected() = runTest {
        try {
            portable.importFrom(tmp.newFolder("empty"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }
}
