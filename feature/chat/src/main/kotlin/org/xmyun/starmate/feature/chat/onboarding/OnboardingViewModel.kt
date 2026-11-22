package org.xmyun.starmate.feature.chat.onboarding

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.DataArchiver
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.AvatarStorage

/** 引导的欢迎、成年确认、起名三步，以及从备份恢复。 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: CompanionPrefs,
    private val companions: CompanionStore,
    private val providers: ProviderStore,
    private val archiver: DataArchiver,
    private val avatars: AvatarStorage,
    private val diag: DiagLog,
) : ViewModel() {

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    private val _restoreError = MutableStateFlow<Int?>(null)
    val restoreError: StateFlow<Int?> = _restoreError.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _avatar = MutableStateFlow<String?>(null)
    val avatar: StateFlow<String?> = _avatar.asStateFlow()

    private var loaded = false

    fun loadCompanion() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val current = companions.current()
            _name.value = current.name
            _avatar.value = current.avatarUri
        }
    }

    fun onNameChange(value: String) {
        _name.value = value
    }

    fun pickAvatar(uri: Uri) {
        viewModelScope.launch {
            avatars.import(uri, previous = null)?.let { _avatar.value = it }
        }
    }

    /** 从备份恢复。成功后按恢复出来的偏好算下一步；失败留在欢迎页并显示原因。 */
    fun restore(uri: Uri, onNext: (String?) -> Unit) {
        if (_restoring.value) return
        _restoring.value = true
        _restoreError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
                    stream.use { archiver.import(it) }
                }
                val adult = prefs.adultConfirmed.first()
                val next = if (!adult) {
                    org.xmyun.starmate.feature.chat.common.ChatRoutes.ONBOARDING_ADULT
                } else {
                    OnboardingSteps.afterAdult(hasChatModel(), prefs.onboardingState.first())
                }
                onNext(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "restore failed: ${e.javaClass.simpleName}")
                _restoreError.value = restoreErrorText(e)
            } finally {
                _restoring.value = false
            }
        }
    }

    fun confirmAdult(onNext: (String?) -> Unit) {
        viewModelScope.launch {
            prefs.setAdultConfirmed(true)
            onNext(OnboardingSteps.afterAdult(hasChatModel(), prefs.onboardingState.first()))
        }
    }

    fun afterModel(onNext: (String?) -> Unit) {
        viewModelScope.launch { onNext(OnboardingSteps.afterModel(prefs.onboardingState.first())) }
    }

    /** 起名这一步：保存（或跳过）后开始访谈。 */
    fun finishCompanion(save: Boolean, onNext: () -> Unit) {
        viewModelScope.launch {
            if (save) {
                val name = _name.value.trim()
                val avatar = _avatar.value
                companions.update { current ->
                    current.copy(name = name.ifEmpty { current.name }, avatarUri = avatar ?: current.avatarUri)
                }
            }
            if (prefs.onboardingState.first() == OnboardingState.NOT_STARTED) {
                prefs.setOnboardingState(OnboardingState.INTERVIEWING)
            }
            onNext()
        }
    }

    private suspend fun hasChatModel(): Boolean = ModelRole.CHAT in providers.assignments.first()

    @StringRes
    private fun restoreErrorText(e: Exception): Int = when (e) {
        is IllegalArgumentException -> R.string.chat_restore_error_invalid
        is IOException -> R.string.chat_restore_error_io
        else -> R.string.chat_restore_error_unknown
    }

    private companion object {
        const val TAG = "chat.onboarding"
    }
}
