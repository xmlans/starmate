package org.xmyun.starmate.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarInitialTest {
    @Test
    fun takesFirstCharacter() {
        assertEquals("小", avatarInitial("小星"))
        assertEquals("A", avatarInitial("  alice "))
    }

    @Test
    fun emptyNameShowsPlaceholder() {
        assertEquals("?", avatarInitial(""))
        assertEquals("?", avatarInitial("   "))
    }

    @Test
    fun doesNotSplitSurrogatePair() {
        assertEquals("🌸", avatarInitial("🌸Mia"))
    }
}
