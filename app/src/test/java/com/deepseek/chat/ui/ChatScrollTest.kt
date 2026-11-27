package com.deepseek.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollTest {

    @Test
    fun `bottom requires index 0 and offset 0`() {
        assertTrue(ChatScroll.atBottom(0, 0))
        assertFalse(ChatScroll.atBottom(0, 5))
        assertFalse(ChatScroll.atBottom(2, 0))
    }

    @Test
    fun `dragging immediately stops following`() {
        // 手指刚碰上去、还没松手：必须立刻不跟随（不能等 Stop）
        assertFalse(ChatScroll.nextFollowing(scrolling = true, atBottom = false))
        // 即使还在底部，拖动期间也不跟随，避免和手指抢
        assertFalse(ChatScroll.nextFollowing(scrolling = true, atBottom = true))
    }

    @Test
    fun `release only resumes following at the bottom`() {
        assertTrue(ChatScroll.nextFollowing(scrolling = false, atBottom = true))
        assertFalse(ChatScroll.nextFollowing(scrolling = false, atBottom = false))
    }
}
