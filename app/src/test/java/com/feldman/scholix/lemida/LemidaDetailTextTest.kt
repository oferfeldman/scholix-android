package com.feldman.scholix.lemida

import org.junit.Assert.assertEquals
import org.junit.Test

class LemidaDetailTextTest {
    @Test fun attachmentNamesAndTheirUploadTimesAreNotRepeated() {
        assertEquals("", LemidaDetailText.withoutAttachments("Sheet.pdf\n17/11/2026, 19:54", listOf("Sheet.pdf")))
        assertEquals("Submit by Sunday.", LemidaDetailText.withoutAttachments("Submit by Sunday.\nSheet.pdf\n1/2/2026, 9:05", listOf("Sheet.pdf")))
        // A date that isn't an attachment's upload time stays.
        assertEquals("Due\n17/11/2026, 19:54", LemidaDetailText.withoutAttachments("Due\n17/11/2026, 19:54", listOf("Sheet.pdf")))
        assertEquals("Read Sheet.pdf first", LemidaDetailText.withoutAttachments("Read Sheet.pdf first", listOf("Sheet.pdf")))
    }
}
