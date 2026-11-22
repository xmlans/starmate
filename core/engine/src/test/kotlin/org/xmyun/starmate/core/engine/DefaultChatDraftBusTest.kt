package org.xmyun.starmate.core.engine

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes

class DefaultChatDraftBusTest {

    @Test
    fun offerThenConsumeClears() {
        val bus = DefaultChatDraftBus()
        val quote = Attachment(AttachmentTypes.QUOTE, JsonObject(emptyMap()))
        bus.offer(quote)
        assertEquals(quote, bus.pending.value)
        assertEquals(quote, bus.consume())
        assertNull(bus.pending.value)
        assertNull(bus.consume())
    }
}
