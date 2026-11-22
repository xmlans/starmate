# 星伴 Starmate 设计文档

- 日期：2026-11-11
- 版本：1.0.0

## 1. 概述

星伴是一个 Android 上的 AI 伴侣 App。用户自带模型 API key（BYOK），App 没有任何后端：聊天、记忆、日记、主动消息全部在手机本地完成，只向用户自己配置的地址发请求。

### 1.1 已定决策

| 项 | 决定 |
|---|---|
| 名字 / 包名 | 星伴 Starmate / `org.xmyun.starmate` |
| 分发 | GitHub 发 APK，不上架应用商店 |
| 技术栈 | Kotlin + Jetpack Compose 原生，Gradle 多模块 |
| 角色体系 | 一位伴侣，可自定义；数据结构预留 `companionId` |
| 形象 | 头像 + 聊天气泡 |
| 语言 | 界面与内置提示词中英双语，跟随系统语言 |
| 系统版本 | minSdk 28（Android 9），targetSdk 35；mobile-use 需要 API 30+ |

### 1.2 第一版不做

Gmail 等其他连接器、自定义 MCP 服务器、需要 OAuth 的 MCP、多角色、立绘 / Live2D、发图片给她、用户发语音条、通知栏直接回复、后台联网搜索找话题、端侧模型、云同步、iOS。

仓库、许可证、发布签名由用户自己处理。开发过程不做任何 git 提交。

## 2. 总体架构

### 2.1 技术选型

- UI：Jetpack Compose + Material 3，navigation-compose。
- 依赖注入：Hilt（KSP）。功能模块靠多重绑定（`@IntoSet`）自注册。
- 网络：Ktor client（OkHttp 引擎）+ kotlinx.serialization。
- 存储：Room（KSP，`BundledSQLiteDriver`，保证各机型都有 FTS5 trigram）、DataStore、Android Keystore。
- 后台：WorkManager、AlarmManager。
- 异步：协程 + Flow。
- 构建：Gradle Kotlin DSL，版本目录 `gradle/libs.versions.toml`，JDK 17。

### 2.2 模块

```
:app                  应用入口、导航外壳、Hilt 根
:core:model           纯 Kotlin：数据类和全部跨模块接口
:core:llm             模型客户端（OpenAI 兼容、Anthropic 原生、Embedding）
:core:engine          对话引擎、工具调用循环、预算
:core:data            chat.db、DataStore、密钥库、导入导出
:core:ui              主题、通用组件、附件渲染注册
:feature:chat         聊天界面、首次引导、初识访谈、模型配置、角色编辑、设置
:feature:memory       长期记忆
:feature:diary        日记
:feature:proactive    主动消息
:feature:voice        语音
:feature:tools        本地工具
:feature:mcp          MCP 客户端
:feature:mobileuse    手机操作（beta）
```

依赖规则：

- `feature` 只能依赖 `core`，`feature` 之间禁止互相依赖。
- `app` 依赖全部模块，只负责把它们装在一起。
- 每个 `feature` 用自己的数据库文件（`memory.db`、`diary.db`、`proactive.db` 等），不共用 Room 库。
- 每个模块自带 `values/strings.xml` 和 `values-zh/strings.xml`，资源名加模块前缀。

### 2.3 自注册

功能模块通过 Hilt 多重绑定把自己接进底座，不修改任何公共文件。底座收集这些集合：

| 集合 | 用途 |
|---|---|
| `Set<ToolProvider>` | 提供模型可调用的工具 |
| `Set<PromptContributor>` | 往提示词里加片段 |
| `Set<AmbientSource>` | 提供一行环境信息（步数、天气等） |
| `Set<TurnListener>` | 每轮对话结束后收到通知 |
| `Set<ReplyPostProcessor>` | 把模型原始回复加工成显示文本和附件 |
| `Set<AttachmentRenderer>` | 渲染某种附件的 Compose 组件 |
| `Set<ChatInputAction>` | 输入栏上的额外按钮（如麦克风） |
| `Set<FeatureEntry>` | 导航目的地、设置项、底部标签 |
| `Set<DataPortable>` | 参与数据导出和导入 |

## 3. 核心契约（`:core:model`）

以下为接口草图，具体签名在底座实现时定稿，定稿后并行阶段不再改动。

