package org.xmyun.starmate.feature.mcp

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 两个预设，鉴权都是 URL 里带 key（已对照官方文档核对）：
 * - Tavily：https://mcp.tavily.com/mcp/?tavilyApiKey=<key>，只开放 tavily_search 和 tavily_extract。
 * - 高德：https://mcp.amap.com/mcp?key=<key>，开放全部工具。
 * 地址里不含 key；key 只在发请求的那一刻作为查询参数加上（见 McpConnection）。
 *
 * 哪些工具可以不问用户就调用，由 [trustedTools] 逐个点名，其余的每次调用都要确认：
 * - tavily_extract 能让 Tavily 去访问任意网址。网页里的文字如果诱导模型把隐私拼进网址，
 *   这个工具就成了往外送数据的通道，所以必须让用户看到网址再放行。
 * - 高德点名的 12 个是官方包 @amap/amap-maps-mcp-server 0.0.8 里的查询类工具（2026-11-19 核对）。
 *   服务器以后新增的工具不在名单里，会自动落到“每次确认”，不会悄悄获得免确认的权限。
 */
enum class McpPreset(
    val id: String,
    val baseUrl: String,
    val keyParam: String,
    val prefix: String,
    /** 按加前缀后的名字过滤；null 表示全部开放。 */
    val allowList: Set<String>?,
    /** 不用确认就能调用的工具（加前缀后的名字）。开放了但不在这里的工具，每次调用都要用户确认。 */
    val trustedTools: Set<String>,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    TAVILY(
        id = "tavily",
        baseUrl = "https://mcp.tavily.com/mcp/",
        keyParam = "tavilyApiKey",
        prefix = "tavily_",
        allowList = setOf("tavily_search", "tavily_extract"),
        trustedTools = setOf("tavily_search"),
        titleRes = R.string.mcp_tavily_title,
        descriptionRes = R.string.mcp_tavily_desc,
    ),
    AMAP(
        id = "amap",
        baseUrl = "https://mcp.amap.com/mcp",
        keyParam = "key",
        prefix = "amap_",
        allowList = null,
        trustedTools = setOf(
            "amap_maps_around_search",
            "amap_maps_bicycling",
            "amap_maps_direction_driving",
            "amap_maps_direction_transit_integrated",
            "amap_maps_direction_walking",
            "amap_maps_distance",
            "amap_maps_geo",
            "amap_maps_ip_location",
            "amap_maps_regeocode",
            "amap_maps_search_detail",
            "amap_maps_text_search",
            "amap_maps_weather",
        ),
        titleRes = R.string.mcp_amap_title,
        descriptionRes = R.string.mcp_amap_desc,
    );

    /** SecretStore 里的名字。 */
    val secretName: String get() = "mcp_key_$id"

    /** 给模型和日志用的名字，不本地化。 */
    val label: String get() = if (this == TAVILY) "Tavily" else "AMap"
}

/** 每个预设的服务器地址。生产环境就是预设地址；测试里换成本地 mock 服务器。 */
interface McpEndpoints {
    fun baseUrl(preset: McpPreset): String
}

class DefaultMcpEndpoints @Inject constructor() : McpEndpoints {
    override fun baseUrl(preset: McpPreset): String = preset.baseUrl
}

/** 跟随系统语言的文字。 */
interface McpText {
    val chinese: Boolean
    fun get(@StringRes id: Int, vararg args: Any): String
}

fun McpText.pick(zh: String, en: String): String = if (chinese) zh else en

class AndroidMcpText @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpText {
    override val chinese: Boolean
        get() = context.resources.configuration.locales[0]?.language == "zh"

    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)
}
