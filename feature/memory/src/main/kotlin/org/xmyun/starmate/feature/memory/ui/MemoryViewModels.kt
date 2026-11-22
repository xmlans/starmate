package org.xmyun.starmate.feature.memory.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.feature.memory.R
import org.xmyun.starmate.feature.memory.consolidate.Consolidator
import org.xmyun.starmate.feature.memory.consolidate.Reflector
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.retrieval.VectorIndex

/** 记忆页的分组：概况、核心、我们之间、活跃、印象、归档（含已失效）。 */
data class MemoryGroups(
    val digest: String = "",
    val core: List<Mem> = emptyList(),
    val betweenUs: List<Mem> = emptyList(),
    val active: List<Mem> = emptyList(),
    val insights: List<Mem> = emptyList(),
    val archived: List<Mem> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean
        get() = digest.isBlank() && core.isEmpty() && betweenUs.isEmpty() && active.isEmpty() && insights.isEmpty() && archived.isEmpty()

    companion object {
        fun of(memories: List<Mem>, digest: String, query: String): MemoryGroups {
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) memories else memories.filter { q in it.content.lowercase() || q in it.tags.lowercase() }
            val live = shown.filter { !it.archived && it.isValid }
            val ordinary = live.filter { !it.isCore }
            return MemoryGroups(
                digest = if (q.isEmpty() || q in digest.lowercase()) digest else "",
                core = live.filter { it.isCore }.sortedByDescending { it.updatedAt },
                betweenUs = ordinary.filter { it.category == Categories.SELF }.sortedByDescending { it.updatedAt },
                active = ordinary.filter { it.category != Categories.SELF && it.category != Categories.INSIGHT }.sortedByDescending { it.updatedAt },
                insights = ordinary.filter { it.category == Categories.INSIGHT }.sortedByDescending { it.updatedAt },
                archived = shown.filter { it.archived || !it.isValid }.sortedByDescending { it.updatedAt },
                loaded = true,
            )
        }
    }
}

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repo: MemoryRepository,
    private val settings: MemorySettings,
) : ViewModel() {
    private val query = MutableStateFlow("")
    val queryText: StateFlow<String> = query.asStateFlow()

    val groups: StateFlow<MemoryGroups> = combine(repo.observeMemories(), repo.observeDigest(), query) { memories, digest, q ->
        MemoryGroups.of(memories, digest, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryGroups())

    fun setQuery(value: String) {
        query.value = value
    }

    fun saveDigest(text: String) {
        viewModelScope.launch { repo.setDigest(text.trim().take(settings.digestMaxChars)) }
    }
}

data class MemoryDetailState(val memory: Mem? = null, val episode: Episode? = null, val loaded: Boolean = false)

@HiltViewModel
class MemoryDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: MemoryRepository,
    private val clock: Clock,
) : ViewModel() {
    private val id: Long = savedState.get<Long>(ARG_ID) ?: 0L
    private val episode = MutableStateFlow<Episode?>(null)

    val state: StateFlow<MemoryDetailState> = combine(repo.observeMemory(id), episode) { mem, ep ->
        MemoryDetailState(mem, ep?.takeIf { mem?.episodeId == it.id }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryDetailState())

    init {
        viewModelScope.launch {
            val mem = repo.memory(id)
            episode.value = mem?.episodeId?.let { repo.episode(it) }
        }
    }

    fun save(content: String, importance: Int) = edit { it.copy(content = content, importance = importance) }

    fun setPinned(pinned: Boolean) = edit { it.copy(pinned = pinned, archived = if (pinned) false else it.archived) }

    /** 从归档或失效中恢复成活跃记忆。 */
    fun restore() = edit { it.copy(archived = false, invalidAt = null) }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteMemory(id)
            onDone()
        }
    }

    private fun edit(transform: (Mem) -> Mem) {
        viewModelScope.launch {
            val current = repo.memory(id) ?: return@launch
            repo.replaceByUser(transform(current), clock.millis())
        }
    }

    companion object {
        const val ARG_ID = "id"
    }
}

data class MemorySettingsState(
    val embeddingAvailable: Boolean? = null,
    val pending: Int = 0,
    val busy: Boolean = false,
    /** 上一次操作的结果文字（资源 id 和参数）。 */
    val message: Pair<Int, Int?>? = null,
)

@HiltViewModel
class MemorySettingsViewModel @Inject constructor(
    private val repo: MemoryRepository,
    private val consolidator: Consolidator,
    private val vectors: VectorIndex,
) : ViewModel() {
    private val mutable = MutableStateFlow(MemorySettingsState())
    val state: StateFlow<MemorySettingsState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(embeddingAvailable = vectors.available(), pending = consolidator.pendingCount())
        }
    }

    fun consolidateNow() = launchAction {
        when (consolidator.consolidatePending()) {
            is Consolidator.Outcome.Done, is Consolidator.Outcome.Skipped -> R.string.memory_result_done to null
            Consolidator.Outcome.NothingPending -> R.string.memory_result_nothing to null
            Consolidator.Outcome.Budget -> R.string.memory_result_budget to null
            else -> R.string.memory_result_failed to null
        }
    }

    fun reflectNow() = launchAction {
        when (consolidator.reflectNow()) {
            is Reflector.Result.Done -> R.string.memory_result_done to null
            Reflector.Result.TooFew -> R.string.memory_result_reflect_few to null
            Reflector.Result.Budget -> R.string.memory_result_budget to null
            Reflector.Result.Failed -> R.string.memory_result_failed to null
        }
    }

    fun rebuildVectors() = launchAction {
        if (!vectors.available()) R.string.memory_settings_embedding_off to null else R.string.memory_result_reindexed to vectors.rebuild()
    }

    fun clearAll() = launchAction {
        repo.clearAll()
        R.string.memory_result_cleared to null
    }

    private fun launchAction(block: suspend () -> Pair<Int, Int?>) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.memory_result_failed to null
            }
            mutable.value = mutable.value.copy(busy = false, message = message, pending = consolidator.pendingCount())
        }
    }
}
