package org.xmyun.starmate.core.data.secret

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.FakeSecretCipher
import org.xmyun.starmate.core.data.newPrefsDataStore

@RunWith(RobolectricTestRunner::class)
class DataStoreSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreSecretStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(tmp.root, "secrets.preferences_pb")
        dataStore = newPrefsDataStore(scope, file)
        store = DataStoreSecretStore(dataStore, FakeSecretCipher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun writeThenReadReturnsSameValue() = runTest {
        store.set("provider_openai", "sk-test-value-123456")
        assertEquals("sk-test-value-123456", store.get("provider_openai"))
    }

    @Test
    fun missingKeyReadsNull() = runTest {
        assertNull(store.get("nothing"))
    }

    @Test
    fun writingNullDeletes() = runTest {
        store.set("a", "value")
        store.set("a", null)
        assertNull(store.get("a"))
        assertEquals(0, dataStore.data.first().asMap().size)
    }

    @Test
    fun rawValueDoesNotContainPlaintext() = runTest {
        val secret = "sk-plaintext-should-not-appear"
        store.set("openai", secret)

        dataStore.data.first().asMap().forEach { (key, value) ->
            assertFalse(key.name.contains(secret))
            assertFalse(value.toString().contains(secret))
            assertFalse(value.toString().contains("plaintext"))
        }
        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains(secret))
        assertFalse(onDisk.contains("plaintext"))
    }

    @Test
    fun tamperedCiphertextReadsNull() = runTest {
        store.set("a", "value")
        @Suppress("UNCHECKED_CAST")
        val key = dataStore.data.first().asMap().keys.single() as Preferences.Key<String>
        dataStore.edit { prefs ->
            val bytes = Base64.getDecoder().decode(prefs[key])
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
            prefs[key] = Base64.getEncoder().encodeToString(bytes)
        }
        assertNull(store.get("a"))
    }

    @Test
    fun invalidBase64ReadsNull() = runTest {
        store.set("a", "value")
        @Suppress("UNCHECKED_CAST")
        val key = dataStore.data.first().asMap().keys.single() as Preferences.Key<String>
        dataStore.edit { it[key] = "!!not base64!!" }
        assertNull(store.get("a"))
    }
}
