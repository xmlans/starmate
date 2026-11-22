package org.xmyun.starmate.feature.diary.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatDraftBus
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.QuotePayload
import org.xmyun.starmate.feature.diary.R
import org.xmyun.starmate.feature.diary.db.DiaryDatabaseHolder
import org.xmyun.starmate.feature.diary.db.DiaryEntryEntity
import org.xmyun.starmate.feature.diary.prefs.DiaryPrefs
import org.xmyun.starmate.feature.diary.write.DiaryFrequency
import org.xmyun.starmate.feature.diary.write.DiaryRunResult
import org.xmyun.starmate.feature.diary.write.DiaryWriter

/** “回复这篇”的引用附件。previewFormat 形如“引用她的日记《%1$s》：%2$s”。 */
fun diaryQuote(entry: DiaryEntryEntity, previewFormat: String): Attachment {
    val excerpt = entry.body.replace(Regex("\\s+"), " ").trim().take(QUOTE_EXCERPT_CHARS)
    return Attachment(
        type = AttachmentTypes.QUOTE,
        payload = buildJsonObject {
            put(QuotePayload.TITLE, JsonPrimitive(entry.title))
            put(QuotePayload.TEXT, JsonPrimitive(excerpt))
            put(QuotePayload.DATE, JsonPrimitive(entry.date))
            put(ATTACHMENT_PREVIEW_KEY, JsonPrimitive(previewFormat.format(entry.title, excerpt)))
        },
    )
}

const val QUOTE_EXCERPT_CHARS = 120

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryListViewModel @Inject constructor(holder: DiaryDatabaseHolder) : ViewModel() {
    /** null 表示还在加载。 */
    val entries: StateFlow<List<DiaryEntryEntity>?> = holder.database.filterNotNull()
        .flatMapLatest { it.dao().observeAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryEntryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val holder: DiaryDatabaseHolder,
    private val draftBus: ChatDraftBus,
) : ViewModel() {
    private val id: Long = savedState.get<Long>(ARG_ID) ?: 0L

    val entry: StateFlow<DiaryEntryEntity?> = holder.database.filterNotNull()
        .flatMapLatest { it.dao().observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // 点开即标为已读
        viewModelScope.launch { holder.dao().markRead(id) }
    }

    fun reply(entry: DiaryEntryEntity, previewFormat: String) {
        draftBus.offer(diaryQuote(entry, previewFormat))
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            holder.dao().delete(id)
            onDone()
        }
    }

    companion object {
        const val ARG_ID = "id"
    }
}

data class DiarySettingsState(val busy: Boolean = false, val message: Int? = null)

@HiltViewModel
class DiarySettingsViewModel @Inject constructor(
    private val prefs: DiaryPrefs,
    private val writer: DiaryWriter,
) : ViewModel() {
    val frequency: StateFlow<DiaryFrequency> = prefs.frequency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryFrequency.SOMETIMES)
    val useChatModel: StateFlow<Boolean> = prefs.role.map { it == ModelRole.CHAT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val mutable = MutableStateFlow(DiarySettingsState())
    val state: StateFlow<DiarySettingsState> = mutable.asStateFlow()

    fun setFrequency(value: DiaryFrequency) {
        viewModelScope.launch { prefs.setFrequency(value) }
    }

    fun setUseChatModel(chat: Boolean) {
        viewModelScope.launch { prefs.setRole(if (chat) ModelRole.CHAT else ModelRole.BACKGROUND) }
    }

    fun writeNow() {
        if (mutable.value.busy) return
        mutable.value = DiarySettingsState(busy = true)
        viewModelScope.launch {
            val message = try {
                when (writer.run(force = true)) {
                    is DiaryRunResult.Written -> R.string.diary_result_written
                    DiaryRunResult.NoSource, DiaryRunResult.NoConversation, DiaryRunResult.NotChosen -> R.string.diary_result_no_chat
                    DiaryRunResult.AlreadyWritten -> R.string.diary_result_already
                    DiaryRunResult.ModelDeclined -> R.string.diary_result_declined
                    DiaryRunResult.Budget -> R.string.diary_result_budget
                    DiaryRunResult.Invalid, DiaryRunResult.Failed -> R.string.diary_result_failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.diary_result_failed
            }
            mutable.value = DiarySettingsState(busy = false, message = message)
        }
    }
}
