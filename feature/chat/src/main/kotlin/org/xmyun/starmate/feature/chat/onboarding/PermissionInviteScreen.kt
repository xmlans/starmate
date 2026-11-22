package org.xmyun.starmate.feature.chat.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.contract.PermissionInvite
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.ChatPrefs

@HiltViewModel
class PermissionInviteViewModel @Inject constructor(
    invites: Set<@JvmSuppressWildcards PermissionInvite>,
    private val chatPrefs: ChatPrefs,
) : ViewModel() {
    val invites: List<PermissionInvite> = invites.sortedWith(compareBy({ it.order }, { it.javaClass.name }))

    fun done(onDone: () -> Unit) {
        viewModelScope.launch {
            chatPrefs.setInvitesShown(true)
            onDone()
        }
    }
}

/** 第 6 步：访谈结束后的权限邀请。逐项说明她能看到什么，可以全部跳过。 */
@Composable
fun PermissionInviteScreen(viewModel: PermissionInviteViewModel, onDone: () -> Unit) {
    // 授权结果回来或从系统设置回来时刷新各行的状态
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    OnboardingPage(
        step = null,
        title = stringResource(R.string.chat_invites_title),
        description = stringResource(R.string.chat_invites_body),
        onBack = null,
        bottom = {
            Button(onClick = { viewModel.done(onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_invites_done))
            }
            TextButton(onClick = { viewModel.done(onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_invites_skip_all))
            }
        },
    ) {
        if (viewModel.invites.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.chat_invites_empty),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            viewModel.invites.forEach { invite ->
                InviteRow(invite = invite, refreshKey = refresh, onResult = { refresh++ })
            }
        }
    }
}

@Composable
private fun InviteRow(invite: PermissionInvite, refreshKey: Int, onResult: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(invite, refreshKey) {
        granted = runCatching { invite.isGranted(context) }.getOrDefault(false)
    }
    val request = invite.rememberRequester { onResult() }
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(invite.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(invite.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                stringResource(invite.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 10.dp))
            if (granted == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_invites_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                FilledTonalButton(onClick = request, enabled = granted != null) {
                    Text(stringResource(R.string.chat_invites_enable))
                }
            }
        }
    }
}
