package org.xmyun.starmate.feature.mobileuse.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.feature.mobileuse.agent.TaskLimits
import org.xmyun.starmate.feature.mobileuse.prefs.MobileUsePrefs
import org.xmyun.starmate.feature.mobileuse.service.AccessibilityBridge
import org.xmyun.starmate.feature.mobileuse.tool.AvailabilityState
import org.xmyun.starmate.feature.mobileuse.tool.TaskCoordinator

data class LabUiState(
    val enabled: Boolean = false,
    val riskAccepted: Boolean = false,
    val sdkSupported: Boolean = Build.VERSION.SDK_INT >= AvailabilityState.MIN_SDK,
    val serviceConnected: Boolean = false,
    val allowlistSize: Int = 0,
    val limits: TaskLimits = TaskLimits(),
    val running: Boolean = false,
)

@HiltViewModel
class LabViewModel @Inject constructor(
    private val prefs: MobileUsePrefs,
    bridge: AccessibilityBridge,
    private val coordinator: TaskCoordinator,
) : ViewModel() {

    private data class Flags(val enabled: Boolean, val risk: Boolean, val allowlist: Int)

    val state: StateFlow<LabUiState> = combine(
        combine(prefs.enabled, prefs.riskAccepted, prefs.allowlist) { e, r, a -> Flags(e, r, a.size) },
        bridge.service,
        prefs.limits,
        coordinator.running,
    ) { flags, service, limits, running ->
        LabUiState(
            enabled = flags.enabled,
            riskAccepted = flags.risk,
            serviceConnected = service != null,
            allowlistSize = flags.allowlist,
            limits = limits,
            running = running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabUiState())

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setEnabled(value) }
        if (!value) coordinator.stopCurrent()
    }

    fun setLimits(limits: TaskLimits) {
        viewModelScope.launch { prefs.setLimits(limits) }
    }

    fun stopTask() = coordinator.stopCurrent()
}
