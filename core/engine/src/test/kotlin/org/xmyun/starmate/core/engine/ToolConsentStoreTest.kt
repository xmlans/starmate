package org.xmyun.starmate.core.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Android 版 DataStore 用 android.system.Os 换文件，需要 Robolectric
@RunWith(RobolectricTestRunner::class)
class ToolConsentStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun rememberAndForget() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = DataStoreToolConsentStore(
                PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "tool_consent.preferences_pb") },
            )
            assertFalse(store.isRemembered("alarm_set"))
            store.remember("alarm_set")
            store.remember("calendar_create_event")
            assertTrue(store.isRemembered("alarm_set"))
            store.forget("alarm_set")
            assertFalse(store.isRemembered("alarm_set"))
            assertTrue(store.isRemembered("calendar_create_event"))
        } finally {
            scope.cancel()
        }
    }
}
