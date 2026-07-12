package com.ethran.notable.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryEscapeTest {
    @Test
    fun plainTextIsUntouched() {
        assertEquals("", escapeSqlLike(""))
        assertEquals("Meeting notes", escapeSqlLike("Meeting notes"))
    }

    @Test
    fun likeWildcardsAreEscaped() {
        assertEquals("100\\% done", escapeSqlLike("100% done"))
        assertEquals("a\\_b", escapeSqlLike("a_b"))
    }

    @Test
    fun escapeCharacterItselfIsEscapedFirst() {
        assertEquals("back\\\\slash", escapeSqlLike("back\\slash"))
        assertEquals("\\\\\\%", escapeSqlLike("\\%"))
    }
}
