package org.xmyun.starmate.feature.tools

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.db.ToolsDatabase
import org.xmyun.starmate.feature.tools.music.MusicAmbient
import org.xmyun.starmate.feature.tools.music.MusicTools
import org.xmyun.starmate.feature.tools.music.NowPlaying
import org.xmyun.starmate.feature.tools.music.NowPlayingRepository

@RunWith(RobolectricTestRunner::class)
class NowPlayingRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ToolsDatabase
    private lateinit var scope: CoroutineScope
    private val clock = clockAt("2026-11-10T21:00")
    private lateinit var repo: NowPlayingRepository

    @Before
    fun setUp() {
        db = newDatabase()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repo = NowPlayingRepository(db.trackDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    @Test
    fun consecutiveSameTrackRecordedOnce() = runTest {
        val song = NowPlaying("晴天", "周杰伦", "QQ音乐", playing = true)
        repo.update(song)
        repo.update(song)
        repo.update(song.copy(playing = false))
        repo.update(song)
        assertEquals(1, db.trackDao().count())
        repo.update(NowPlaying("七里香", "周杰伦", "QQ音乐", playing = true))
        repo.update(song)
        assertEquals(listOf("晴天", "七里香", "晴天"), repo.recent(10).map { it.title })
    }

    @Test
    fun pausedTrackIsNotRecorded() = runTest {
        repo.update(NowPlaying("晴天", "周杰伦", null, playing = false))
        assertEquals(0, db.trackDao().count())
    }

    @Test
    fun keepsOnlyLatest50() = runTest {
        repeat(55) { i ->
            clock.now = clock.now.plus(Duration.ofMinutes(4))
            repo.update(NowPlaying("歌$i", "歌手", "应用", playing = true))
        }
        assertEquals(50, db.trackDao().count())
        val recent = repo.recent(50)
        assertEquals("歌54", recent.first().title)
        assertEquals("歌5", recent.last().title)
    }

    @Test
    fun toolsAndAmbient() = runTest {
        val permissions = FakePermissions(ToolGroup.MUSIC)
        val prefs = newPrefs(scope, tmp.root)
        val gate = GroupGate(prefs, permissions)
        val tools = MusicTools(gate, repo, FakeToolText(), clock)
        val ambient = MusicAmbient(gate, repo, FakeToolText())
        prefs.enableWithAmbient(ToolGroup.MUSIC)
        repo.setListenerConnected(true)

        assertEquals("用户现在没有在播放音乐", tools.nowPlaying.execute(args(), CHAT).content)
        assertNull(ambient.snapshot())

        repo.update(NowPlaying("晴天", "周杰伦", "QQ音乐", playing = true))
        assertEquals("正在播放：《晴天》— 周杰伦（QQ音乐）", tools.nowPlaying.execute(args(), CHAT).content)
        assertEquals("正在听《晴天》— 周杰伦", ambient.snapshot())

        repo.update(NowPlaying("晴天", "周杰伦", "QQ音乐", playing = false))
        assertNull(ambient.snapshot())

        val recent = tools.recent.execute(args("count" to 99), CHAT)
        assertEquals("- 11月10日 周二 21:00 《晴天》— 周杰伦（QQ音乐）", recent.content)
    }
}
