package org.xmyun.starmate.core.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.xmyun.starmate.core.model.Attachment

/** 附件列表存为 JSON 文本。 */
class Converters {

    @TypeConverter
    fun attachmentsToJson(attachments: List<Attachment>): String =
        json.encodeToString(attachmentListSerializer, attachments)

    @TypeConverter
    fun jsonToAttachments(text: String): List<Attachment> =
        try {
            json.decodeFromString(attachmentListSerializer, text)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val attachmentListSerializer = ListSerializer(Attachment.serializer())
    }
}
