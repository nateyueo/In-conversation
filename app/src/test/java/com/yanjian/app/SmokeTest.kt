package com.yanjian.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun modesHaveDistinctValues() {
        assertEquals("WORD", StudyMode.WORD.name)
        assertEquals("SENTENCE", StudyMode.SENTENCE.name)
    }
}