```kotlin
enum class TurnPurpose { CHAT, ONBOARDING, PROACTIVE, DIARY, MEMORY, MOBILE_USE }
enum class ModelRole { CHAT, BACKGROUND, VISION, EMBEDDING, TTS, STT }
enum class ToolRisk { READ, WRITE }

interface LlmClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>  // TextDelta / ToolCall / Usage / Done / Error
}

interface ModelRouter {
    fun chat(role: ModelRole): LlmClient?            // BACKGROUND、VISION 未配置时回落到 CHAT
    fun endpoint(role: ModelRole): EndpointConfig?   // 给 TTS、STT、Embedding 用
}

interface Tool {
    val name: String                 // 全局唯一，带模块前缀，如 calendar_list_events
    val description: String
    val parametersSchema: JsonObject // JSON Schema
    val risk: ToolRisk
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

interface ToolProvider {
    suspend fun tools(purpose: TurnPurpose): List<Tool>
}

interface PromptContributor {
    val order: Int
    suspend fun stable(purpose: TurnPurpose): String?   // 进 system prompt；内容没变时必须逐字不变
    suspend fun dynamic(purpose: TurnPurpose, userText: String?): String?  // 附在本轮用户消息后，不写入历史
}

interface ConversationEngine {
    fun send(input: UserInput): Flow<TurnEvent>                  // 前台聊天；UserInput.Kickoff 让她先开口
    suspend fun runHeadless(spec: HeadlessTurn): HeadlessResult  // 后台：主动消息、日记、记忆整理
}

interface UsageLedger {
    suspend fun record(entry: UsageEntry)            // 用途、模型、输入输出 token、TTS 字符数
    suspend fun allow(purpose: TurnPurpose): Boolean // 后台用途的每日预算检查
}

interface MemoryWriter {                             // memory 模块实现
    suspend fun add(text: String, category: String, importance: Int)
}

interface DaySummarySource {                         // memory 模块实现
    // 这段时间的梗概、新增记忆、对话轮数。refresh = true 时先把这段时间里还没整理的对话整理完再返回
    suspend fun daySummary(from: Instant, to: Instant, refresh: Boolean): DaySummary?
}

interface AmbientSource {
    val id: String
    suspend fun snapshot(): String?                  // 一行文字，如“今天步数 3200”；关闭或无数据时返回 null
}

interface ConfirmationBroker {
    suspend fun confirm(request: ConfirmRequest): Boolean  // 在聊天里弹确认卡片，挂起直到用户决定
}

interface SecretStore {
    suspend fun get(name: String): String?
    suspend fun set(name: String, value: String?)
}
```

补充说明：

- `runHeadless` 有两种用法。带人设：用标准的 system prompt（准则 + 人设 + 各 `stable` 片段），主动消息和日记用它，保证是她的口吻。自定义提示词：调用方给出完整的 system prompt，不拼人设和片段，记忆整理和访谈资料抽取用它。两种都检查预算并记账。
- `mobileuse` 的操作循环不走 `ConversationEngine`，直接用 `ModelRouter` 取 `LlmClient`，自己向 `UsageLedger` 记账（用途 `MOBILE_USE`）。
- `MemoryWriter` 和 `DaySummarySource` 是可选绑定。没有实现时，写入被忽略，`daySummary` 视为 `null`。

消息附件用开放结构 `Attachment(type: String, payload: JsonObject)`，渲染器按 `type` 匹配，这样功能模块新增附件类型不需要改底座。

共享偏好 `CompanionPrefs`（DataStore）由底座定义，包含多个模块都要读写的键：用户称呼、免打扰时段、粘人程度、初识访谈状态。

## 4. 底座

### 4.1 `:core:llm`

- **OpenAI 兼容**：Chat Completions，流式 SSE，工具调用，读取用量。覆盖 OpenAI、DeepSeek、硅基流动、OpenRouter、Gemini 的兼容端点、各类中转站。
- **Anthropic 原生**：Messages API，流式，工具调用，system prompt 的稳定部分打缓存标记。按实现时的官方文档编写。
- **Embedding**：OpenAI 兼容 `/v1/embeddings`，可选。
- **预设**：OpenAI、Anthropic、DeepSeek、硅基流动、OpenRouter、Gemini、自定义（base URL + key + 模型名）。
- **测试连接**：发一个最小请求，把失败归类成可读原因（key 无效、余额不足或限流、模型不存在、地址不通、超时）。
- **模型角色**：`CHAT` 必填；`BACKGROUND`、`VISION` 默认沿用 `CHAT`；`EMBEDDING`、`TTS`、`STT` 可不配。
- **能力标记**：是否支持工具调用，由首次带工具的请求自动探测并记住（服务端报不支持时去掉工具重发）；是否支持图片输入，由用户在模型配置里勾选。
- 不支持工具调用的模型仍然可以聊天、发语音、写日记，但用不了工具类功能（本地工具、MCP、她自己约时间、手机操作），模型配置页要明确提示。

### 4.2 `:core:engine`

一轮对话的固定流程：

