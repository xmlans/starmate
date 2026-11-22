package org.xmyun.starmate.feature.chat.settings

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.EndpointUrls
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.ConnectionTest
import org.xmyun.starmate.core.model.EndpointExtras
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.ProviderPreset
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.ChatRoutes

/**
 * 服务商表单。apiKey 是用户刚输入、还没保存的 key，保存后立即清空，不在内存里多留；
 * 为空表示保留已存的 key。这个类的 toString 不带 key。
 */
data class ProviderForm(
    /** 已保存过的服务商 id；新建且还没保存时为 null。 */
    val providerId: String? = null,
    /** 选中的预设；null 表示自定义。 */
    val presetId: String? = null,
    val displayName: String = "",
    val kind: ProviderKind = ProviderKind.OPENAI_COMPAT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    /** 只在引导里用：主聊天模型名。 */
    val model: String = "",
) {
    override fun toString(): String =
        "ProviderForm(providerId=$providerId, presetId=$presetId, baseUrl=$baseUrl, apiKey=***, model=$model)"
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Failed(val kind: LlmErrorKind) : TestState
    data class Invalid(@StringRes val message: Int) : TestState
}

/** 给某个模型角色指定服务商和模型。 */
data class RoleForm(
    val role: ModelRole,
    val providerId: String? = null,
    val model: String = "",
    val supportsImages: Boolean = false,
    val voice: String = "",
    val format: String = "",
    val instructions: String = "",
    val test: TestState = TestState.Idle,
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
) {
    val testable: Boolean get() = role in CHAT_ROLES

    companion object {
        val CHAT_ROLES = setOf(ModelRole.CHAT, ModelRole.BACKGROUND, ModelRole.VISION)
    }
}

