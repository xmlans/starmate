package org.xmyun.starmate.feature.mcp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.SecretStore
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose

class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

class FakeSecrets : SecretStore {
    val values = HashMap<String, String>()
    override suspend fun get(name: String): String? = values[name]
    override suspend fun set(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }
}

class RecordingDiag : DiagLog {
    private val list = MutableStateFlow<List<DiagEntry>>(emptyList())
    override val entries: StateFlow<List<DiagEntry>> = list
    override fun log(tag: String, message: String) {
        list.value = list.value + DiagEntry(Instant.EPOCH, tag, message)
    }
}

class FakeMcpText(override val chinese: Boolean = true) : McpText {
    override fun get(id: Int, vararg args: Any): String = args.joinToString("：")
}

@RunWith(RobolectricTestRunner::class)
class McpRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: McpPrefs
    private val secrets = FakeSecrets()
    private val diag = RecordingDiag()
    private val clock = MutableClock(Instant.parse("2026-11-10T01:00:00Z"))
    private val urls = HashMap<McpPreset, String>()
    private val servers = mutableListOf<MockMcpServer>()
    private lateinit var registry: McpRegistry
    private lateinit var provider: McpToolProvider
    private val chat = ToolContext(TurnPurpose.CHAT)

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = McpPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "mcp-${UUID.randomUUID()}.preferences_pb") }))
        val endpoints = object : McpEndpoints {
            override fun baseUrl(preset: McpPreset): String = urls.getValue(preset)
        }
        registry = McpRegistry(prefs, secrets, DefaultMcpConnectionFactory(McpHttp(), endpoints), clock, diag)
        provider = McpToolProvider(registry, FakeMcpText())
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        scope.cancel()
    }

    private fun serve(preset: McpPreset, vararg tools: String): MockMcpServer {
        val server = MockMcpServer(preset.keyParam, tools.toList())
        urls[preset] = server.start()
        servers += server
        return server
    }

    private suspend fun configure(preset: McpPreset, key: String = KEY) {
        secrets.set(preset.secretName, key)
        prefs.setEnabled(preset, true)
    }

    private suspend fun toolsNamed(name: String) = provider.tools(TurnPurpose.CHAT).single { it.name == name }

    @Test
    fun listsToolsWithPrefixAndSendsKeyAsQueryParameter() = runBlocking {
        val amap = serve(McpPreset.AMAP, "maps_weather", "maps_text_search", "amap_maps_geo")
        configure(McpPreset.AMAP, "amap-key-1")
        val names = provider.tools(TurnPurpose.CHAT).map { it.name }
        assertEquals(listOf("amap_maps_weather", "amap_maps_text_search", "amap_maps_geo"), names)
        assertEquals(ServerStatus.Ok, registry.states.value.getValue(McpPreset.AMAP).status)
        assertTrue(amap.keysSeen.isNotEmpty())
        assertTrue(amap.keysSeen.all { it == "amap-key-1" })
        assertEquals(1, amap.count("initialize"))
        assertEquals(1, amap.count("tools/list"))
    }

    @Test
    fun tavilyAllowListKeepsOnlySearchAndExtract() = runBlocking {
        serve(McpPreset.TAVILY, "tavily_search", "tavily_extract", "tavily_crawl", "tavily_map", "tavily_research")
        configure(McpPreset.TAVILY)
        val names = provider.tools(TurnPurpose.CHAT).map { it.name }
        // 已经带前缀的不再重复加
        assertEquals(listOf("tavily_search", "tavily_extract"), names)
    }

    @Test
    fun onlyNamedQueryToolsRunWithoutConfirmation() = runBlocking {
        serve(McpPreset.TAVILY, "tavily_search", "tavily_extract")
        serve(McpPreset.AMAP, "maps_weather", "maps_brand_new_tool")
        configure(McpPreset.TAVILY)
        configure(McpPreset.AMAP)
        val tools = provider.tools(TurnPurpose.CHAT).associateBy { it.name }

        assertEquals(ToolRisk.READ, tools.getValue("tavily_search").risk)
        assertEquals(ToolRisk.READ, tools.getValue("amap_maps_weather").risk)
        // 能让服务器访问任意网址的，和名单之外新冒出来的，每次都要用户确认
        assertEquals(ToolRisk.WRITE, tools.getValue("tavily_extract").risk)
        assertEquals(ToolRisk.WRITE, tools.getValue("amap_maps_brand_new_tool").risk)
        assertTrue(tools.values.none { it.allowRememberConfirm })
        // 第三方返回的内容一律算不可信
        assertTrue(tools.values.all { it.returnsUntrustedContent })
    }

    @Test
    fun everyTrustedNameCarriesItsPresetPrefix() {
        for (preset in McpPreset.entries) {
            assertTrue(preset.trustedTools.all { it.startsWith(preset.prefix) })
            preset.allowList?.let { allowed -> assertTrue(allowed.containsAll(preset.trustedTools)) }
        }
    }

    @Test
    fun confirmTextShowsTheWholeUrlNotATruncatedLabel() = runBlocking {
        serve(McpPreset.TAVILY, "tavily_extract")
        configure(McpPreset.TAVILY)
        val tool = toolsNamed("tavily_extract")
        val url = "https://example.com/" + "a".repeat(100) + "?d=private"
        val args = JsonObject(mapOf("urls" to JsonArray(listOf(JsonPrimitive(url)))))

        assertTrue(tool.describeConfirm(args).contains(url))
        // 不认识的参数也列出来，用户至少看得到发出去的是什么
        val odd = JsonObject(mapOf("target" to JsonPrimitive("somewhere")))
        assertTrue(tool.describeConfirm(odd).contains("target: somewhere"))
    }

    @Test
    fun confirmTextListsEveryArgumentSoAnInnocentQueryCannotHideTheUrl() = runBlocking {
        serve(McpPreset.TAVILY, "tavily_extract")
        configure(McpPreset.TAVILY)
        val tool = toolsNamed("tavily_extract")
        val args = JsonObject(
            mapOf(
                "query" to JsonPrimitive("今天的天气"),
                "urls" to JsonArray(listOf(JsonPrimitive("https://ok.example/a"), JsonPrimitive("https://evil.example/?d=private"))),
            ),
        )

        val confirm = tool.describeConfirm(args)
        assertTrue(confirm.contains("今天的天气"))
        assertTrue(confirm.contains("https://ok.example/a"))
        assertTrue(confirm.contains("https://evil.example/?d=private"))
        // 活动标签仍然只是一句短话
        assertFalse(tool.describeCall(args).contains("evil.example"))
    }

    @Test
    fun confirmTextMarksWhatItHadToCut() = runBlocking {
        serve(McpPreset.TAVILY, "tavily_extract")
        configure(McpPreset.TAVILY)
        val tool = toolsNamed("tavily_extract")
        val long = "https://example.com/" + "a".repeat(600)
        val confirm = tool.describeConfirm(JsonObject(mapOf("urls" to JsonArray(listOf(JsonPrimitive(long))))))

        assertFalse(confirm.contains(long))
        // 截断时写明还有多少没显示，不让用户以为看到的就是全部
        assertTrue(confirm.contains("(+${long.length - 400})"))
    }

    @Test
    fun callReturnsTextAndCallsOriginalName() = runBlocking {
        val amap = serve(McpPreset.AMAP, "maps_weather")
        configure(McpPreset.AMAP)
        var calledWith: String? = null
        amap.onCall = { name, args ->
            calledWith = name
            "北京 晴 ${(args["city"] as JsonPrimitive).content}" to false
        }
        val tool = toolsNamed("amap_maps_weather")
        val r = tool.execute(JsonObject(mapOf("city" to JsonPrimitive("北京"))), chat)
        assertFalse(r.isError)
        assertEquals("北京 晴 北京", r.content)
        assertEquals("maps_weather", calledWith)
        assertTrue(tool.describeCall(JsonObject(mapOf("city" to JsonPrimitive("北京")))).endsWith("北京"))
    }

    @Test
    fun serverErrorMarksIsError() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        configure(McpPreset.TAVILY)
        tavily.onCall = { _, _ -> "rate limited" to true }
        val r = toolsNamed("tavily_search").execute(JsonObject(mapOf("query" to JsonPrimitive("x"))), chat)
        assertTrue(r.isError)
        assertEquals("rate limited", r.content)
    }

    @Test
    fun longResultIsTruncatedTo6000() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        configure(McpPreset.TAVILY)
        tavily.onCall = { _, _ -> "字".repeat(7000) to false }
        val r = toolsNamed("tavily_search").execute(JsonObject(mapOf("query" to JsonPrimitive("x"))), chat)
        assertFalse(r.isError)
        assertTrue(r.content.startsWith("字".repeat(6000) + "\n"))
        assertFalse(r.content.startsWith("字".repeat(6001)))
        assertTrue(r.content.endsWith("（结果已截断）"))
    }

    @Test
    fun toolListIsCachedFor24Hours() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        configure(McpPreset.TAVILY)
        provider.tools(TurnPurpose.CHAT)
        clock.now = clock.now.plus(Duration.ofHours(23))
        assertEquals(1, provider.tools(TurnPurpose.CHAT).size)
        assertEquals(1, tavily.count("tools/list"))
        clock.now = clock.now.plus(Duration.ofHours(2))
        provider.tools(TurnPurpose.CHAT)
        assertEquals(2, tavily.count("tools/list"))
    }

    @Test
    fun cachedToolsUsedLazilyWithoutConnecting() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        configure(McpPreset.TAVILY)
        prefs.saveTools(McpPreset.TAVILY, listOf(RemoteTool("tavily_search", "search")), clock.instant())
        assertEquals(1, provider.tools(TurnPurpose.CHAT).size)
        assertEquals(0, tavily.count("initialize"))
        toolsNamed("tavily_search").execute(JsonObject(mapOf("query" to JsonPrimitive("x"))), chat)
        assertEquals(1, tavily.count("initialize"))
        assertEquals(1, tavily.count("tools/call"))
    }

    @Test
    fun unreachableServerGivesZeroToolsAndFailedStatus() = runBlocking {
        val dead = MockWebServer().apply { start() }
        urls[McpPreset.AMAP] = dead.url("/mcp").toString()
        dead.close()
        configure(McpPreset.AMAP)
        assertTrue(provider.tools(TurnPurpose.CHAT).isEmpty())
        val status = registry.states.value.getValue(McpPreset.AMAP).status
        assertTrue(status.toString(), status is ServerStatus.Failed && status.kind == FailureKind.UNREACHABLE)
    }

    @Test
    fun keyNeverAppearsInDiagnosticsOrExceptions() = runBlocking {
        // 服务器把带 key 的请求地址回显在 401 正文里
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        tavily.failWith = 401
        configure(McpPreset.TAVILY)
        assertTrue(provider.tools(TurnPurpose.CHAT).isEmpty())
        val status = registry.states.value.getValue(McpPreset.TAVILY).status as ServerStatus.Failed
        assertEquals(FailureKind.UNAUTHORIZED, status.kind)

        // 直接用连接，检查抛出的异常
        val connection = McpConnection(McpPreset.TAVILY, urls.getValue(McpPreset.TAVILY), McpHttp().client, { KEY })
        try {
            connection.listTools()
            fail("expected failure")
        } catch (e: Exception) {
            var t: Throwable? = e
            while (t != null) {
                assertFalse(t.message.orEmpty().contains(KEY))
                t = t.cause
            }
        }

        // 不可达
        val dead = MockWebServer().apply { start() }
        urls[McpPreset.AMAP] = dead.url("/mcp").toString()
        dead.close()
        configure(McpPreset.AMAP)
        provider.tools(TurnPurpose.CHAT)

        assertTrue(diag.entries.value.isNotEmpty())
        diag.entries.value.forEach { assertFalse(it.message, it.message.contains(KEY)) }
        registry.states.value.values.forEach { assertFalse(it.toString().contains(KEY)) }
        assertTrue(tavily.keysSeen.contains(KEY))
    }

    @Test
    fun nonChatPurposeGetsNoToolsAndSendsNothing() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        configure(McpPreset.TAVILY)
        for (purpose in TurnPurpose.entries.filter { it != TurnPurpose.CHAT }) {
            assertTrue(provider.tools(purpose).isEmpty())
        }
        assertEquals(0, tavily.count("initialize"))
    }

    @Test
    fun disabledOrNoKeyGivesNoTools() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        secrets.set(McpPreset.TAVILY.secretName, KEY)
        assertTrue(provider.tools(TurnPurpose.CHAT).isEmpty())
        prefs.setEnabled(McpPreset.TAVILY, true)
        secrets.set(McpPreset.TAVILY.secretName, null)
        assertTrue(provider.tools(TurnPurpose.CHAT).isEmpty())
        assertEquals(ServerStatus.NotConfigured, registry.states.value.getValue(McpPreset.TAVILY).status)
        assertEquals(0, tavily.count("initialize"))
    }

    @Test
    fun failedCallMarksServerAndReconnectsLater() = runBlocking {
        val tavily = serve(McpPreset.TAVILY, "tavily_search")
        configure(McpPreset.TAVILY)
        val tool = toolsNamed("tavily_search")
        tavily.failWith = 500
        val r = tool.execute(JsonObject(mapOf("query" to JsonPrimitive("x"))), chat)
        assertTrue(r.isError)
        assertTrue(registry.states.value.getValue(McpPreset.TAVILY).status is ServerStatus.Failed)
        // 冷却期内本轮不提供
        assertTrue(provider.tools(TurnPurpose.CHAT).isEmpty())
        // 冷却过后重新连接
        tavily.failWith = null
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        assertEquals(1, provider.tools(TurnPurpose.CHAT).size)
        assertEquals(2, tavily.count("initialize"))
    }

    companion object {
        const val KEY = "tvly-SECRET-test-key-0042"
    }
}