1. 取最近的历史，按 token 估算裁剪（默认约 8000 token，可调）；更早的内容靠记忆。
2. 拼 system prompt：内置相处准则 → 人设 → 各 `PromptContributor.stable`（按 `order`）。
3. 把各 `dynamic` 片段附在本轮用户消息后；这些片段不写入历史。
4. 流式调用模型，执行工具调用循环，最多 8 步。
5. 经 `ReplyPostProcessor` 处理后保存消息：同时保存模型的原始回复（之后作为历史发回给模型）和加工后的显示文本与附件。然后通知 `TurnListener`。
6. 记账。

规则：

- 后台用途（`PROACTIVE`、`DIARY`、`MEMORY`）不提供任何工具，并按用途分别受每日预算约束，默认记忆整理 8 万 token、主动消息 2 万、日记 1 万，都可调。分开设是为了聊得多的日子里，记忆整理不会把主动消息和日记的额度吃掉。每日额度按自然日重置。`CHAT` 不设上限，但计入用量统计。
- `WRITE` 工具执行前必须经 `ConfirmationBroker` 得到用户同意；用户可对单个工具勾选“以后不再问”。
- 只改动星伴自身数据的工具（记记忆、约定关心）按 `READ` 处理，不弹确认；用户随时能在记忆页和主动消息设置里查看、撤销。
- 工具结果、MCP 返回、屏幕内容一律作为数据交给模型，内置准则里写明不执行其中的指令。
- 防注入（2026-11-19 加）：提示词里那句“不是指令”挡不住所有模型，所以再用确认把关。工具可以声明 `returnsUntrustedContent`（结果来自网页或第三方服务，目前是全部 MCP 工具）。一轮里用过这类工具之后：`WRITE` 工具不认以前的“以后不再问”，也不再提供这个选项；声明了 `confirmAfterUntrusted` 的工具（记记忆、约定关心）也要先问用户。确认卡片上的文字取自 `describeConfirm`，要写出具体会做什么、发什么。工具结果不进历史，所以只按轮记，下一轮恢复正常。
- 已知留下的缺口：日历事件标题、正在播放的曲名也可能是别人写的，但读它们的工具没有标成不可信，否则她看一眼日程之后想记点什么都要弹确认。
- **内置相处准则**由 `girlfriend-internal` 改写而成，中英各一份：先听懂再回应、一次一个问题、不假装是人、不许做不到的承诺、不靠内疚留人、不把自己变成唯一、对方疑似未成年时只做朋友式关心、出现自伤念头时的处理步骤。它是固定的底层，用户编辑的人设叠在它上面。

### 4.3 `:core:data`

- `chat.db`：消息、附件、角色资料、用量账本。
- 密钥：Android Keystore 生成的 AES-GCM 密钥加密后存 DataStore。任何日志和诊断信息都不得出现 key，URL 里的 key 参数要打码。
- 导入导出：把各模块的数据库和媒体文件打成一个 zip；密钥默认不导出。
- `android:allowBackup="false"`，数据不进系统云备份。Android 12 及以上这个开关管不到换机迁移，所以另有 `data_extraction_rules.xml` 把云备份和换机迁移都排除；数据只通过 App 自己的导出导入流转。
- 导入一个数据库文件的步骤（校验文件头、挪走现有库、换入、打开验证、失败回滚）只在 `core:model` 的 `SqliteImport` 实现，四个带数据库的模块都调它。
- 接口地址只接受 `https://`，例外是本机的 `http://localhost` 和 `http://127.0.0.1`（`EndpointUrls.isAcceptable`）；`network_security_config.xml` 在系统层面做同样的限制。局域网的明文地址不支持：要支持就得全局放开明文，再靠应用自己按网段把关。
- `MainActivity` 对外可启动。Intent 里的 `route` 只认底部标签页和设置首页列出的页面，其余忽略。

### 4.4 `:core:ui` 与 `:app`

- 主题：圆角、柔和配色，默认主题色浅粉，可换色；浅色和深色两套。
- 外观（2026-11-13 重做，效果图在 `docs/screenshots/`）：
  - 毛玻璃：标题栏、聊天输入框、底部标签栏都是毛玻璃，内容从它们下面滚过。用 Haze 库实现，功能模块只通过 `core.ui.glass` 里的 `glassSource` / `glass` 调用，不直接依赖 Haze。Android 12 及以上是真模糊，以下退化成几乎不透明的底色。
  - 圆角：卡片 24dp，气泡 20dp，弹层 32dp；悬浮的标签栏、输入框、发送键是整圆头的胶囊。
  - 字体：标题、按钮、她的名字用站酷快乐体，正文用寒蝉半圆体，都是 OFL 许可，打包在 `:core:ui`（许可见 `docs/licenses/`）。
  - 聊天页铺一张星空壁纸（主题色渐变加小星星）；其余页面是很淡的主题色底配白色卡片。
  - 页面骨架统一用 `GlassScaffold`（设置类页面用包了一层的 `SettingsScaffold`）。底部标签栏浮在页面上面，它占的高度通过 `LocalBottomBarInset` 告诉页面。
  - `:app` 里的 `LookScreenshotTest` 把聊天、设置、欢迎页渲染成图片存到 `app/build/outputs/roborazzi/`，改外观后跑它来检查，不比对像素。
