package org.xmyun.starmate.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun cjkCountsOneEach() = assertEquals(2, TokenEstimator.estimate("你好"))

    @Test
    fun latinCountsOnePerFourChars() = assertEquals(2, TokenEstimator.estimate("abcdefgh"))

    @Test
    fun mixedRoundsUp() = assertEquals(3, TokenEstimator.estimate("你好ab"))

    @Test
    fun emptyIsZero() = assertEquals(0, TokenEstimator.estimate(""))
}
