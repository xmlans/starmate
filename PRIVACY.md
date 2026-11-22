# 星伴隐私政策

生效日期：2026 年 11 月 21 日

星伴（Starmate）是一款 Android 上的 AI 伴侣应用，由开发者“兰酱qwq”开发。本政策适用于星伴的所有版本（正式版、Beta 版和 Google Play 版）。

开发者：兰酱qwq　联系方式：support@xmyun.org

## 一句话说明

星伴没有服务器。开发者不收集、不上传、不出售你的任何数据，应用里也没有统计分析、广告或崩溃上报工具。你的数据存在你的手机上，只会发往你自己选择的服务。

## 数据存在哪里

- 聊天记录、记忆、日记、语音文件和设置都只存在你的手机上。
- 你填写的 API key 用 Android Keystore 加密保存，不会写进日志。
- 应用数据不进入系统云备份，也不参与换机迁移。需要转移时，用应用里的“导入导出”，导出的备份文件不含 API key。
- 卸载应用或在系统设置里清除应用数据，就会删除以上全部内容。

## 数据会发往哪里

只有在功能需要时，由你的手机直接发出：

1. **你配置的 AI 模型服务商**：为了生成回复，聊天内容、相关的记忆、你授权的工具返回的结果（例如天气、日程）会作为请求内容发给你选择的服务商。这些数据受该服务商自己的隐私政策约束。
2. **你配置的语音服务**（可选）：要朗读的文字，或你语音输入的录音。
3. **Open-Meteo**（天气）：约 1 公里精度的坐标，或你输入的城市名。
4. **Tavily 和高德地图**（可选，需要你填写 key 并开启）：搜索词、地点或路线。
5. **举报给开发者**（可选）：只有你在举报时选择“同时发给开发者”，才会打开你的邮件应用，由你确认内容后自己发送。

除此之外，星伴不会连接任何其他地址。

## 权限及用途

所有权限都是可选的，不授权只是对应的功能不能用。你可以随时在系统设置里撤销。

| 权限 | 用途 |
|---|---|
| 位置（大致或精确） | 查询当地天气、回答“我在哪”。只在你使用时读取，不在后台持续定位 |
| 通讯录 | 按名字查找联系人的电话和生日；打开拨号盘时填号码（由你自己按拨出） |
| 日历 | 读取日程；在你确认后新建日程 |
| 闹钟 | 在你确认后设置闹钟 |
| 麦克风 | 语音输入 |
| 通知 | 显示她主动发来的消息 |
| 身体活动 | 手机没有健康数据时，用计步器估算步数 |
| Health Connect：睡眠、步数 | 回答你关于睡眠和步数的问题（只读，不写入） |
| 通知使用权 | 只读取“正在播放”的歌曲信息，不读取任何通知内容 |
| 开机启动 | 手机重启后恢复她约好来找你的时间 |

### Health Connect 数据

- 星伴只读取睡眠时长和步数，不写入任何健康数据。
- 这些数据只在你和她聊到相关话题时使用，作为请求内容发给你配置的 AI 模型服务商，不会发往其他任何地方。
- 不用于广告，不出售，不用于信用评估或保险定价，也不会让任何人读取。
- 星伴对 Health Connect 数据的使用遵守 Health Connect 权限政策，包括其中的“有限使用”要求。

### Beta 版的手机操作

只有 Beta 版包含“手机操作”，Google Play 版没有。开启后，星伴通过无障碍服务，在你确认的任务里读取当前屏幕的文字并替你点按，读到的内容会发给你配置的模型。它只在你允许的应用里工作；银行、支付类应用一律禁止，遇到付款页面会立即停止。

## AI 生成的内容

她的回复由你选择的第三方模型生成，可能不准确，也可能不恰当。在 Google Play 版里，你可以长按她的回复选择“举报”：这条回复会从聊天和她的记忆里删除，你也可以选择把它通过邮件发给开发者。

## 未成年人

星伴只面向 18 岁及以上的用户，首次使用时需要确认年满 18 岁。

## 政策变更

本政策更新时会发布在这个页面，并修改上方的生效日期。

---

# Starmate Privacy Policy

Effective date: November 21, 2026

Starmate is an AI companion app for Android, developed by 兰酱qwq. This policy covers every edition of Starmate (stable, beta and the Google Play edition).

Developer: 兰酱qwq · Contact: support@xmyun.org

## In short

Starmate has no server. The developer does not collect, upload or sell any of your data, and the app contains no analytics, advertising or crash-reporting tools. Your data stays on your phone and is sent only to services you choose.

## Where your data is stored

- Chats, memories, diary entries, voice files and settings are stored only on your phone.
- API keys you enter are encrypted with the Android Keystore and never written to logs.
- App data is excluded from system cloud backup and device-to-device transfer. To move it, use Import / Export in the app; exported backups do not contain API keys.
- Uninstalling the app, or clearing its data in system settings, deletes all of the above.

## Where your data is sent

Only when a feature needs it, directly from your phone:

1. **The AI model provider you configure**: to generate replies, your messages, relevant memories and results from tools you have allowed (such as weather or calendar events) are sent to the provider you chose, under that provider's own privacy policy.
2. **The speech service you configure** (optional): text to be read aloud, or your voice input.
3. **Open-Meteo** (weather): coordinates rounded to about 1 km, or a city name you type.
4. **Tavily and AMap** (optional, only with your own key and switched on): search terms, places or routes.
5. **Reports to the developer** (optional): only if you choose "also send it to the developer" when reporting, your email app opens and you send the message yourself after reviewing it.

Starmate connects to nothing else.

## Permissions and why they are used

Every permission is optional; without it only the related feature is unavailable. You can revoke any of them in system settings at any time.

| Permission | Used for |
|---|---|
| Location (approximate or precise) | Local weather and "where am I". Read only when you use it, never tracked in the background |
| Contacts | Looking up a contact's phone number and birthday by name; filling in the dialer (you press call yourself) |
| Calendar | Reading events; creating an event after you confirm |
| Alarms | Setting an alarm after you confirm |
| Microphone | Voice input |
| Notifications | Showing messages she sends on her own |
| Physical activity | Estimating steps with the phone's step counter when no health data is available |
| Health Connect: sleep, steps | Answering your questions about sleep and steps (read only, never written) |
| Notification access | Reading only the "now playing" song information, never any notification content |
| Run at startup | Restoring the check-ins she arranged after the phone restarts |

### Health Connect data

- Starmate reads only sleep duration and step counts and writes no health data.
- This data is used only when you talk with her about it, and is sent only to the AI model provider you configure, as part of that request.
- It is not used for advertising, not sold, not used for credit or insurance decisions, and not made available to anyone else.
- Starmate's use of Health Connect data complies with the Health Connect Permissions policy, including the Limited Use requirements.

### Phone operation (beta only)

Only the beta edition includes phone operation; the Google Play edition does not. When you turn it on, Starmate uses an accessibility service to read the text on screen and tap for you during a task you confirmed, and sends what it reads to the model you configure. It works only in apps you allow; banking and payment apps are always blocked, and it stops immediately on payment screens.

## AI-generated content

Her replies are generated by a third-party model you choose and may be inaccurate or inappropriate. In the Google Play edition you can long-press a reply and choose Report: the reply is removed from the chat and from her memory, and you can choose to email it to the developer.

## Children

Starmate is intended only for users 18 and older, and asks you to confirm your age on first use.

## Changes

Updates to this policy will be published on this page with a new effective date.