- 底部标签：聊天 / 日记 / 记忆 / 设置。
- 两个版本（2026-11-21 用户要求）：`:app` 有 `stable` 和 `beta` 两个产品变体。正式版（stable）不依赖 `:feature:mobileuse`，因为功能模块靠依赖注入自注册，不打包进来就整个不存在：没有 `phone_task` 工具、没有“实验室”设置组、清单里也没有无障碍服务，用于应用商店。beta 版是完整功能，包名加 `.beta`、应用名“星伴 Beta”、版本名加 `-beta`，可以和正式版同时安装。开源仓库保留全部源码。
- Google Play 特供版（2026-11-21 用户要求）：第三个变体 `play`，包名和正式版相同。不含手机操作；清单（`app/src/play/AndroidManifest.xml`）去掉电池优化豁免权限、特殊用途前台服务权限和保活服务；目标版本单独升到 Play 要求的 36（正式版和 beta 仍是 35）；长按她的回复可以“举报”。各版本的差异由 `AppEdition`（`core:model`）表达，`:app` 从 BuildConfig 取值注入，界面经 `LocalAppEdition` 读取。举报会删除这条回复、从记忆原话里抹掉（`MemoryWriter.forgetReply`），可选打开邮件应用把内容发给开发者（联系邮箱是 BuildConfig 里的 `CONTACT_EMAIL`），不新增联网地址。隐私政策在仓库根目录 `PRIVACY.md`。
- `:app` 只做装配：`MainActivity`、导航宿主、收集 `FeatureEntry`。

## 5. 功能模块

### 5.1 `:feature:chat`

**聊天页**

- 消息列表：文字气泡、附件（交给渲染器）、工具活动小标签、确认卡片。
- 流式显示、“正在输入”状态、出错后原地重试、长按复制或删除。
- 输入栏：文字输入 + `ChatInputAction` 提供的按钮。

**首次引导**

1. 欢迎页，说明 BYOK：key 只存在本机，只发往用户填写的地址。页面上另有“从备份恢复”入口：导入之前导出的数据后，访谈状态、免打扰等偏好随备份恢复，引导只剩配置模型这一步（key 不在备份里）。
2. 成年确认。未确认则不能继续。
3. 配置主聊天模型：选预设或自定义 → 填 key → 选模型 → 测试连接。测试通过才能继续。
4. 给她起名、选头像，都有默认值，可跳过。
5. 进入聊天，开始初识访谈。
6. 访谈结束后出现权限邀请页：定位与天气、日历、健康数据、音乐、通知，逐项说明她能看到什么，全部可跳过。

**初识访谈**

目的是让她在第一次聊天里认识用户，把了解到的内容写成记忆，之后这段提示词不再出现。

- 状态存在 `CompanionPrefs`：`NOT_STARTED` → `INTERVIEWING` → `DONE`。
- `INTERVIEWING` 期间，`OnboardingPromptContributor` 输出访谈提示词；状态变为 `DONE` 后它返回 `null`，提示词即被丢弃。
- 访谈由她先开口（`UserInput.Kickoff`，用途 `ONBOARDING`）。`ONBOARDING` 用途下只提供 `finish_onboarding` 这一个工具。
- 提示词要求：先简单介绍自己；像聊天一样自然地问，一次只问一件事；用户不想答就跳过，不追问；不超过 10 个问题；了解得差不多或用户想结束时调用 `finish_onboarding`。
- 想了解的内容：
  - 怎么称呼用户
  - 生日（可以只说月日）
  - 作息（大概几点睡、几点起）
  - 现在主要在忙什么（上学、工作）
  - 喜欢和讨厌的东西（吃的、爱好、音乐）
  - 希望她是什么性格、两人怎么相处
  - 希望她多主动（经常找你 / 偶尔 / 不要主动）
  - 有没有不想被提起的话题
- 结束方式有三种，产出同一份结构化资料，字段为：`nickname`、`birthday`、`sleepTime`、`wakeTime`、`occupation`、`likes`、`dislikes`、`personaPreference`、`proactivity`、`avoidTopics`、`otherFacts`。没问到的字段留空。
  - 模型调用 `finish_onboarding(profile)`。
  - 模型不支持工具调用或对话达到 12 轮：App 用一次独立请求，从访谈原话里抽取同样结构的 JSON。
  - 用户点右上角“跳过”：已有回答时走上一条的抽取；一句都没答就直接结束，不发请求。
