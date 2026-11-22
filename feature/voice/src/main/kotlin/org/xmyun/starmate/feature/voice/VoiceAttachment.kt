package org.xmyun.starmate.feature.voice

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes

enum class VoiceState(val wire: String) {
    RECORDING("recording"),
    READY("ready"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): VoiceState = entries.firstOrNull { it.wire == value } ?: FAILED
    }
}

/** 语音附件 payload 的读取结果。file 是相对 files/voice/ 的文件名。 */
data class VoicePayload(
    val id: String,
    val transcript: String,
    val tone: String?,
    val state: VoiceState,
    val file: String?,
    val durationMs: Long?,
    val preview: String?,
)

/** 语音附件的 payload 读写。更新时保留原有的其他键。 */
object VoiceAttachments {
    const val KEY_ID = "id"
    const val KEY_TRANSCRIPT = "transcript"
    const val KEY_TONE = "tone"
    const val KEY_STATE = "state"
    const val KEY_FILE = "file"
    const val KEY_DURATION = "durationMs"

    fun create(id: String, transcript: String, tone: String?, previewPrefix: String): Attachment {
        val map = linkedMapOf<String, JsonElement>(
            KEY_ID to JsonPrimitive(id),
            KEY_TRANSCRIPT to JsonPrimitive(transcript),
            KEY_STATE to JsonPrimitive(VoiceState.RECORDING.wire),
            ATTACHMENT_PREVIEW_KEY to JsonPrimitive(previewPrefix + transcript),
        )
        if (tone != null) map[KEY_TONE] = JsonPrimitive(tone)
        return Attachment(AttachmentTypes.VOICE, JsonObject(map))
    }

    fun read(attachment: Attachment): VoicePayload? {
        if (attachment.type != AttachmentTypes.VOICE) return null
        val p = attachment.payload
        val id = p.string(KEY_ID) ?: return null
        return VoicePayload(
            id = id,
            transcript = p.string(KEY_TRANSCRIPT).orEmpty(),
            tone = p.string(KEY_TONE),
            state = VoiceState.fromWire(p.string(KEY_STATE)),
            file = p.string(KEY_FILE),
            durationMs = (p[KEY_DURATION] as? JsonPrimitive)?.longOrNull,
            preview = p.string(ATTACHMENT_PREVIEW_KEY),
        )
    }

    fun ready(attachment: Attachment, file: String, durationMs: Long?): Attachment {
        val map = LinkedHashMap(attachment.payload)
        map[KEY_STATE] = JsonPrimitive(VoiceState.READY.wire)
        map[KEY_FILE] = JsonPrimitive(file)
        if (durationMs != null && durationMs > 0) map[KEY_DURATION] = JsonPrimitive(durationMs) else map.remove(KEY_DURATION)
        return attachment.copy(payload = JsonObject(map))
    }

    fun failed(attachment: Attachment): Attachment {
        val map = LinkedHashMap(attachment.payload)
        map[KEY_STATE] = JsonPrimitive(VoiceState.FAILED.wire)
        map.remove(KEY_FILE)
        map.remove(KEY_DURATION)
        return attachment.copy(payload = JsonObject(map))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
