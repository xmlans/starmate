package org.xmyun.starmate.core.llm.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xmyun.starmate.core.llm.FakeDiagLog
import org.xmyun.starmate.core.llm.FakeSecretStore
import org.xmyun.starmate.core.llm.anthropic.AnthropicClient
import org.xmyun.starmate.core.llm.anthropic.AnthropicMaxTokensMemory
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.openai.OpenAiCompatClient
import org.xmyun.starmate.core.model.ConnectionTest
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.ProviderPreset
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.core.model.RoleAssignment

/** 内存版 ProviderStore，只为路由测试。 */
private class FakeProviderStore : ProviderStore {
    override val presets: List<ProviderPreset> = Presets.all
    val providerState = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val assignmentState = MutableStateFlow<Map<ModelRole, RoleAssignment>>(emptyMap())
    override val providers: StateFlow<List<ProviderConfig>> = providerState
    override val assignments: StateFlow<Map<ModelRole, RoleAssignment>> = assignmentState

    override suspend fun saveProvider(config: ProviderConfig, apiKey: String?) {
        providerState.value = providerState.value.filterNot { it.id == config.id } + config
    }

    override suspend fun deleteProvider(id: String) {
        providerState.value = providerState.value.filterNot { it.id == id }
    }

    override suspend fun assign(assignment: RoleAssignment) {
        assignmentState.value = assignmentState.value + (assignment.role to assignment)
    }

    override suspend fun unassign(role: ModelRole) {
        assignmentState.value = assignmentState.value - role
    }

    override suspend fun listModels(providerId: String): List<String> = emptyList()
    override suspend fun test(providerId: String, model: String): ConnectionTest = ConnectionTest.Ok
}

class DefaultModelRouterTest {

    private val store = FakeProviderStore()
    private val secrets = FakeSecretStore()
    private val httpFactory = HttpClientFactory()
    private val router = DefaultModelRouter(store, secrets, DefaultLlmClientFactory(httpFactory, FakeDiagLog(), AnthropicMaxTokensMemory()))

    private val openAi = ProviderConfig("openai", "OpenAI", ProviderKind.OPENAI_COMPAT, "https://api.openai.com/v1")
    private val anthropic = ProviderConfig("anthropic", "Anthropic", ProviderKind.ANTHROPIC, "https://api.anthropic.com")

    @After
    fun tearDown() {
        httpFactory.client.close()
    }

    private suspend fun configureChatOnly() {
        store.saveProvider(openAi, null)
        secrets.set(ProviderKeys.secretName("openai"), "sk-x")
        store.assign(RoleAssignment(ModelRole.CHAT, "openai", "gpt-chat"))
    }

    @Test
    fun backgroundAndVisionFallBackToChat() = runTest {
        configureChatOnly()

        val chat = router.chat(ModelRole.CHAT)!!
        assertEquals("gpt-chat", router.chat(ModelRole.BACKGROUND)!!.modelId)
        assertEquals(chat.modelId, router.chat(ModelRole.VISION)!!.modelId)
        assertTrue(chat is OpenAiCompatClient)
    }

    @Test
    fun ownAssignmentWinsOverFallback() = runTest {
        configureChatOnly()
        store.saveProvider(anthropic, null)
        store.assign(RoleAssignment(ModelRole.BACKGROUND, "anthropic", "claude-haiku-5-5"))

        val background = router.chat(ModelRole.BACKGROUND)!!
        assertEquals("claude-haiku-5-5", background.modelId)
        assertTrue(background is AnthropicClient)
    }

    @Test
    fun nothingConfiguredReturnsNull() = runTest {
        assertNull(router.chat(ModelRole.CHAT))
        assertNull(router.chat(ModelRole.BACKGROUND))
        assertNull(router.embedding())
        assertNull(router.endpoint(ModelRole.TTS))
    }

    @Test
    fun assignmentPointingAtMissingProviderReturnsNull() = runTest {
        store.assign(RoleAssignment(ModelRole.CHAT, "gone", "m"))
        assertNull(router.chat(ModelRole.CHAT))
    }

    @Test
    fun markToolsUnsupportedPersistsOnEffectiveAssignment() = runTest {
        configureChatOnly()
        assertTrue(router.chat(ModelRole.BACKGROUND)!!.supportsTools)

        router.markToolsUnsupported(ModelRole.BACKGROUND)

        assertFalse(router.chat(ModelRole.BACKGROUND)!!.supportsTools)
        assertFalse(router.chat(ModelRole.CHAT)!!.supportsTools)
        assertFalse(store.assignmentState.value[ModelRole.CHAT]!!.supportsTools)
        assertNull(store.assignmentState.value[ModelRole.BACKGROUND])
    }

    @Test
    fun capabilityFlagsComeFromAssignment() = runTest {
        store.saveProvider(openAi, null)
        store.assign(RoleAssignment(ModelRole.CHAT, "openai", "m", supportsImages = true, supportsTools = false))

        val client = router.chat(ModelRole.CHAT)!!
        assertTrue(client.supportsImages)
        assertFalse(client.supportsTools)
    }

    @Test
    fun chatRejectsOtherRoles() = runTest {
        for (role in listOf(ModelRole.EMBEDDING, ModelRole.TTS, ModelRole.STT)) {
            try {
                router.chat(role)
                fail("$role 应当被拒绝")
            } catch (expected: IllegalArgumentException) {
                // 预期如此
            }
        }
    }

    @Test
    fun endpointForTtsAndRejectsChat() = runTest {
        store.saveProvider(openAi, null)
        secrets.set(ProviderKeys.secretName("openai"), "sk-tts")
        store.assign(RoleAssignment(ModelRole.TTS, "openai", "tts-1", extras = mapOf("voice" to "nova")))

        assertEquals(
            EndpointConfig("https://api.openai.com/v1", "sk-tts", "tts-1", mapOf("voice" to "nova")),
            router.endpoint(ModelRole.TTS),
        )
        assertNull(router.endpoint(ModelRole.STT))
        try {
            router.endpoint(ModelRole.CHAT)
            fail("CHAT 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            // 预期如此
        }
    }

    @Test
    fun embeddingOnlyForOpenAiCompat() = runTest {
        store.saveProvider(openAi, null)
        store.saveProvider(anthropic, null)
        store.assign(RoleAssignment(ModelRole.EMBEDDING, "openai", "text-embedding-3-small"))
        assertNotNull(router.embedding())
        assertEquals("text-embedding-3-small", router.embedding()!!.modelId)

        store.assign(RoleAssignment(ModelRole.EMBEDDING, "anthropic", "x"))
        assertNull(router.embedding())
    }
}
