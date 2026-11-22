package org.xmyun.starmate.feature.voice.tag

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTagParserTest {

    private fun transcripts(raw: String) = VoiceTagParser.parse(raw).segments.map { it.transcript }

    // ---- 设计文档里的用例 ----

    @Test
    fun noTagsReturnedUnchanged() {
        val samples = listOf("", "你好呀", "  前后有空格  ", "第一行\n\n\n\n第二行", "a > b and c", "1 < 2 是对的")
        for (s in samples) {
            assertEquals(s, VoiceTagParser.preview(s))
            val parsed = VoiceTagParser.parse(s)
            assertEquals(s, parsed.text)
            assertTrue(parsed.segments.isEmpty())
            assertFalse(parsed.changed)
        }
    }

    @Test
    fun basicSegmentRemovedFromText() {
        val parsed = VoiceTagParser.parse("你好<voice>想你了</voice>晚安")
        assertEquals("你好晚安", parsed.text)
        assertEquals(listOf(VoiceSegment("想你了", null)), parsed.segments)
        assertEquals("你好晚安", VoiceTagParser.preview("你好<voice>想你了</voice>晚安"))
    }

    @Test
    fun toneAttributeRead() {
        assertEquals("温柔", VoiceTagParser.parse("<voice tone=\"温柔\">晚安</voice>").segments.single().tone)
        assertEquals("soft", VoiceTagParser.parse("<voice tone='soft'>night</voice>").segments.single().tone)
        assertEquals("开心", VoiceTagParser.parse("<voice tone=“开心”>早</voice>").segments.single().tone)
        assertEquals("sleepy", VoiceTagParser.parse("<voice tone=sleepy>night</voice>").segments.single().tone)
        assertEquals("温柔", VoiceTagParser.parse("<voice  tone = \"温柔\" >晚安</voice>").segments.single().tone)
        assertEquals("轻声", VoiceTagParser.parse("<voice speed=\"1\" tone=\"轻声\">嗯</voice>").segments.single().tone)
        assertEquals("soft, a little sleepy", VoiceTagParser.parse("<voice tone=\"soft, a little sleepy\">x</voice>").segments.single().tone)
        assertNull(VoiceTagParser.parse("<voice tone=\"\">x</voice>").segments.single().tone)
    }

    @Test
    fun twoSegmentsKeepOrder() {
        val parsed = VoiceTagParser.parse("开头<voice>第一段</voice>中间<voice tone=\"小声\">第二段</voice>结尾")
        assertEquals("开头中间结尾", parsed.text)
        assertEquals(listOf(VoiceSegment("第一段", null), VoiceSegment("第二段", "小声")), parsed.segments)
        val kinds = parsed.parts.map { it::class.simpleName }
        assertEquals(listOf("Text", "Voice", "Text", "Voice", "Text"), kinds)
    }

    @Test
    fun previewHidesTruncatedTags() {
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice>我"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voi"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice ton"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice tone="))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice tone=\"温"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice tone=\"温柔\""))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice tone=\"温柔\">我想"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice>我想你<"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice>我想你</voi"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice>我想你</voice"))
        assertEquals("在吗", VoiceTagParser.preview("在吗<voice>我想你</voice>"))
        assertEquals("在吗，", VoiceTagParser.preview("在吗<voice>我想你</voice>，"))
        assertEquals("在吗", VoiceTagParser.preview("在吗</voi"))
        assertEquals("在吗", VoiceTagParser.preview("在吗 <"))
    }

    @Test
    fun lessThanThatIsNotATagKept() {
        assertEquals("1 < 2 是对的", VoiceTagParser.parse("1 < 2 是对的").text)
        assertEquals("1 < 2 是对的", VoiceTagParser.preview("1 < 2 是对的"))
        assertEquals("看 <video> 和 <voiceover>", VoiceTagParser.parse("看 <video> 和 <voiceover>").text)
        assertEquals("看 <video> 和 <voiceover>", VoiceTagParser.preview("看 <video> 和 <voiceover>"))
        assertEquals("a<<b", VoiceTagParser.parse("a<<b").text)
        assertEquals("x <3 y", VoiceTagParser.preview("x <3 y"))
    }

    @Test
    fun unclosedOpenTagTreatedAsClosedInParse() {
        val parsed = VoiceTagParser.parse("好的<voice>晚安呀")
        assertEquals("好的", parsed.text)
        assertEquals(listOf("晚安呀"), parsed.segments.map { it.transcript })

        val cut = VoiceTagParser.parse("好的<voice tone=\"困\">晚安</voi")
        assertEquals("好的", cut.text)
        assertEquals(VoiceSegment("晚安", "困"), cut.segments.single())
    }

    // ---- 其他畸形输入 ----

    @Test
    fun strayCloseAndSelfClosingRemoved() {
        assertEquals("hi there", VoiceTagParser.parse("hi </voice> there").text)
        assertEquals("ab", VoiceTagParser.parse("a<voice/>b").text)
        assertEquals("ab", VoiceTagParser.parse("a<voice />b").text)
        assertEquals("ab", VoiceTagParser.preview("a</voice>b"))
        assertTrue(VoiceTagParser.parse("a<voice/>b").segments.isEmpty())
    }

    @Test
    fun caseInsensitiveTagName() {
        val parsed = VoiceTagParser.parse("<VOICE Tone=\"x\">hey</Voice>ok")
        assertEquals("ok", parsed.text)
        assertEquals(VoiceSegment("hey", "x"), parsed.segments.single())
    }

    @Test
    fun emptySegmentDropped() {
        val parsed = VoiceTagParser.parse("<voice>   </voice>好")
        assertEquals("好", parsed.text)
        assertTrue(parsed.segments.isEmpty())
        assertTrue(parsed.changed)
    }

    @Test
    fun voiceOnlyReplyHasEmptyText() {
        val parsed = VoiceTagParser.parse("<voice>晚安</voice>")
        assertEquals("", parsed.text)
        assertEquals(listOf("晚安"), transcripts("<voice>晚安</voice>"))
        assertEquals("", VoiceTagParser.preview("<voice>晚安</voice>"))
    }

    @Test
    fun extraBlankLinesCollapsed() {
        assertEquals("第一行\n\n第二行", VoiceTagParser.parse("第一行\n\n<voice>嗨</voice>\n\n第二行").text)
        assertEquals("第一行\n第二行", VoiceTagParser.parse("第一行\n<voice>嗨</voice>第二行").text)
        assertEquals("正文", VoiceTagParser.parse("<voice>嗨</voice>\n\n正文\n\n").text)
    }

    @Test
    fun spacesAroundRemovedTagCollapsed() {
        assertEquals("Hi there", VoiceTagParser.parse("Hi <voice>miss you</voice> there").text)
        assertEquals("Hi there", VoiceTagParser.preview("Hi <voice>miss you</voice> there"))
    }

    @Test
    fun transcriptCleaned() {
        assertEquals(listOf("晚安 早点睡"), transcripts("<voice>\n  晚安\n  早点睡 \n</voice>"))
        assertEquals(listOf("ab"), transcripts("<voice>a<voice>b</voice>"))
    }

    @Test
    fun unclosedQuoteStillEndsAtGreaterThan() {
        val parsed = VoiceTagParser.parse("好<voice tone=\"温柔>晚安</voice>")
        assertEquals("好", parsed.text)
        assertEquals(VoiceSegment("晚安", "温柔"), parsed.segments.single())
    }

    @Test
    fun renderRestoresSegmentsInPlace() {
        val parsed = VoiceTagParser.parse("你好<voice>想你了</voice>晚安")
        assertEquals("你好想你了晚安", VoiceTagParser.render(parsed.parts) { true })
        assertEquals("你好晚安", VoiceTagParser.render(parsed.parts) { false })

        val english = VoiceTagParser.parse("Okay.<voice>Good night</voice>See you")
        assertEquals("Okay. Good night See you", VoiceTagParser.render(english.parts) { true })

        val two = VoiceTagParser.parse("A<voice>一</voice>B<voice>二</voice>C")
        assertEquals("A一BC", VoiceTagParser.render(two.parts) { it == 0 })
        assertEquals("AB二C", VoiceTagParser.render(two.parts) { it == 1 })
    }

    @Test
    fun everyStreamingPrefixHidesTagsAndVoiceContent() {
        val full = "早上好呀<voice tone=\"开心\">起床啦，今天也要加油哦</voice>我给你列了早餐清单：\n1. 牛奶\n2. 面包" +
            "<voice>记得吃完</voice>"
        for (end in 0..full.length) {
            val shown = VoiceTagParser.preview(full.substring(0, end))
            assertFalse("prefix $end leaked '<': $shown", shown.contains('<'))
            assertFalse("prefix $end leaked voice text: $shown", shown.contains("起床") || shown.contains("记得"))
            assertTrue("prefix $end: $shown", "早上好呀我给你列了早餐清单：\n1. 牛奶\n2. 面包".startsWith(shown))
        }
    }

    @Test
    fun previewNeverShrinksTextBeforeATag() {
        // 标签之前已经显示的文字，在标签逐字到来时不应该消失
        val full = "我在<voice>嗯</voice>呢"
        for (end in 2..full.length) {
            assertTrue(VoiceTagParser.preview(full.substring(0, end)).startsWith("我在"))
        }
    }

    @Test
    fun randomInputsNeverThrowOrLeakCompleteTags() {
        val pieces = listOf(
            "<", ">", "/", "voice", "VOICE", "</", "<voice>", "</voice>", "<voice tone=\"", "\"", "'", "“", "”",
            "tone", "=", " ", "\n", "a", "中", "😊", "<voice/>", "</voice", "<v", "<voic", "\t", "<<", ">>",
        )
        val random = Random(42)
        fun junk() = buildString { repeat(random.nextInt(0, 12)) { append(pieces[random.nextInt(pieces.size)]) } }
        repeat(5000) {
            // 任意垃圾输入：不抛异常
            val s = junk() + junk()
            VoiceTagParser.preview(s)
            val parsed = VoiceTagParser.parse(s)
            VoiceTagParser.render(parsed.parts) { i -> i % 2 == 0 }
            assertTrue(parsed.segments.all { seg -> seg.transcript.isNotBlank() })

            // 夹在垃圾中间的一段完整语音：内容既不出现在流式预览里，也不出现在最终文字里
            val wrapped = junk() + "<voice>SECRET</voice>" + junk()
            for (end in 0..wrapped.length) {
                val shown = VoiceTagParser.preview(wrapped.substring(0, end))
                assertFalse("input=[$wrapped] end=$end shown=[$shown]", shown.contains("SECRET") || shown.contains("SECRE"))
            }
            assertFalse("input=[$wrapped]", VoiceTagParser.parse(wrapped).text.contains("SECRET"))
        }
    }

    @Test
    fun longInputIsFast() {
        val text = buildString {
            repeat(2000) { append("这是第 $it 句话，1 < 2。") }
            append("<voice>最后一句")
        }
        val start = System.nanoTime()
        repeat(50) { VoiceTagParser.preview(text) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("preview too slow: $ms ms for 50 calls", ms < 2000)
        assertFalse(VoiceTagParser.preview(text).contains("最后一句"))
    }
}
