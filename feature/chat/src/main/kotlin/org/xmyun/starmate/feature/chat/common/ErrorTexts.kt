package org.xmyun.starmate.feature.chat.common

import androidx.annotation.StringRes
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.feature.chat.R

/** 每种模型错误给用户看的说明，以及该去哪里处理（null 表示原地重试即可）。 */
object ErrorTexts {

    @StringRes
    fun message(kind: LlmErrorKind?): Int = when (kind) {
        LlmErrorKind.NOT_CONFIGURED -> R.string.chat_error_not_configured
        LlmErrorKind.INVALID_KEY -> R.string.chat_error_invalid_key
        LlmErrorKind.QUOTA_OR_RATE_LIMIT -> R.string.chat_error_quota
        LlmErrorKind.MODEL_NOT_FOUND -> R.string.chat_error_model_not_found
        LlmErrorKind.TOOLS_UNSUPPORTED -> R.string.chat_error_tools_unsupported
        LlmErrorKind.UNREACHABLE -> R.string.chat_error_unreachable
        LlmErrorKind.TIMEOUT -> R.string.chat_error_timeout
        LlmErrorKind.BAD_RESPONSE -> R.string.chat_error_bad_response
        LlmErrorKind.UNKNOWN, null -> R.string.chat_error_unknown
    }

    /** 需要去模型配置页处理的错误。 */
    fun fixRoute(kind: LlmErrorKind?): String? = when (kind) {
        LlmErrorKind.NOT_CONFIGURED,
        LlmErrorKind.INVALID_KEY,
        LlmErrorKind.MODEL_NOT_FOUND,
        LlmErrorKind.TOOLS_UNSUPPORTED,
        LlmErrorKind.UNREACHABLE,
        -> ChatRoutes.SETTINGS_MODELS
        else -> null
    }
}
