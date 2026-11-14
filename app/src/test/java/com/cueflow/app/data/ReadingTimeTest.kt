package com.cueflow.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTimeTest {
    @Test
    fun `empty text needs no time`() {
        assertEquals(0, ReadingTime.estimateMinutes(""))
        assertEquals(0, ReadingTime.estimateMinutes("   \n  "))
    }

    @Test
    fun `chinese characters are estimated at a comfortable pace`() {
        val text = "字".repeat(220)
        assertEquals(1, ReadingTime.estimateMinutes(text))
        assertEquals(2, ReadingTime.estimateMinutes("字".repeat(440)))
    }

    @Test
    fun `latin words are weighted and rounded up`() {
        val text = (1..137).joinToString(" ") { "word" }
        assertEquals(1, ReadingTime.estimateMinutes(text))
        assertEquals(3, ReadingTime.estimateMinutes((1..300).joinToString(" ") { "word" }))
    }

    @Test
    fun `mixed language counts both scripts`() {
        val units = ReadingTime.units("你好世界 hello world")
        assertEquals(4 + 2 * 1.6, units, 0.01)
    }

    @Test
    fun `character count is stable`() {
        assertEquals(6, ReadingTime.countCharacters("a中b文🎬"))
    }
}
