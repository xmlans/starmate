package org.xmyun.starmate.core.llm.provider

import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.ProviderPreset

/** 内置服务商预设。自定义服务商由界面直接填 base URL，不在这里。 */
object Presets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("openai", "OpenAI", ProviderKind.OPENAI_COMPAT, "https://api.openai.com/v1"),
        ProviderPreset("anthropic", "Anthropic", ProviderKind.ANTHROPIC, "https://api.anthropic.com"),
        ProviderPreset("deepseek", "DeepSeek", ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com/v1"),
        ProviderPreset("siliconflow", "硅基流动", ProviderKind.OPENAI_COMPAT, "https://api.siliconflow.cn/v1"),
        ProviderPreset("openrouter", "OpenRouter", ProviderKind.OPENAI_COMPAT, "https://openrouter.ai/api/v1"),
        ProviderPreset("gemini", "Gemini", ProviderKind.OPENAI_COMPAT, "https://generativelanguage.googleapis.com/v1beta/openai"),
    )
}

/** SecretStore 里服务商 key 的名字。 */
object ProviderKeys {
    fun secretName(providerId: String): String = "provider_key_$providerId"
}
