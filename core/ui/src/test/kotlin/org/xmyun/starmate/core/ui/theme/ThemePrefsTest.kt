package org.xmyun.starmate.core.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

@RunWith(RobolectricTestRunner::class)
class ThemePrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: ThemePrefs

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmp.root, "theme_test.preferences_pb") },
        )
        prefs = ThemePrefs(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsToPink() = runTest {
        assertEquals(ThemeAccent.PINK, prefs.accent.first())
    }

    @Test
    fun setAccent_isEmitted() = runTest {
        prefs.accent.test {
            assertEquals(ThemeAccent.PINK, awaitItem())
            prefs.setAccent(ThemeAccent.BLUE)
            assertEquals(ThemeAccent.BLUE, awaitItem())
            prefs.setAccent(ThemeAccent.ORANGE)
            assertEquals(ThemeAccent.ORANGE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun settingSameAccentTwice_emitsOnce() = runTest {
        prefs.accent.test {
            assertEquals(ThemeAccent.PINK, awaitItem())
            prefs.setAccent(ThemeAccent.PURPLE)
            assertEquals(ThemeAccent.PURPLE, awaitItem())
            prefs.setAccent(ThemeAccent.PURPLE)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unknownStoredValue_fallsBackToPink() = runTest {
        store.edit { it[ThemePrefs.KEY_ACCENT] = "NEON" }
        assertEquals(ThemePrefs.KEY_ACCENT.name, "accent")
        assertEquals(ThemeAccent.PINK, prefs.accent.first())
    }

    @Test
    fun contextConstructor_sharesStoreAcrossInstances_andUsesThemePrefsFile() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ThemePrefs(context).setAccent(ThemeAccent.GREEN)
        assertEquals(ThemeAccent.GREEN, ThemePrefs(context).accent.first())
        val file = File(context.filesDir, "datastore/theme_prefs.preferences_pb")
        assertTrue("expected ${file.path}", file.exists())
    }
}
