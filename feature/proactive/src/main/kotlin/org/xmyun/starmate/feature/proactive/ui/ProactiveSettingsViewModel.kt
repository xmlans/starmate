package org.xmyun.starmate.feature.proactive.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.feature.proactive.ProactiveController
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.SendLogEntry
import org.xmyun.starmate.feature.proactive.notify.ProactiveNotifier
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.store.CareStore
import org.xmyun.starmate.feature.proactive.store.SendLogStore
import org.xmyun.starmate.feature.proactive.tools.CareService

object ProactiveRoutes {
    const val SETTINGS = "settings/proactive"
    const val BATTERY = "settings/proactive/battery"
}

data class ProactiveUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val clinginess: Clinginess = Clinginess.MEDIUM,
    val quietHours: QuietHours = QuietHours.DEFAULT,
    val keepAlive: Boolean = false,
    val cares: List<Care> = emptyList(),
    val logs: List<SendLogEntry> = emptyList(),
) {
    /** 总开关关闭或粘人程度为“关”：待发的约定处于挂起状态。 */
    val caresPaused: Boolean get() = !enabled || clinginess == Clinginess.OFF
}

private data class Settings(
    val enabled: Boolean,
    val clinginess: Clinginess,
    val quietHours: QuietHours,
    val keepAlive: Boolean,
)

@HiltViewModel
class ProactiveSettingsViewModel @Inject constructor(
    prefs: ProactivePrefs,
    private val companionPrefs: CompanionPrefs,
    careStore: CareStore,
    logStore: SendLogStore,
    private val careService: CareService,
    private val controller: ProactiveController,
    private val notifier: ProactiveNotifier,
    clock: Clock,
) : ViewModel() {

    val zone: ZoneId = clock.zone

    private val settings = combine(
        prefs.enabled,
        companionPrefs.clinginess,
        companionPrefs.quietHours,
        prefs.keepAlive,
    ) { enabled, clinginess, quiet, keepAlive -> Settings(enabled, clinginess, quiet, keepAlive) }

    val state: StateFlow<ProactiveUiState> = combine(
        settings,
        careStore.observePending(),
        logStore.observeRecent(RECENT_LOGS),
    ) { s, cares, logs ->
        ProactiveUiState(
            loaded = true,
            enabled = s.enabled,
            clinginess = s.clinginess,
            quietHours = s.quietHours,
            keepAlive = s.keepAlive,
            cares = cares,
            logs = logs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProactiveUiState())

    private val notifications = MutableStateFlow(notifier.canNotify())
    val notificationsAllowed: StateFlow<Boolean> = notifications.asStateFlow()

    fun refreshNotificationAccess() {
        notifications.value = notifier.canNotify()
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { controller.setEnabled(value) }
    }

    fun setClinginess(value: Clinginess) {
        viewModelScope.launch { controller.setClinginess(value) }
    }

    fun setQuietStart(minuteOfDay: Int) {
        viewModelScope.launch {
            val current = companionPrefs.quietHours.first()
            companionPrefs.setQuietHours(current.copy(startMinute = minuteOfDay))
        }
    }

    fun setQuietEnd(minuteOfDay: Int) {
        viewModelScope.launch {
            val current = companionPrefs.quietHours.first()
            companionPrefs.setQuietHours(current.copy(endMinute = minuteOfDay))
        }
    }

    fun setKeepAlive(value: Boolean) {
        viewModelScope.launch { controller.setKeepAlive(value) }
    }

    fun cancelCare(id: Long) {
        viewModelScope.launch { careService.cancel(id) }
    }

    companion object {
        const val RECENT_LOGS = 30
    }
}
