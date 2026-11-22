package org.xmyun.starmate.feature.chat.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.CompanionProfile
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.ui.component.Avatar
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.AvatarStorage

data class CompanionDraft(
    val name: String = "",
    val avatarUri: String? = null,
    val persona: String = "",
    val userNickname: String = "",
    val relationshipNotes: String = "",
)

@HiltViewModel
class CompanionEditViewModel @Inject constructor(
    private val store: CompanionStore,
    private val avatars: AvatarStorage,
) : ViewModel() {

    private val _draft = MutableStateFlow<CompanionDraft?>(null)
    val draft: StateFlow<CompanionDraft?> = _draft.asStateFlow()

    private var originalAvatar: String? = null

    init {
        viewModelScope.launch {
            val p = store.current()
            originalAvatar = p.avatarUri
            _draft.value = CompanionDraft(p.name, p.avatarUri, p.persona, p.userNickname, p.relationshipNotes)
        }
    }

    fun update(transform: (CompanionDraft) -> CompanionDraft) {
        _draft.update { it?.let(transform) }
    }

    fun pickAvatar(uri: Uri) {
        viewModelScope.launch {
            // 换掉的是这次编辑里新选的图时才删；原头像等保存后再删
            val previous = _draft.value?.avatarUri?.takeIf { it != originalAvatar }
            avatars.import(uri, previous)?.let { path -> update { it.copy(avatarUri = path) } }
        }
    }

    fun save(onSaved: () -> Unit) {
        val draft = _draft.value ?: return
        viewModelScope.launch {
            store.update { current: CompanionProfile ->
                current.copy(
                    name = draft.name.trim().ifEmpty { current.name },
                    avatarUri = draft.avatarUri,
                    persona = draft.persona.trim(),
                    userNickname = draft.userNickname.trim(),
                    relationshipNotes = draft.relationshipNotes.trim(),
                )
            }
            if (draft.avatarUri != originalAvatar) avatars.discard(originalAvatar)
            onSaved()
        }
    }
}

/** 角色编辑：名字、头像、人设（带 3 个模板）、她怎么称呼你、相处偏好。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionEditScreen(viewModel: CompanionEditViewModel, onBack: () -> Unit) {
    val draft by viewModel.draft.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val templates = listOf(
        R.string.chat_persona_template_gentle_name to R.string.chat_persona_template_gentle,
        R.string.chat_persona_template_cheerful_name to R.string.chat_persona_template_cheerful,
        R.string.chat_persona_template_quiet_name to R.string.chat_persona_template_quiet,
    )
    val templateTexts = templates.map { stringResource(it.first) to stringResource(it.second) }

    SettingsScaffold(
        title = stringResource(R.string.chat_companion_title),
        onBack = onBack,
        actions = {
            TextButton(onClick = { viewModel.save(onBack) }, enabled = draft != null) {
                Text(stringResource(R.string.chat_save))
            }
        },
    ) { padding ->
        val d = draft ?: return@SettingsScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(
                    uri = d.avatarUri,
                    name = d.name,
                    size = 88.dp,
                    onClick = pick,
                    contentDescription = stringResource(R.string.chat_companion_avatar),
                )
                TextButton(onClick = pick) { Text(stringResource(R.string.chat_companion_pick_avatar)) }
            }
            OutlinedTextField(
                value = d.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.chat_companion_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = d.userNickname,
                onValueChange = { v -> viewModel.update { it.copy(userNickname = v) } },
                label = { Text(stringResource(R.string.chat_companion_nickname)) },
                supportingText = { Text(stringResource(R.string.chat_companion_nickname_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.chat_companion_persona), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templateTexts.forEach { (label, text) ->
                    AssistChip(
                        onClick = { viewModel.update { it.copy(persona = text) } },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = d.persona,
                onValueChange = { v -> viewModel.update { it.copy(persona = v) } },
                label = { Text(stringResource(R.string.chat_companion_persona)) },
                supportingText = { Text(stringResource(R.string.chat_companion_persona_hint)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            OutlinedTextField(
                value = d.relationshipNotes,
                onValueChange = { v -> viewModel.update { it.copy(relationshipNotes = v) } },
                label = { Text(stringResource(R.string.chat_companion_notes)) },
                supportingText = { Text(stringResource(R.string.chat_companion_notes_hint)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            Button(onClick = { viewModel.save(onBack) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_save))
            }
        }
    }
}
