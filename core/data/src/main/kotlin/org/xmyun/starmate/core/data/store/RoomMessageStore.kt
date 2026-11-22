package org.xmyun.starmate.core.data.store

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.db.toEntity
import org.xmyun.starmate.core.data.db.toModel
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.Role

@Singleton
class RoomMessageStore @Inject constructor(
    private val holder: ChatDatabaseHolder,
) : MessageStore {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecent(limit: Int): Flow<List<ChatMessage>> {
        if (limit <= 0) return flowOf(emptyList())
        return holder.database
            .filterNotNull()
            .flatMapLatest { it.chatDao().observeRecentDesc(limit) }
            .map { rows -> rows.asReversed().map { it.toModel() } }
    }

    override suspend fun recent(limit: Int): List<ChatMessage> {
        if (limit <= 0) return emptyList()
        return holder.dao().recentDesc(limit).asReversed().map { it.toModel() }
    }

    override suspend fun between(from: Instant, to: Instant): List<ChatMessage> =
        holder.dao().between(from.toEpochMilli(), to.toEpochMilli()).map { it.toModel() }

    override suspend fun byId(id: Long): ChatMessage? = holder.dao().messageById(id)?.toModel()

    override suspend fun insert(message: ChatMessage): Long =
        holder.dao().insertMessage(message.toEntity().copy(id = 0))

    override suspend fun update(message: ChatMessage) {
        holder.dao().updateMessage(message.toEntity())
    }

    override suspend fun delete(id: Long) {
        holder.dao().deleteMessage(id)
    }

    override suspend fun lastMessageAt(role: Role?): Instant? {
        val dao = holder.dao()
        val millis = if (role == null) dao.lastMessageAt() else dao.lastMessageAtForRole(role.name)
        return millis?.let(Instant::ofEpochMilli)
    }
}
