package org.xmyun.starmate.feature.tools.contacts

import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.R
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.JsonArgs.string
import org.xmyun.starmate.feature.tools.common.Schema
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.WriteOutcome
import org.xmyun.starmate.feature.tools.common.error
import org.xmyun.starmate.feature.tools.common.ok

/** 联系人组：没有环境信息，不会批量发给模型。 */
class ContactsTools @Inject constructor(
    private val gate: GroupGate,
    private val gateway: ContactsGateway,
    private val appState: AppState,
    private val text: ToolText,
) : ToolProvider {

    val search: Tool = Search()
    val dial: Tool = Dial()

    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && gate.usable(ToolGroup.CONTACTS)) listOf(search, dial) else emptyList()

    internal fun line(c: ContactInfo): String {
        val parts = mutableListOf<String>()
        parts += if (c.phones.isEmpty()) {
            if (text.chinese) "没有电话" else "no phone number"
        } else {
            (if (text.chinese) "电话 " else "phone ") + c.phones.take(MAX_PHONES).joinToString(if (text.chinese) "、" else ", ")
        }
        c.birthday?.let { parts += (if (text.chinese) "生日 " else "birthday ") + it }
        return if (text.chinese) "- ${c.name}：" + parts.joinToString("；") else "- ${c.name}: " + parts.joinToString("; ")
    }

    private inner class Search : Tool {
        override val name = "contacts_search"
        override val description =
            "Look up people in the user's phone contacts by name. Use it when the user mentions someone and you " +
                "need their phone number or birthday, e.g. to remind them of a birthday or before offering to call. " +
                "Returns at most 5 matches, each with only name, phone numbers and birthday."
        override val parametersSchema = Schema.obj(
            "name" to Schema.string("Full or partial name as saved in contacts, e.g. 妈妈 or Alex"),
            required = listOf("name"),
        )
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val name = args.string("name")?.take(50) ?: return text.error("缺少 name", "`name` is missing")
            val found = gateway.search(name, MAX_RESULTS).take(MAX_RESULTS)
            if (found.isEmpty()) return text.error("联系人里没有找到“$name”", "No contact matches \"$name\"")
            return ToolResult(found.joinToString("\n") { line(it) })
        }
    }

    private inner class Dial : Tool {
        override val name = "contacts_dial"
        override val description =
            "Open the phone's dialer with a number filled in. It does not place the call: the user presses call " +
                "themselves. Use it only when the user wants to call someone. The user will be asked to confirm, and " +
                "it only works while the app is open. Get the number from contacts_search or from the user."
        override val parametersSchema = Schema.obj(
            "number" to Schema.string("Phone number, digits with optional + - space ( )"),
            required = listOf("number"),
        )
        override val risk = ToolRisk.WRITE

        override fun describeCall(args: JsonObject): String =
            text.get(R.string.tools_contacts_describe_dial, args.string("number").orEmpty())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val number = args.string("number") ?: return text.error("缺少 number", "`number` is missing")
            if (!NUMBER.matches(number) || number.count(Char::isDigit) < 3) {
                return text.error("号码格式不对", "That is not a valid phone number")
            }
            if (!appState.isForeground.value) {
                return text.error("星伴现在不在前台，不能打开拨号盘", "Starmate is not in the foreground, so the dialer cannot be opened")
            }
            return when (gateway.dial(number)) {
                WriteOutcome.OK -> text.ok("已打开拨号盘并填好 $number，等用户自己按拨出", "Dialer opened with $number; the user will press call")
                WriteOutcome.NO_APP -> text.error("这台手机上没有拨号应用", "There is no dialer app on this phone")
                else -> text.error("没能打开拨号盘", "The dialer could not be opened")
            }
        }
    }

    companion object {
        const val MAX_RESULTS = 5
        const val MAX_PHONES = 3
        // 不接受 * 和 #：它们能拼出呼叫转移之类的运营商指令码（例如 **21*号码#），不该由模型填进拨号盘
        private val NUMBER = Regex("^[0-9+()\\- ]{3,30}$")
    }
}