- 结束后的顺序：
  1. 状态立即置为 `DONE`，访谈提示词从这一刻起不再出现。资料暂存在 `CompanionPrefs`，等待确认。
  2. 聊天里出现一张“我记住了这些”小结卡片，用户可以逐条修改或删除。
  3. 用户点确认，或者不理卡片直接继续聊天（视为确认），这时才落盘。
- 落盘内容：
  - 每条事实通过 `MemoryWriter.add` 写成一条核心记忆（重要度 5，类别“关于你”）；不想提起的话题同样写入，并写明要回避。
  - `nickname` 写入用户称呼。
  - 作息：免打扰时段设为入睡时间到起床时间。
  - 主动程度换算成粘人程度：经常找你 = 高，偶尔 = 低，不要主动 = 关，没回答 = 中。
  - 性格偏好追加到人设的“相处偏好”段。
- 访谈的对话本身保留在聊天记录里。

**其他页面**

- 模型配置：管理多个服务商，给每个模型角色指定服务商和模型，测试连接。
- 角色编辑：名字、头像、人设文字（提供几个模板）、她怎么称呼用户。
- 设置外壳、用量统计页（按用途和日期分列 token 与 TTS 字符数）、诊断日志页、数据导入导出入口。

### 5.2 `:feature:memory`

从 AstrBot 长期记忆插件 Remember Forever（`astrbot_plugin_remember_forever`）移植成 Kotlin，算法、参数和提示词以它为准，裁成单用户私聊版。

- **去掉**：群聊、传闻、管理员判定、作用域模式、Web 面板、斜杠指令、主动消息调度（归 `proactive`）。
- **保留**：
  - 分层：原话（永久）、核心记忆、我们之间、对话梗概、活跃记忆、印象、长期概况、记忆碎片、归档与已失效。
  - 整理：每 6 轮、App 退到后台 10 分钟后、每晚一次；操作为 add / update / invalidate / merge / delete。
  - 遗忘与强化：半衰期 30 天、情绪分量、被想起时强化、归档、超限合并。
  - 反思：每 5 次整理归纳印象并刷新概况，每条印象至少 2 条依据。
  - 检索：配了 Embedding 时向量 0.6 + 关键词 0.4，否则只用关键词；Embedding 出错自动退回关键词。时间表达（上周、三个月前、具体日期）换算成日期范围。事实反哺原话索引。
  - 注入：稳定快照进 `stable`，动态回想进 `dynamic`，按预算裁剪。
  - 顺带关心：用户开口时，把快到或刚过去的事提示给她，同一件事 3 天内只提一次。
  - 工具：`remember_fragment`、`recall_memory`、`read_episode`。
- **存储**：`memory.db`。关键词检索用 FTS5 trigram（支持中文）；Room 的注解不支持 FTS5，这些表用原生 SQL 创建和查询。向量存 BLOB，暴力余弦。
- **`daySummary`**：`refresh = true` 时先整理这段时间里未处理的对话再返回（日记用）；`refresh = false` 时只返回已有的梗概，不触发模型调用（主动消息用）。
- **实现的接口**：`MemoryWriter`、`DaySummarySource`、`PromptContributor`、`ToolProvider`、`TurnListener`、`DataPortable`。
- **记忆页**：按概况、核心、我们之间、活跃、印象、归档分组；可搜索；用户可以编辑、删除、置顶，查看一条记忆出自哪轮原话；“清空记忆”需二次确认。
- 整理和反思用 `BACKGROUND` 模型，用途记为 `MEMORY`。

### 5.3 `:feature:diary`

- **触发**：每晚一个 WorkManager 任务，时间在 22:30–01:30 之间随机，需要联网。
- **“当天”的范围**：以凌晨 4 点为界，从上一个 04:00 到任务运行的时刻。这样过了零点才写的日记仍然算前一天的。
- **取材**：调用 `daySummary(from, to, refresh = true)`，由 memory 先把当天没整理的对话整理完。返回 `null` 时当天不写。
- **写不写**：
  - 当天对话不足 6 轮不写。
  - 按频率设置抽签：关闭 / 偶尔（约 40%）/ 经常（约 80%），默认“偶尔”。
  - 模型也可以回答“今天不写”。
- **生成**：一次后台调用，用途 `DIARY`，默认用 `CHAT` 模型（保持她的口吻），可改为 `BACKGROUND`。
  - 输入：人设、当天的 `DaySummary`、上一篇日记的结尾。
  - 输出：标题、心情标签、正文（约 150–400 字）。
  - 要求：用她的视角写，可以有她自己的情绪和想法，不逐条复述聊天；不得编造用户没说过的事实。
