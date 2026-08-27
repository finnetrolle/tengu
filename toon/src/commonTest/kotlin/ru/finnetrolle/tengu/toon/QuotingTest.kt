package ru.finnetrolle.tengu.toon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuotingTest {

    @Test
    fun cleanStringsStayBare() {
        assertEquals("hello", Quoting.render("hello", ','))
        assertEquals("30 of 847 total", Quoting.render("30 of 847 total", ','))
        assertEquals("with \"quote\"", Quoting.render("with \"quote\"", ','))
        assertFalse(Quoting.needsQuotes("with \"quote\"", ','))
    }

    @Test
    fun dirtyStringsGetQuoted() {
        assertEquals("\"a,b\"", Quoting.render("a,b", ','))
        assertEquals("\"42\"", Quoting.render("42", ','))
        assertEquals("\"true\"", Quoting.render("true", ','))
        assertEquals("\"null\"", Quoting.render("null", ','))
        assertEquals("\" leading\"", Quoting.render(" leading", ','))
        assertEquals("\"#hash\"", Quoting.render("#hash", ','))
        assertEquals("\"\"", Quoting.render("", ','))
    }

    @Test
    fun escapesInsideQuotedForm() {
        assertEquals("\"a\\nb\"", Quoting.render("a\nb", ','))
        assertEquals("\"a\\tb\"", Quoting.render("a\tb", ','))
        assertEquals("\"a\\\"b,\"", Quoting.render("a\"b,", ','))
    }

    @Test
    fun bareBackslashIsUnambiguousAndStaysBare() {
        // экранирование существует только внутри кавычек; голая форма читается буквально
        assertEquals("a\\b", Quoting.render("a\\b", ','))
    }

    @Test
    fun delimiterIsRespected() {
        // с другим разделителем запятая больше не требует кавычек, а точка с запятой требует
        assertEquals("a,b", Quoting.render("a,b", ';'))
        assertTrue(Quoting.needsQuotes("a;b", ';'))
    }
}
