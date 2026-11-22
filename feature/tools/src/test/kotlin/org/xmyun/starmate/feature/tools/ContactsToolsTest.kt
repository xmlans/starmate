package org.xmyun.starmate.feature.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.contacts.ContactInfo
import org.xmyun.starmate.feature.tools.contacts.ContactsTools

@RunWith(RobolectricTestRunner::class)
class ContactsToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val gateway = FakeContactsGateway()
    private val app = FakeAppState(true)
    private lateinit var tools: ContactsTools

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tools = ContactsTools(GroupGate(newPrefs(scope, tmp.root), FakePermissions()), gateway, app, FakeToolText())
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun atMostFiveResults() = runTest {
        gateway.contacts = (1..8).map { ContactInfo("小王$it", listOf("1380000000$it"), null) }
        val r = tools.search.execute(args("name" to "小王"), CHAT)
        assertFalse(r.isError)
        assertEquals(5, r.content.lines().size)
    }

    @Test
    fun onlyNamePhoneBirthday() = runTest {
        // 数据结构上就只有这三个字段
        val fields = ContactInfo::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertEquals(setOf("name", "phones", "birthday"), fields)

        gateway.contacts = listOf(ContactInfo("妈妈", listOf("138 0000 0000", "010-12345678"), "1970-05-01"))
        val r = tools.search.execute(args("name" to "妈妈"), CHAT)
        assertEquals("- 妈妈：电话 138 0000 0000、010-12345678；生日 1970-05-01", r.content)
    }

    @Test
    fun noMatchIsError() = runTest {
        val r = tools.search.execute(args("name" to "不存在"), CHAT)
        assertTrue(r.isError)
    }

    @Test
    fun dialOnlyInForeground() = runTest {
        app.flow.value = false
        val r = tools.dial.execute(args("number" to "13800000000"), CHAT)
        assertTrue(r.isError)
        assertTrue(gateway.dialed.isEmpty())
        app.flow.value = true
        assertFalse(tools.dial.execute(args("number" to "13800000000"), CHAT).isError)
        assertEquals(listOf("13800000000"), gateway.dialed)
    }

    @Test
    fun dialRejectsGarbage() = runTest {
        assertTrue(tools.dial.execute(args("number" to "call mom"), CHAT).isError)
    }

    @Test
    fun dialRejectsCarrierControlCodes() = runTest {
        // 呼叫转移、查串号这类指令码带 * 和 #，不能由模型填进拨号盘
        assertTrue(tools.dial.execute(args("number" to "**21*13800000000#"), CHAT).isError)
        assertTrue(tools.dial.execute(args("number" to "*#06#"), CHAT).isError)
        assertTrue(gateway.dialed.isEmpty())
    }

    @Test
    fun dialAcceptsOrdinaryFormattedNumbers() = runTest {
        assertFalse(tools.dial.execute(args("number" to "+86 138-0000-0000"), CHAT).isError)
        assertFalse(tools.dial.execute(args("number" to "(010) 1234 5678"), CHAT).isError)
        assertEquals(listOf("+86 138-0000-0000", "(010) 1234 5678"), gateway.dialed)
    }
}
