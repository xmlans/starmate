package org.xmyun.starmate.feature.proactive.work

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionParserTest {

    @Test
    fun sendTrue_withText() {
        assertEquals(Decision.Send("面试怎么样啦？"), DecisionParser.parse("""{"send": true, "text": "面试怎么样啦？"}"""))
    }

    @Test
    fun sendTrue_insideCodeFence() {
        val raw = "```json\n{\"send\": true, \"text\": \" hi \"}\n```"
        assertEquals(Decision.Send("hi"), DecisionParser.parse(raw))
    }

    @Test
    fun sendFalse() {
        assertEquals(Decision.Skip, DecisionParser.parse("""{"send": false}"""))
        assertEquals(Decision.Skip, DecisionParser.parse("""{"send": false, "text": "ignored"}"""))
    }

    @Test
    fun missingFields_areInvalid() {
        assertEquals(Decision.Invalid, DecisionParser.parse("""{"text": "hi"}"""))
        assertEquals(Decision.Invalid, DecisionParser.parse("""{"send": true}"""))
        assertEquals(Decision.Invalid, DecisionParser.parse("""{"send": true, "text": "   "}"""))
        assertEquals(Decision.Invalid, DecisionParser.parse("""{"send": "maybe", "text": "hi"}"""))
    }

    @Test
    fun notJson_isInvalid() {
        assertEquals(Decision.Invalid, DecisionParser.parse("Sure! I'd love to say hi."))
        assertEquals(Decision.Invalid, DecisionParser.parse(""))
        assertEquals(Decision.Invalid, DecisionParser.parse("{send: true, text: }"))
        assertEquals(Decision.Invalid, DecisionParser.parse("[1, 2, 3]"))
    }
}