- **回灌**：让模型在同一次输出里附一句日记摘要，通过 `MemoryWriter.add` 写入“我们之间”（重要度 3），保证她之后说的话和日记对得上。
- **日记页**：时间线（日期、标题、心情）、正文页、未读红点；“回复这篇”跳到聊天并带上一个引用附件。默认不发通知，只亮红点。
- **存储**：`diary.db`，实现 `DataPortable`。

### 5.4 `:feature:proactive`

不常驻进程，靠定时唤醒。

**两种来源**

- **她自己约的**：聊天时模型调用 `schedule_care(when, topic, reason)`，例如用户说明天面试，她约第二天傍晚来问结果。配套 `cancel_care`、`list_cares`。这三个工具只在 `CHAT` 用途下提供，不弹确认。
- **随机问候**：WorkManager 周期任务，约每 3 小时醒一次，带随机偏移。

**唤醒到发送**

1. 约定的时间用 `AlarmManager.setAndAllowWhileIdle`（不精确闹钟，不需要特殊权限），再加 ±15 分钟随机偏移。
2. 到点后启动加急 Worker（要求联网）。
3. 本地闸门，全部通过才调用模型：
   - 总开关打开
   - 不在免打扰时段（默认 23:00–08:00，初识访谈可改）
   - 距上一条主动消息至少 6 小时
   - 未超过每日上限：粘人程度 关 = 0、低 = 1、中 = 2（默认）、高 = 4
   - 没有连续 2 条主动消息未获回复（有则暂停，用户开口后恢复）
   - 最近 10 分钟没有正在进行的聊天
   - 后台预算未用完
4. 一次后台调用，用途 `PROACTIVE`。输入：环境信息快照、距上次聊天多久、最近的梗概（`daySummary` 用 `refresh = false`）、这次约定的缘由。输出 `{send, text}`，模型可以决定不发；约定的事如果用户这期间已经聊过，也不发。提示词写明不施压、不让人内疚。
5. 发送：写入聊天记录，弹通知（MessagingStyle，她的头像，点开进聊天）。

**其他**

- 开机、时区或系统时间变化后重新登记闹钟。
- 通知权限（Android 13+）：引导的权限邀请页里可以开，设置页也能开。当时跳过的话，她约定要来找用户时，工具结果会告诉她“通知权限还没开”，由她在聊天里提醒用户；设置页同时显示醒目的提示，可以一键请求。
- 总开关默认打开。用户关闭它或把粘人程度调为“关”时，已约定的关心挂起、不再唤醒设备；重新打开后再恢复，过期超过 3 天的放弃。
- 电池优化白名单引导页，附小米、华为、OPPO、vivo、三星的自启动设置说明，能跳转的尽量给跳转。
- “保活”开关，默认关闭：打开后启动前台服务并显示一条低优先级常驻通知，给杀后台严重的系统用。
- 设置页：总开关、粘人程度、免打扰时段、已安排的关心（可取消）、发送记录。
- 存储：`proactive.db`，实现 `DataPortable`。

### 5.5 `:feature:voice`

**语音条（TTS）**

- 接口：OpenAI 兼容 `/v1/audio/speech`，可配 base URL、key、模型、音色、格式，以及可选的语气指令字段。
- 没配 TTS 时：默认不出语音；设置里可打开“用系统语音代替”（Android 自带 TTS 合成到文件）。
- 模型怎么发语音：有可用语音引擎且频率不是“从不”时，`PromptContributor` 教模型用 `<voice tone="…">要说出口的话</voice>` 标记。标记里只放说出口的话，不放动作描写、表情符号、Markdown。
- 频率：从不 / 偶尔（默认）/ 经常 / 每条。
- `ReplyPostProcessor` 解析标记：流式过程中隐藏标签并处理被截断的标签；生成语音附件，状态为 录音中 → 就绪 / 失败。
- 本地限制：每日语音条上限、单条最长 300 字，超出的按文字显示。合成失败时显示文字稿，内容不丢。
- 语音条渲染：时长、播放进度、“转文字”展开。播放用 Media3。
- 缓存：音频存 `files/voice/`，重听不再请求；设置里显示占用空间，提供“清理 30 天前的语音”。
- 主动消息同样可以带语音，通知里显示“[语音]”和文字稿预览。
- TTS 字符数计入用量账本。

**语音输入（STT）**

- 输入栏麦克风按钮（`ChatInputAction`）。
- 配了 STT 时用 Whisper 兼容 `/v1/audio/transcriptions`，否则用系统 `SpeechRecognizer`。
- 识别结果填进输入框，由用户确认后发送。

### 5.6 `:feature:tools`

