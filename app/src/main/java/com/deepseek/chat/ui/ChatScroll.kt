package com.deepseek.chat.ui

/** 滚动位置的纯逻辑（便于单测）：reverseLayout 下 index 0 + offset 0 才是真正贴底。 */
object ChatScroll {

    fun atBottom(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
        firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

    /**
     * 手指一碰就停止跟随（scrolling=true → 不跟随）；
     * 松手后只有真正贴底才恢复跟随，否则保持「不跟随」。
     */
    fun nextFollowing(scrolling: Boolean, atBottom: Boolean): Boolean = !scrolling && atBottom
}
