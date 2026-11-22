package org.xmyun.starmate.core.data.store

import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.newHolder
import org.xmyun.starmate.core.model.DEFAULT_COMPANION_ID

@RunWith(RobolectricTestRunner::class)
class RoomCompanionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var holder: ChatDatabaseHolder
    private lateinit var store: RoomCompanionStore
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        holder = newHolder(tmp.root)
        store = RoomCompanionStore(holder)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        holder.close()
    }

    @Test
    fun currentInsertsChineseDefaultWhenEmpty() = runTest {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val profile = store.current()
        assertEquals(DEFAULT_COMPANION_ID, profile.id)
        assertEquals("星伴", profile.name)
        assertEquals(DefaultCompanion.profile(Locale.SIMPLIFIED_CHINESE).persona, profile.persona)
        assertEquals(profile, holder.dao().companion(DEFAULT_COMPANION_ID)!!.let { store.current() })
    }

    @Test
    fun englishLocaleGetsEnglishPersona() = runTest {
        Locale.setDefault(Locale.US)
        val profile = store.profile.first()
        assertEquals("星伴", profile.name)
        assertTrue(profile.persona.startsWith("You are"))
    }

    @Test
    fun updatePersistsAndKeepsId() = runTest {
        store.update { it.copy(id = 42, name = "小星", userNickname = "阿雨", relationshipNotes = "喜欢慢慢聊") }
        val profile = store.current()
        assertEquals(DEFAULT_COMPANION_ID, profile.id)
        assertEquals("小星", profile.name)
        assertEquals("阿雨", profile.userNickname)
        assertEquals("喜欢慢慢聊", profile.relationshipNotes)
        assertEquals(profile, store.profile.first())
    }
}
