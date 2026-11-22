package org.xmyun.starmate.feature.mcp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.SecretStore
import org.xmyun.starmate.feature.mcp.McpNames
import org.xmyun.starmate.feature.mcp.McpPreset
import org.xmyun.starmate.feature.mcp.McpPrefs
import org.xmyun.starmate.feature.mcp.McpRegistry
import org.xmyun.starmate.feature.mcp.ServerStatus

data class PresetUi(
    val preset: McpPreset,
    val enabled: Boolean,
    val hasKey: Boolean,
    val status: ServerStatus,
    /** 已发现的工具（模型看到的名字，已按白名单过滤）。 */
    val toolNames: List<String>,
    val busy: Boolean,
)

@HiltViewModel
class McpSettingsViewModel @Inject constructor(
    private val prefs: McpPrefs,
    private val secrets: SecretStore,
    private val registry: McpRegistry,
) : ViewModel() {

    private val keyed = MutableStateFlow(McpPreset.entries.associateWith { false })
    private val busy = MutableStateFlow(emptySet<McpPreset>())

    val presets: StateFlow<List<PresetUi>> = combine(
        prefs.enabled(McpPreset.TAVILY),
        prefs.enabled(McpPreset.AMAP),
        keyed,
        busy,
        registry.states,
    ) { tavily, amap, hasKey, working, states ->
        McpPreset.entries.map { p ->
            val s = states.getValue(p)
            PresetUi(
                preset = p,
                enabled = if (p == McpPreset.TAVILY) tavily else amap,
                hasKey = hasKey.getValue(p),
                status = s.status,
                toolNames = s.tools.map { McpNames.exposed(p, it.name) }.filter { p.allowList?.contains(it) ?: true },
                busy = p in working,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            McpPreset.entries.forEach { p ->
                val has = !secrets.get(p.secretName).isNullOrBlank()
                keyed.update { it + (p to has) }
                registry.loadCached(p)
            }
        }
    }

    fun setEnabled(preset: McpPreset, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setEnabled(preset, enabled)
            if (enabled && keyed.value.getValue(preset)) work(preset) { registry.refresh(preset) }
        }
    }

    fun saveKey(preset: McpPreset, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            work(preset) {
                secrets.set(preset.secretName, trimmed)
                keyed.update { it + (preset to true) }
                registry.onKeyChanged(preset)
            }
        }
    }

    fun clearKey(preset: McpPreset) {
        viewModelScope.launch {
            work(preset) {
                secrets.set(preset.secretName, null)
                keyed.update { it + (preset to false) }
                registry.onKeyChanged(preset)
            }
        }
    }

    fun test(preset: McpPreset) {
        viewModelScope.launch { work(preset) { registry.refresh(preset) } }
    }

    private suspend fun work(preset: McpPreset, block: suspend () -> Unit) {
        busy.update { it + preset }
        try {
            block()
        } finally {
            busy.update { it - preset }
        }
    }
}
