package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextHelpersTest {

    @Test
    fun irreversibleConfirmMessage_returnsChineseCopy() {
        assertEquals(
            "删除会话？\n此操作不可撤销。",
            irreversibleConfirmMessage("删除会话？", useChinese = true)
        )
    }

    @Test
    fun irreversibleConfirmMessage_returnsEnglishCopy() {
        assertEquals(
            "Delete session?\nThis cannot be undone.",
            irreversibleConfirmMessage("Delete session?", useChinese = false)
        )
    }
}
