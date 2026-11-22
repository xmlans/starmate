package org.xmyun.starmate.core.llm.provider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
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
import org.xmyun.starmate.core.llm.FakeDiagLog
import org.xmyun.starmate.core.llm.FakeSecretStore
import org.xmyun.starmate.core.llm.TEST_KEY
import org.xmyun.starmate.core.llm.enqueueJson
import org.xmyun.starmate.core.llm.enqueueSse
import org.xmyun.starmate.core.llm.anthropic.AnthropicMaxTokensMemory
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.json
import org.xmyun.starmate.core.llm.sse
import org.xmyun.starmate.core.model.ConnectionTest
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.RoleAssignment

@RunWith(RobolectricTestRunner::class)
class DataStoreProviderStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val diag = FakeDiagLog()
    private val secrets = FakeSecretStore()
    private val httpFactory = HttpClientFactory()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreProviderStore

    @Before
    fun setUp() {
        server.start()
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "providers.preferences_pb") })
        store = DataStoreProviderStore(dataStore, secrets, DefaultLlmClientFactory(httpFactory, diag, AnthropicMaxTokensMemory()), httpFactory, diag)
    }

    @After
    fun tearDown() {
        scope.cancel()
        httpFactory.client.close()
        server.close()
    }

    private fun openAi(id: String = "openai") =
        ProviderConfig(id, "OpenAI", ProviderKind.OPENAI_COMPAT, server.url("/v1").toString())

    @Test
    fun savedProviderAndAssignmentReadBack() = runTest {
        val config = openAi()
        store.saveProvider(config, "sk-one")
        val assignment = RoleAssignment(ModelRole.TTS, "openai", "tts-1", extras = mapOf("voice" to "nova"))
        store.assign(assignment)

        assertEquals(listOf(config), store.providers.first())
        assertEquals(assignment, store.assignments.first()[ModelRole.TTS])
        assertEquals("sk-one", secrets.values["provider_key_openai"])
    }

    @Test
    fun nullKeyKeepsExistingKey() = runTest {
        store.saveProvider(openAi(), "sk-keep")
        store.saveProvider(openAi().copy(displayName = "改名了"), null)

        assertEquals("sk-keep", secrets.values["provider_key_openai"])
        val providers = store.providers.first()
        assertEquals(1, providers.size)
        assertEquals("改名了", providers[0].displayName)
    }

    @Test
    fun deleteProviderRemovesKeyAndItsAssignments() = runTest {
        store.saveProvider(openAi("a"), "sk-a")
        store.saveProvider(openAi("b"), "sk-b")
        store.assign(RoleAssignment(ModelRole.CHAT, "a", "m1"))
        store.assign(RoleAssignment(ModelRole.TTS, "a", "tts"))
        store.assign(RoleAssignment(ModelRole.BACKGROUND, "b", "m2"))

        store.deleteProvider("a")

        assertEquals(listOf("b"), store.providers.first().map { it.id })
        assertNull(secrets.values["provider_key_a"])
        assertEquals("sk-b", secrets.values["provider_key_b"])
        assertEquals(setOf(ModelRole.BACKGROUND), store.assignments.first().keys)
    }

    @Test
    fun unassignRemovesOnlyThatRole() = runTest {
        store.saveProvider(openAi(), "k")
        store.assign(RoleAssignment(ModelRole.CHAT, "openai", "m"))
        store.assign(RoleAssignment(ModelRole.VISION, "openai", "v", supportsImages = true))

        store.unassign(ModelRole.VISION)

        assertEquals(setOf(ModelRole.CHAT), store.assignments.first().keys)
    }

    @Test
    fun listModelsOpenAiCompat() = runTest {
        store.saveProvider(openAi(), TEST_KEY)
        server.enqueueJson(200, """{"object":"list","data":[{"id":"gpt-b"},{"id":"gpt-a"}]}""")

        assertEquals(listOf("gpt-a", "gpt-b"), store.listModels("openai"))
        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
    }

    @Test
    fun listModelsAnthropic() = runTest {
        store.saveProvider(ProviderConfig("anthropic", "Anthropic", ProviderKind.ANTHROPIC, server.url("/").toString()), TEST_KEY)
        server.enqueueJson(200, """{"data":[{"id":"claude-opus-5-5","type":"model"}],"has_more":false}""")

        assertEquals(listOf("claude-opus-5-5"), store.listModels("anthropic"))
        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals(TEST_KEY, request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
    }

    @Test
    fun listModelsFailureReturnsEmpty() = runTest {
        store.saveProvider(openAi(), TEST_KEY)
        server.enqueueJson(500, "boom")
        assertEquals(emptyList<String>(), store.listModels("openai"))

        server.enqueueJson(200, "not json")
        assertEquals(emptyList<String>(), store.listModels("openai"))

        assertEquals(emptyList<String>(), store.listModels("missing"))
    }

    @Test
    fun connectionTestOk() = runTest {
        store.saveProvider(openAi(), TEST_KEY)
        server.enqueueSse(sse("""{"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""", "[DONE]"))

        assertEquals(ConnectionTest.Ok, store.test("openai", "gpt-test"))
        val body = server.takeRequest().json()
        assertEquals(8, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("hi", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun connectionTestFailureIsClassified() = runTest {
        store.saveProvider(openAi(), TEST_KEY)
        server.enqueueJson(401, """{"error":{"message":"Incorrect API key provided: $TEST_KEY"}}""")

        val result = store.test("openai", "gpt-test")

        assertTrue(result is ConnectionTest.Failed)
        result as ConnectionTest.Failed
        assertEquals(LlmErrorKind.INVALID_KEY, result.kind)
        assertTrue(!result.message.contains(TEST_KEY))
    }

    @Test
    fun connectionTestUnknownProvider() = runTest {
        val result = store.test("nope", "m")
        assertEquals(LlmErrorKind.NOT_CONFIGURED, (result as ConnectionTest.Failed).kind)
    }

    @Test
    fun presetsMatchPlan() {
        val byId = store.presets.associateBy { it.id }
        assertEquals(setOf("openai", "anthropic", "deepseek", "siliconflow", "openrouter", "gemini"), byId.keys)
        assertEquals(ProviderKind.ANTHROPIC, byId["anthropic"]!!.kind)
        assertEquals("https://api.anthropic.com", byId["anthropic"]!!.baseUrl)
        assertEquals("硅基流动", byId["siliconflow"]!!.displayName)
        assertTrue(store.presets.filter { it.id != "anthropic" }.all { it.kind == ProviderKind.OPENAI_COMPAT })
    }
}
