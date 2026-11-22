package org.xmyun.starmate.feature.tools.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.GroupStatus
import org.xmyun.starmate.feature.tools.common.ManualCity
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.ToolsPrefs
import org.xmyun.starmate.feature.tools.health.HealthAvailability
import org.xmyun.starmate.feature.tools.health.HealthGateway
import org.xmyun.starmate.feature.tools.health.StepBaselineScheduler
import org.xmyun.starmate.feature.tools.location.GeocodeResult
import org.xmyun.starmate.feature.tools.location.WeatherClient

@HiltViewModel
class ToolsSettingsViewModel @Inject constructor(
    prefs: ToolsPrefs,
    private val gate: GroupGate,
) : ViewModel() {
    private val refreshTick = MutableStateFlow(0)

    val statuses: StateFlow<Map<ToolGroup, GroupStatus>> =
        combine(prefs.allSettings, refreshTick) { _, _ -> ToolGroup.entries.associateWith { gate.status(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 从系统设置回来时权限可能变了。 */
    fun refresh() {
        refreshTick.value++
    }
}

enum class CityMessage { SAVED, NOT_FOUND, FAILED }

data class GroupDetailState(
    val enabled: Boolean = false,
    val ambient: Boolean = false,
    val granted: Boolean = false,
    val health: HealthAvailability? = null,
    val manualCity: String? = null,
    val cityBusy: Boolean = false,
    val cityMessage: CityMessage? = null,
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val prefs: ToolsPrefs,
    private val gate: GroupGate,
    private val health: HealthGateway,
    private val weather: WeatherClient,
    private val scheduler: StepBaselineScheduler,
    private val text: ToolText,
) : ViewModel() {

    val group: ToolGroup = ToolGroup.fromKey(savedState.get<String>(ARG_GROUP)) ?: ToolGroup.CALENDAR

    private val granted = MutableStateFlow(false)
    private val healthAvailability = MutableStateFlow<HealthAvailability?>(null)
    private val cityUi = MutableStateFlow(false to (null as CityMessage?))

    val state: StateFlow<GroupDetailState> = combine(
        prefs.settings(group), granted, healthAvailability, prefs.manualCity, cityUi,
    ) { settings, isGranted, hc, city, (busy, message) ->
        GroupDetailState(settings.enabled, settings.ambient, isGranted, hc, city?.name, busy, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (group == ToolGroup.HEALTH) healthAvailability.value = health.availability()
            granted.value = gate.granted(group)
            syncStepWorker()
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setEnabled(group, enabled)
            syncStepWorker()
        }
    }

    fun setAmbient(ambient: Boolean) {
        viewModelScope.launch { prefs.setAmbient(group, ambient) }
    }

    fun saveCity(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            cityUi.value = true to null
            val message = when (val r = weather.geocode(trimmed, text.chinese)) {
                is GeocodeResult.Ok -> {
                    prefs.setManualCity(ManualCity(r.place.name, r.place.latitude, r.place.longitude))
                    CityMessage.SAVED
                }
                GeocodeResult.NotFound -> CityMessage.NOT_FOUND
                is GeocodeResult.Failed -> CityMessage.FAILED
            }
            cityUi.value = false to message
            granted.value = gate.granted(group)
        }
    }

    fun clearCity() {
        viewModelScope.launch {
            prefs.setManualCity(null)
            cityUi.value = false to null
            granted.value = gate.granted(group)
        }
    }

    private suspend fun syncStepWorker() {
        if (group != ToolGroup.HEALTH) return
        if (gate.usable(ToolGroup.HEALTH)) scheduler.schedule() else scheduler.cancel()
    }

    companion object {
        const val ARG_GROUP = "group"
    }
}