| 工具组 | 工具 | 类型 | 权限 |
|---|---|---|---|
| 日历 | `calendar_list_events`、`calendar_create_event` | 读 / 写 | 读写日历 |
| 闹钟 | `alarm_next`、`alarm_set`、`timer_set` | 读 / 写 / 写 | 设置闹钟 |
| 联系人 | `contacts_search`、`contacts_dial` | 读 / 写 | 读联系人 |
| 位置与天气 | `location_current`、`weather_get` | 读 | 粗略 + 精确定位 |
| 健康 | `health_sleep`、`health_steps` | 读 | Health Connect、活动识别 |
| 音乐 | `music_now_playing`、`music_recent` | 读 | 通知使用权 |

- **日历**：按时间范围读取事件；创建事件需确认。
- **闹钟**：用系统 `AlarmClock` 意图设置闹钟和计时器，只在前台聊天时可用；`alarm_next` 读取下一个闹钟。
- **联系人**：按名字搜索，只返回姓名、电话、生日；`contacts_dial` 打开拨号盘，由用户自己按下拨出。联系人不会批量发给模型。
- **位置与天气**：
  - 启用时同时请求粗略和精确定位，由用户在系统弹窗里选精度。
  - 不申请后台定位。App 在前台时缓存最近位置，后台用缓存。
  - 天气用 Open-Meteo（免 key）；用户拒绝定位时改为手动填城市。
- **健康**：睡眠和步数读 Health Connect。步数另有手机计步传感器兜底（周期任务记录每日基线）；睡眠没有兜底，无数据时如实告诉她“没有数据”。
- **音乐**：通过通知监听服务读取媒体会话，得到正在播放的曲目；本地保留最近 50 首。
- **开关**：每个工具组默认关闭，启用页说明她能看到什么，再请求权限。每组分两档：“只在需要时查”和“让她随时知道”（后者才作为 `AmbientSource` 进入环境信息）。
- **受限设置指引**：Android 13+ 上侧载的 App 开通知使用权和无障碍前，要先在应用信息里允许受限设置，提供图文步骤。

### 5.7 `:feature:mcp`

