package org.xmyun.starmate.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmyun.starmate.feature.tools.location.WmoCodes

class WmoCodesTest {

    @Test
    fun commonCodes() {
        assertEquals("晴", WmoCodes.describe(0, true))
        assertEquals("clear sky", WmoCodes.describe(0, false))
        assertEquals("阴", WmoCodes.describe(3, true))
        assertEquals("中雨", WmoCodes.describe(63, true))
        assertEquals("moderate rain", WmoCodes.describe(63, false))
        assertEquals("大雪", WmoCodes.describe(75, true))
        assertEquals("雷阵雨", WmoCodes.describe(95, true))
        assertEquals("thunderstorm", WmoCodes.describe(95, false))
    }

    @Test
    fun unknownCodeFallsBack() {
        assertEquals("未知天气", WmoCodes.describe(42, true))
        assertEquals("unknown weather", WmoCodes.describe(42, false))
        assertEquals("未知天气", WmoCodes.describe(null, true))
    }
}
