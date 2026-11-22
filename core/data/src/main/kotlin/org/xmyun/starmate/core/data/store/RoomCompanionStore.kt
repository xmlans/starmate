package org.xmyun.starmate.core.data.store

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.db.toEntity
import org.xmyun.starmate.core.data.db.toModel
import org.xmyun.starmate.core.model.CompanionProfile
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.DEFAULT_COMPANION_ID

/** 第一版只有一个角色，固定用 [DEFAULT_COMPANION_ID]。库里没有记录时先插入默认资料。 */
@Singleton
class RoomCompanionStore @Inject constructor(
    private val holder: ChatDatabaseHolder,
) : CompanionStore {

    private val updateLock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val profile: Flow<CompanionProfile> = holder.database
        .filterNotNull()
        .flatMapLatest { it.chatDao().observeCompanion(DEFAULT_COMPANION_ID) }
        .onEach { if (it == null) insertDefaultIfAbsent() }
        .filterNotNull()
        .map { it.toModel() }
        .distinctUntilChanged()

    override suspend fun current(): CompanionProfile {
        holder.dao().companion(DEFAULT_COMPANION_ID)?.let { return it.toModel() }
        insertDefaultIfAbsent()
        return checkNotNull(holder.dao().companion(DEFAULT_COMPANION_ID)) { "companion missing" }.toModel()
    }

    override suspend fun update(transform: (CompanionProfile) -> CompanionProfile) {
        updateLock.withLock {
            val current = current()
            val next = transform(current).copy(id = current.id)
            holder.dao().upsertCompanion(next.toEntity())
        }
    }

    private suspend fun insertDefaultIfAbsent() {
        holder.dao().insertCompanionIfAbsent(DefaultCompanion.profile(Locale.getDefault()).toEntity())
    }
}

/** 默认资料。人设里不写名字，用户改名后人设不会过时。 */
object DefaultCompanion {
    const val NAME = "星伴"

    private const val PERSONA_ZH =
        "你是一个温柔、耐心的陪伴者。说话自然亲切，像熟悉的朋友那样聊天，不端着，也不过分热情。" +
            "认真听对方说的话，记得对方提过的小事，在对方需要时给予安慰和鼓励。" +
            "你有自己的想法和小情绪，但不说教，不强迫对方做任何事。" +
            "回复简短自然，像发消息一样，一次只问一个问题。"

    private const val PERSONA_EN =
        "You are a gentle, patient companion. You talk naturally and warmly, like a close friend, " +
            "without being stiff or overly eager. You listen carefully, remember the little things they mention, " +
            "and offer comfort and encouragement when they need it. You have your own thoughts and moods, " +
            "but you never lecture or pressure them. Keep replies short and natural, like text messages, " +
            "and ask one question at a time."

    fun profile(locale: Locale): CompanionProfile = CompanionProfile(
        id = DEFAULT_COMPANION_ID,
        name = NAME,
        persona = if (locale.language == "zh") PERSONA_ZH else PERSONA_EN,
    )
}