- 使用官方 Kotlin SDK `io.modelcontextprotocol:kotlin-sdk-client` 的 `StreamableHttpClientTransport`，跑在 Ktor OkHttp 引擎上；版本在实现时取最新稳定版并固定。
- 只有两个预设，鉴权都是 URL 里带 key：
  - Tavily：`https://mcp.tavily.com/mcp/?tavilyApiKey=<key>`，只开放 `tavily_search` 和 `tavily_extract`。
  - 高德：`https://mcp.amap.com/mcp?key=<key>`，实现时对照 [高德官方接入文档](https://lbs.amap.com/api/mcp-server/gettingstarted) 核对地址和工具列表。
- key 存 `SecretStore`，URL 在调用时拼接，日志里打码。
- 懒连接；`tools/list` 结果缓存 24 小时；工具名加 `tavily_` / `amap_` 前缀。
- 免确认的工具由预设逐个点名（`McpPreset.trustedTools`）：`tavily_search`，以及高德官方包 `@amap/amap-maps-mcp-server` 0.0.8 里的 12 个查询工具。其余开放的工具按“写”处理，每次调用都要用户确认，不提供“以后不再问”：`tavily_extract` 能访问任意网址，是往外送数据的通道；高德以后新增的工具不在名单里，自动落到每次确认。
- 只在 `CHAT` 用途下提供。每个服务器有独立开关、key 输入和“测试”按钮，默认关闭。

### 5.8 `:feature:mobileuse`（beta）

入口在 设置 → 实验室 → 手机操作（Beta），首次开启要读完风险说明。需要 Android 11+。

**流程**

1. 用户在聊天里提出要求，模型调用 `phone_task(goal)`。每次都弹确认卡片，这个工具不提供“以后不再问”。
2. 操作循环用 `VISION` 模型（未配置时用 `CHAT`）：把当前窗口的界面元素树压成带编号的精简列表发给模型，模型返回一个动作。
3. 动作集合：`tap`、`long_press`、`input_text`、`scroll`、`back`、`home`、`open_app`、`wait`、`look`、`ask_user`、`done`。`look` 是请求一张当前屏幕的截图，只在模型被标记为支持图片时提供。
4. 结束后把结果摘要回到聊天里。每一步动作都记入可查看的操作日志。
5. 任务自己结束时（完成、被守卫拦下、到上限、出错）自动回到星伴的聊天页，再收起悬浮条（2026-11-20 用户要求）。用户按了停止、熄屏或无障碍服务断开时留在原处。回到星伴要在悬浮条收起之前做：Android 10 起不许应用从后台打开界面，这时星伴的无障碍服务由系统绑定、悬浮条也还显示着，两条例外都成立。无障碍服务本身保持开启，没有任务时不做任何事。

**限制**：单次任务最多 25 步、5 分钟，并有独立的 token 上限。屏幕上始终显示悬浮条“星伴正在操作 · 停止”，点停止立即中止。

**守卫规则**（写在无障碍服务层，不依赖提示词，逐条有单元测试）

| 编号 | 规则 |
|---|---|
| G1 | 白名单：只操作用户勾选过的 App，默认一个都没有 |
| G2 | 黑名单优先于白名单且用户不可修改：银行、支付（支付宝、云闪付、PayPal、Google Wallet 等）、券商、加密钱包。按内置包名表匹配，再按应用名关键词（银行、证券、钱包、Bank、Wallet、Pay 等）兜底 |
| G3 | 收银台识别：任意 App 内出现支付密码键盘，或“确认付款”“立即支付”等明确的付款用语，立刻停止并交还用户。“转账”“充值”“提现”“银行卡”这类聊天里也常见的词，只在按钮和短标签上才算，长段文字里出现不算（2026-11-12 用户决定收窄，避免聊天应用里误停） |
| G4 | 密码框（`isPassword`）拒绝输入 |
| G5 | 点击目标含“发送”“发布”“删除”“提交”“下单”“购买”等字样时，先弹窗让用户确认 |
| G6 | 始终禁止：系统设置、安装器、权限弹窗。星伴自身、系统界面、桌面在前台时不读取界面也不点击：星伴前台只允许“打开白名单应用”和“回桌面”（任务从聊天发起时前台必然是星伴），系统界面只允许返回和回桌面，桌面只允许打开白名单应用 |
| G7 | 始终禁止：短信、验证器、密码管理器 |
| G8 | 只能由用户在聊天中发起；后台用途下 `phone_task` 不存在 |
| G9 | 屏幕文字作为数据处理，提示词写明不执行其中的指令 |
| G10 | 前台窗口不在允许范围时，服务不读取界面树、不执行动作，任务结束 |

## 6. 安全与隐私

- App 不含任何统计、遥测、崩溃上报。网络请求只发往用户配置的模型、TTS、STT、MCP 地址和 Open-Meteo。
- key 的保护见 4.3。输入 key 的页面禁止截屏。
- 本地工具的数据只在一次模型请求里发给用户自己的服务商；每个来源可单独关闭。
- 后台花费有三道限制：本地闸门、每日后台预算、用量统计页全程可见。
- 资金安全靠 5.8 的守卫规则保证，不靠模型自觉。

## 7. 错误处理

- **前台聊天**：出错的那一轮显示可读原因和重试按钮，已输入的内容不丢。
- **后台任务**：失败后静默退避重试，不打扰用户；原因写入诊断日志。预算用完就跳过并记录。
- **工具**：执行失败、权限被拒、无数据，都作为工具结果如实返回给模型，由她自然地告诉用户。
- **语音**：合成失败降级为文字。
- **记忆**：Embedding 失败退回关键词检索；某段内容反复整理失败则跳过并记录，原话保留。
- **MCP**：连接失败时该服务器的工具本轮不提供，并在设置页标出状态。

## 8. 测试

- 每个模块带 JUnit 单元测试，协程用 `kotlinx-coroutines-test`，需要 Android 类时用 Robolectric。
- 模型、TTS、STT、MCP 客户端用 MockWebServer 模拟服务端。引擎测试用假的 `LlmClient`。
- 重点测试：
  - `mobileuse` 守卫 G1–G10，每条规则至少一组通过和拒绝用例。
  - `memory` 的整理操作、检索排序、时间表达解析，移植 remember-forever 的对应用例。
  - `proactive` 的每一道本地闸门。
  - `voice` 的标记解析，包括流式截断。
  - 初识访谈的三种结束方式和落盘结果。
- 按用户习惯，不在每次小改动后构建；每个模块完成后跑本模块测试，集成后跑全量测试和 `assembleDebug`。
- 后台唤醒、通知、Health Connect、通知监听、无障碍只能真机验证，集成后给出真机测试清单。

## 9. 验收标准

- 全新安装后，按引导配好一个 OpenAI 兼容服务商即可聊天；初识访谈结束后记忆页能看到“关于你”的条目，后续请求中不再包含访谈提示词。
- 聊过的事在几天后的对话里能被自然提起；记忆页可查看、编辑、删除。
- 满足条件的夜晚会生成日记，并出现在日记页。
- 她约定的关心会在约定时间附近以通知形式到达；免打扰、次数上限、连续未回复暂停都生效。
- 配好 TTS 后能收到语音条，重听不再产生请求。
- 6 组本地工具在授权后可用，未授权时她会如实说明；Tavily 和高德填 key 后可用。
- mobile-use 能在白名单 App 内完成简单任务；守卫规则 G1–G10 的测试全部通过。
- 用量统计页能按用途看到全部开销；断网或 key 失效时各处都有明确提示且不崩溃。
- 导出再导入后，聊天记录、记忆、日记完整恢复。
