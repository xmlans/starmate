package org.xmyun.starmate.feature.mobileuse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.ScrollDirection

class ActionParserTest {

    private val parser = ActionParser(lookAvailable = true)

    private fun ok(json: String, p: ActionParser = parser): AgentAction {
        val r = p.parse(json)
        assertTrue("expected Ok for $json but was $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).action
    }

    private fun error(json: String, p: ActionParser = parser): String {
        val r = p.parse(json)
        assertTrue("expected Error for $json but was $r", r is ParseResult.Error)
        return (r as ParseResult.Error).message
    }

    @Test
    fun everyActionParses() {
        assertEquals(AgentAction.Tap(3, "open"), ok("""{"action":"tap","index":3,"why":"open"}"""))
        assertEquals(AgentAction.LongPress(2), ok("""{"action":"long_press","index":2}"""))
        assertEquals(AgentAction.InputText(1, "咖啡"), ok("""{"action":"input_text","index":1,"text":"咖啡"}"""))
        assertEquals(
            AgentAction.Scroll(5, ScrollDirection.DOWN),
            ok("""{"action":"scroll","index":5,"direction":"down"}"""),
        )
        assertEquals(AgentAction.Back(), ok("""{"action":"back"}"""))
        assertEquals(AgentAction.Home(), ok("""{"action":"home"}"""))
        assertEquals(AgentAction.OpenApp("com.example.notes"), ok("""{"action":"open_app","package":"com.example.notes"}"""))
        assertEquals(AgentAction.Wait(2), ok("""{"action":"wait","seconds":2}"""))
        assertEquals(AgentAction.Look(), ok("""{"action":"look"}"""))
        assertEquals(AgentAction.AskUser("发给小林吗？"), ok("""{"action":"ask_user","question":"发给小林吗？"}"""))
        assertEquals(AgentAction.Done("搞定了"), ok("""{"action":"done","summary":"搞定了"}"""))
    }

    @Test
    fun unknownActionIsAnError() {
        val msg = error("""{"action":"pay","index":1}""")
        assertTrue(msg.contains("Unknown action"))
    }

    @Test
    fun tapWithoutIndexIsAnError() {
        error("""{"action":"tap"}""")
        error("""{"action":"tap","index":-1}""")
        error("""{"action":"tap","index":1.5}""")
    }

    @Test
    fun waitOf6SecondsIsClampedTo5() {
        assertEquals(AgentAction.Wait(5), ok("""{"action":"wait","seconds":6}"""))
        assertEquals(AgentAction.Wait(1), ok("""{"action":"wait","seconds":0}"""))
    }

    @Test
    fun nonJsonIsAnError() {
        error("I will tap the search button now.")
        error("""{"action": "tap", "index": """)
        error("""["tap", 3]""")
    }

    @Test
    fun codeFenceAroundJsonIsTolerated() {
        assertEquals(AgentAction.Back(), ok("```json\n{\"action\": \"back\"}\n```"))
    }

    @Test
    fun lookIsUnknownWhenImagesAreNotSupported() {
        error("""{"action":"look"}""", ActionParser(lookAvailable = false))
    }

    @Test
    fun missingRequiredFieldsAreErrors() {
        error("""{"action":"input_text","index":1}""")
        error("""{"action":"scroll","index":1,"direction":"sideways"}""")
        error("""{"action":"open_app"}""")
        error("""{"action":"ask_user"}""")
        error("""{"action":"done","summary":"  "}""")
        error("""{"index":1}""")
    }

    @Test
    fun overlongInputIsRejected() {
        error("""{"action":"input_text","index":1,"text":"${"x".repeat(ActionParser.MAX_INPUT + 1)}"}""")
    }

    @Test
    fun numericStringIndexIsAccepted() {
        assertEquals(AgentAction.Tap(4), ok("""{"action":"tap","index":"4"}"""))
    }
}
