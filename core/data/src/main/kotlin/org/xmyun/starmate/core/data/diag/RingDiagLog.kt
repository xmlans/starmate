package org.xmyun.starmate.core.data.diag

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog

/** 内存里保留最近 [CAPACITY] 条，写入前先过 [Redactor]。不落盘，不上传。 */
@Singleton
class RingDiagLog @Inject constructor(
    private val clock: Clock,
) : DiagLog {

    private val state = MutableStateFlow<List<DiagEntry>>(emptyList())

    override val entries: StateFlow<List<DiagEntry>> = state.asStateFlow()

    override fun log(tag: String, message: String) {
        val entry = DiagEntry(clock.instant(), Redactor.redact(tag), Redactor.redact(message))
        state.update { current ->
            if (current.size >= CAPACITY) current.drop(current.size - CAPACITY + 1) + entry else current + entry
        }
    }

    companion object {
        const val CAPACITY = 500
    }
}
