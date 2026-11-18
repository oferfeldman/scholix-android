package com.feldman.scholix.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHtmlTest {
    @Test fun plainTextDecodesEscapedSubjects() {
        assertEquals("A quick reminder for tomorrow's lesson", MessageHtml.plainText("A quick reminder for tomorrow&#39;s lesson"))
        assertEquals("Q&A", MessageHtml.plainText("Q&amp;amp;A"))
    }

    @Test fun liftsQuotedHeaderAndDividerOutOfTheBody() {
        val html = "<p><br></p><p>&nbsp;</p><hr><div dir=\"rtl\">מאת: מיציקוב ניקול<br>אל: בהודעה זו רשימת הנמענים הוסתרה<br>10:02 10/11/2026<br>The Last Leaf Make-up<br></div><p><br></p><p>Dear Students,</p><p>Thursday's class</p>"
        val display = MessageHtml.splitQuotedHeader(html)
        assertEquals(QuotedHeader("מיציקוב ניקול", "10:02 10/11/2026", "The Last Leaf Make-up"), display.quoted)
        assertFalse(display.html.contains("<hr"))
        assertFalse(display.html.contains("מאת"))
        assertFalse(display.html.contains("Make-up"))
        assertTrue(display.html.startsWith("<p>Dear Students,</p>"))
    }

    @Test fun handlesHeaderLinesAsSeparateParagraphs() {
        val html = "<hr><p><b>מאת:</b> Dana Levi</p><p>אל: Class 5</p><p>10/11/2026 10:02</p><p>Trip</p><p>Body text</p>"
        val display = MessageHtml.splitQuotedHeader(html)
        assertEquals(QuotedHeader("Dana Levi", "10/11/2026 10:02", "Trip"), display.quoted)
        assertEquals("<p>Body text</p>", display.html)
    }

    @Test fun keepsRepliesWithTextBeforeTheDivider() {
        val html = "<p>See below</p><hr><div>מאת: X<br>אל: Y<br>10/11/2026<br>Subject</div><p>Body</p>"
        val display = MessageHtml.splitQuotedHeader(html)
        assertNull(display.quoted)
        assertEquals(html, display.html)
    }

    @Test fun keepsOrdinaryDividers() {
        val html = "<hr><p>Section two</p>"
        assertNull(MessageHtml.splitQuotedHeader(html).quoted)
    }
}