/**
 * 模型配置：服务商的增删改、各角色的分配、历史上限，以及引导第 3 步“配主聊天模型”。
 * 同一个类服务三个页面：模型配置总览、服务商编辑页（路由参数 providerId）、引导的模型页。
 */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val store: ProviderStore,
    private val prefs: CompanionPrefs,
    savedState: SavedStateHandle,
) : ViewModel() {

    val presets: List<ProviderPreset> = store.presets

    val providers: StateFlow<List<ProviderConfig>> =
        store.providers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val assignments: StateFlow<Map<ModelRole, RoleAssignment>> =
        store.assignments.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val historyBudget: StateFlow<Int> =
        prefs.historyTokenBudget.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_HISTORY_BUDGET)

    private val _form = MutableStateFlow(formFor(presets.firstOrNull()))
    val form: StateFlow<ProviderForm> = _form.asStateFlow()

    private val _formTest = MutableStateFlow<TestState>(TestState.Idle)
    val formTest: StateFlow<TestState> = _formTest.asStateFlow()

    private val _formModels = MutableStateFlow<List<String>>(emptyList())
    val formModels: StateFlow<List<String>> = _formModels.asStateFlow()

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()

    /** 引导里“继续”是否可用：测试通过且之后没改过表单。 */
    val canContinue: StateFlow<Boolean> = _formTest.map { it == TestState.Ok }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _role = MutableStateFlow<RoleForm?>(null)
    val roleForm: StateFlow<RoleForm?> = _role.asStateFlow()

    private val providerArg: String? = savedState.get<String>(ChatRoutes.PROVIDER_ARG)

    /** 服务商编辑页：路由参数指向一个已有的服务商。 */
    val editingExisting: Boolean = providerArg != null && providerArg != ChatRoutes.NEW_PROVIDER

    init {
        val id = providerArg
        if (id != null && editingExisting) {
            viewModelScope.launch {
                val existing = store.providers.first().firstOrNull { it.id == id } ?: return@launch
                _form.value = ProviderForm(
                    providerId = existing.id,
                    presetId = presets.firstOrNull { it.id == existing.id || it.baseUrl == existing.baseUrl }?.id,
                    displayName = existing.displayName,
                    kind = existing.kind,
                    baseUrl = existing.baseUrl,
                    hasStoredKey = true,
                )
            }
        }
    }

    // ---- 服务商表单 ----

    fun selectPreset(presetId: String?) {
        val preset = presets.firstOrNull { it.id == presetId }
        _form.update { current ->
            current.copy(
                presetId = preset?.id,
                displayName = preset?.displayName ?: "",
                kind = preset?.kind ?: ProviderKind.OPENAI_COMPAT,
                baseUrl = preset?.baseUrl ?: "",
            )
        }
        _formModels.value = emptyList()
        _formTest.value = TestState.Idle
    }

    fun updateForm(transform: (ProviderForm) -> ProviderForm) {
        _form.update(transform)
        _formTest.value = TestState.Idle
    }

    /** 先保存服务商（含 key），再发一个最小请求测试。 */
    fun testConnection() {
        val form = _form.value
        validate(form, needModel = true)?.let {
            _formTest.value = TestState.Invalid(it)
            return
        }
        _formTest.value = TestState.Running
        viewModelScope.launch {
            _formTest.value = try {
                val id = persistProvider(form)
                when (val result = store.test(id, form.model.trim())) {
                    ConnectionTest.Ok -> TestState.Ok
                    is ConnectionTest.Failed -> TestState.Failed(result.kind)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(LlmErrorKind.UNKNOWN)
            }
        }
    }

    /** 从服务商拉模型列表。需要先保存服务商才能带上 key。 */
    fun loadFormModels() {
        val form = _form.value
        validate(form, needModel = false)?.let {
            _formTest.value = TestState.Invalid(it)
            return
        }
        _loadingModels.value = true
        viewModelScope.launch {
            try {
                val id = persistProvider(form)
                _formModels.value = store.listModels(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _formModels.value = emptyList()
            } finally {
                _loadingModels.value = false
            }
        }
    }

    /** 引导：测试通过后把这个服务商和模型指定为主聊天模型。 */
    suspend fun completeSetup(): Boolean {
        if (_formTest.value != TestState.Ok) return false
        val form = _form.value
        val id = form.providerId ?: return false
        val existing = assignments.value[ModelRole.CHAT]
        store.assign(
            RoleAssignment(
                role = ModelRole.CHAT,
                providerId = id,
                model = form.model.trim(),
                supportsImages = existing?.supportsImages ?: false,
                extras = existing?.extras ?: emptyMap(),
            ),
        )
        return true
    }

    /** 服务商编辑页的“保存”。 */
    fun saveProvider(onSaved: () -> Unit) {
        val form = _form.value
        validate(form, needModel = false)?.let {
            _formTest.value = TestState.Invalid(it)
            return
        }
        viewModelScope.launch {
            persistProvider(form)
            onSaved()
        }
    }

    fun deleteProvider(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            store.deleteProvider(id)
            onDeleted()
        }
    }

    private suspend fun persistProvider(form: ProviderForm): String {
        val id = form.providerId ?: newProviderId(form.presetId)
        val name = form.displayName.trim().ifEmpty { presets.firstOrNull { it.id == form.presetId }?.displayName ?: form.baseUrl.trim() }
        store.saveProvider(
            ProviderConfig(id = id, displayName = name, kind = form.kind, baseUrl = form.baseUrl.trim()),
            apiKey = form.apiKey.trim().ifEmpty { null },
        )
        // key 存好就从内存里清掉
        _form.update { it.copy(providerId = id, displayName = name, apiKey = "", hasStoredKey = true) }
        return id
    }

    private fun newProviderId(presetId: String?): String {
        val base = presetId ?: "custom"
        val taken = providers.value.map { it.id }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    @StringRes
    private fun validate(form: ProviderForm, needModel: Boolean): Int? = when {
        !EndpointUrls.isAcceptable(form.baseUrl) -> R.string.chat_models_error_url
        form.apiKey.isBlank() && !form.hasStoredKey -> R.string.chat_models_error_key
        needModel && form.model.isBlank() -> R.string.chat_models_error_model
        else -> null
    }

    private fun formFor(preset: ProviderPreset?): ProviderForm = ProviderForm(
        presetId = preset?.id,
        displayName = preset?.displayName ?: "",
        kind = preset?.kind ?: ProviderKind.OPENAI_COMPAT,
        baseUrl = preset?.baseUrl ?: "",
    )

    // ---- 历史上限 ----

    fun setHistoryBudget(tokens: Int) {
        viewModelScope.launch { prefs.setHistoryTokenBudget(tokens.coerceIn(MIN_HISTORY_BUDGET, MAX_HISTORY_BUDGET)) }
    }

    // ---- 角色分配 ----

    fun openRole(role: ModelRole) {
        val current = assignments.value[role]
        _role.value = RoleForm(
            role = role,
            providerId = current?.providerId ?: providers.value.firstOrNull()?.id,
            model = current?.model.orEmpty(),
            supportsImages = current?.supportsImages ?: (role == ModelRole.VISION),
            voice = current?.extras?.get(EndpointExtras.VOICE).orEmpty(),
            format = current?.extras?.get(EndpointExtras.FORMAT).orEmpty(),
            instructions = current?.extras?.get(EndpointExtras.INSTRUCTIONS).orEmpty(),
        )
    }

    fun closeRole() {
        _role.value = null
    }

    fun updateRole(transform: (RoleForm) -> RoleForm) {
        _role.update { it?.let(transform)?.copy(test = TestState.Idle) }
    }

    fun loadRoleModels() {
        val role = _role.value ?: return
        val providerId = role.providerId ?: return
        _role.update { it?.copy(loadingModels = true) }
        viewModelScope.launch {
            val models = try {
                store.listModels(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _role.update { it?.copy(models = models, loadingModels = false) }
        }
    }

    fun testRole() {
        val role = _role.value ?: return
        val providerId = role.providerId
        if (providerId == null || role.model.isBlank()) {
            _role.update { it?.copy(test = TestState.Invalid(R.string.chat_models_error_model)) }
            return
        }
        _role.update { it?.copy(test = TestState.Running) }
        viewModelScope.launch {
            val result = when (val test = store.test(providerId, role.model.trim())) {
                ConnectionTest.Ok -> TestState.Ok
                is ConnectionTest.Failed -> TestState.Failed(test.kind)
            }
            _role.update { if (it?.role == role.role) it.copy(test = result) else it }
        }
    }

    fun saveRole() {
        val form = _role.value ?: return
        val providerId = form.providerId
        if (providerId == null || form.model.isBlank()) {
            _role.update { it?.copy(test = TestState.Invalid(R.string.chat_models_error_model)) }
            return
        }
        viewModelScope.launch {
            store.assign(buildAssignment(form, providerId, assignments.value[form.role]))
            _role.value = null
        }
    }

    fun clearRole(role: ModelRole) {
        viewModelScope.launch {
            store.unassign(role)
            if (_role.value?.role == role) _role.value = null
        }
    }

    companion object {
        const val DEFAULT_HISTORY_BUDGET = 8000
        const val MIN_HISTORY_BUDGET = 2000
        const val MAX_HISTORY_BUDGET = 64000

        fun buildAssignment(form: RoleForm, providerId: String, existing: RoleAssignment?): RoleAssignment {
            val model = form.model.trim()
            val extras = if (form.role == ModelRole.TTS) {
                buildMap {
                    form.voice.trim().takeIf { it.isNotEmpty() }?.let { put(EndpointExtras.VOICE, it) }
                    form.format.trim().takeIf { it.isNotEmpty() }?.let { put(EndpointExtras.FORMAT, it) }
                    form.instructions.trim().takeIf { it.isNotEmpty() }?.let { put(EndpointExtras.INSTRUCTIONS, it) }
                }
            } else {
                existing?.extras ?: emptyMap()
            }
            // 换了服务商或模型就重新探测是否支持工具
            val sameModel = existing != null && existing.providerId == providerId && existing.model == model
            return RoleAssignment(
                role = form.role,
                providerId = providerId,
                model = model,
                supportsImages = if (form.role == ModelRole.VISION || form.role == ModelRole.CHAT) form.supportsImages else false,
                supportsTools = if (sameModel) existing.supportsTools else true,
                extras = extras,
            )
        }
    }
}
