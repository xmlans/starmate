package org.xmyun.starmate.feature.chat.settings

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xmyun.starmate.core.model.ConnectionTest
import org.xmyun.starmate.core.model.EndpointExtras
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.feature.chat.FakeCompanionPrefs
import org.xmyun.starmate.feature.chat.FakeProviderStore
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.ChatRoutes

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeProviderStore()
    private val prefs = FakeCompanionPrefs()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(args: Map<String, Any?> = emptyMap()) = ModelsViewModel(store, prefs, SavedStateHandle(args))

    @Test
    fun continueDisabledUntilTestPasses_thenAssignmentSaved() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.canContinue.value)
        assertEquals("openai", vm.form.value.presetId)

        vm.updateForm { it.copy(apiKey = "sk-secret", model = "gpt-a") }
        store.testResult = ConnectionTest.Failed(LlmErrorKind.INVALID_KEY, "401")
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(TestState.Failed(LlmErrorKind.INVALID_KEY), vm.formTest.value)
        assertFalse(vm.canContinue.value)
        assertFalse(vm.completeSetup())
        assertTrue(store.assigned.isEmpty())

        // 测试前先保存了服务商和 key；保存后 key 从表单里清掉
        val (config, key) = store.saved.first()
        assertEquals("openai", config.id)
        assertEquals("https://api.openai.com/v1", config.baseUrl)
        assertEquals("sk-secret", key)
        assertEquals("", vm.form.value.apiKey)
        assertTrue(vm.form.value.hasStoredKey)
        assertFalse(vm.form.value.toString().contains("sk-secret"))

        store.testResult = ConnectionTest.Ok
        vm.testConnection()
        advanceUntilIdle()
        assertTrue(vm.canContinue.value)
        // 第二次测试沿用已存的 key
        assertEquals(null, store.saved.last().second)

        // 测试通过后又改了表单，要重新测
        vm.updateForm { it.copy(model = "gpt-b") }
        advanceUntilIdle()
        assertFalse(vm.canContinue.value)
        vm.testConnection()
        advanceUntilIdle()
        assertTrue(vm.canContinue.value)
        assertEquals("openai" to "gpt-b", store.tested.last())

        assertTrue(vm.completeSetup())
        assertEquals(RoleAssignment(ModelRole.CHAT, "openai", "gpt-b"), store.assigned.single())
    }

    @Test
    fun missingKeyOrModelIsRejectedWithoutCallingProvider() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.updateForm { it.copy(model = "gpt-a") }
        vm.testConnection()
        assertEquals(TestState.Invalid(R.string.chat_models_error_key), vm.formTest.value)

        vm.updateForm { it.copy(apiKey = "sk", model = "") }
        vm.testConnection()
        assertEquals(TestState.Invalid(R.string.chat_models_error_model), vm.formTest.value)

        vm.updateForm { it.copy(model = "m", baseUrl = "ftp://x") }
        vm.testConnection()
        assertEquals(TestState.Invalid(R.string.chat_models_error_url), vm.formTest.value)
        advanceUntilIdle()
        assertTrue(store.tested.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun customProviderGetsUniqueId() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectPreset(null)
        assertEquals("", vm.form.value.baseUrl)
        vm.updateForm { it.copy(displayName = "中转", baseUrl = "https://relay.example/v1", apiKey = "k", kind = ProviderKind.OPENAI_COMPAT) }
        vm.saveProvider {}
        advanceUntilIdle()
        assertEquals("custom", store.saved.last().first.id)

        val second = viewModel()
        advanceUntilIdle()
        second.selectPreset(null)
        second.updateForm { it.copy(baseUrl = "https://relay2.example/v1", apiKey = "k2") }
        second.saveProvider {}
        advanceUntilIdle()
        assertEquals("custom-2", store.saved.last().first.id)
    }

    @Test
    fun editingExistingProviderKeepsKeyWhenBlank() = runTest(dispatcher) {
        store.saveProvider(org.xmyun.starmate.core.model.ProviderConfig("deepseek", "DeepSeek", ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com/v1"), "old")
        store.saved.clear()
        val vm = viewModel(mapOf(ChatRoutes.PROVIDER_ARG to "deepseek"))
        advanceUntilIdle()
        assertTrue(vm.editingExisting)
        assertEquals("deepseek", vm.form.value.providerId)
        vm.updateForm { it.copy(baseUrl = "https://api.deepseek.com") }
        vm.saveProvider {}
        advanceUntilIdle()
        assertEquals("https://api.deepseek.com", store.saved.single().first.baseUrl)
        assertEquals(null, store.saved.single().second)
    }

    @Test
    fun ttsRoleStoresExtras_andVisionImagesFlag() = runTest(dispatcher) {
        store.saveProvider(org.xmyun.starmate.core.model.ProviderConfig("openai", "OpenAI", ProviderKind.OPENAI_COMPAT, "https://api.openai.com/v1"), "k")
        val vm = viewModel()
        advanceUntilIdle()

        vm.openRole(ModelRole.TTS)
        vm.updateRole { it.copy(model = "gpt-4o-mini-tts", voice = "nova", format = "mp3", instructions = "轻声") }
        vm.saveRole()
        advanceUntilIdle()
        val tts = store.assigned.last()
        assertEquals(ModelRole.TTS, tts.role)
        assertEquals(mapOf(EndpointExtras.VOICE to "nova", EndpointExtras.FORMAT to "mp3", EndpointExtras.INSTRUCTIONS to "轻声"), tts.extras)
        assertEquals(null, vm.roleForm.value)

        vm.openRole(ModelRole.VISION)
        assertTrue(vm.roleForm.value!!.supportsImages)
        vm.updateRole { it.copy(model = "gpt-4o") }
        vm.saveRole()
        advanceUntilIdle()
        assertTrue(store.assigned.last().supportsImages)
    }

    @Test
    fun sameModelKeepsDetectedToolSupport_newModelResetsIt() {
        val existing = RoleAssignment(ModelRole.CHAT, "openai", "gpt-a", supportsTools = false)
        val same = ModelsViewModel.buildAssignment(RoleForm(ModelRole.CHAT, "openai", "gpt-a"), "openai", existing)
        assertFalse(same.supportsTools)
        val changed = ModelsViewModel.buildAssignment(RoleForm(ModelRole.CHAT, "openai", "gpt-b"), "openai", existing)
        assertTrue(changed.supportsTools)
    }

    @Test
    fun historyBudgetIsClamped() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setHistoryBudget(100)
        advanceUntilIdle()
        assertEquals(ModelsViewModel.MIN_HISTORY_BUDGET, prefs.historyFlow.value)
        vm.setHistoryBudget(16000)
        advanceUntilIdle()
        assertEquals(16000, prefs.historyFlow.value)
    }
}
