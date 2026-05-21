package com.eratverbum.stepbible

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseReferenceTest {

    private data class TestCase(val input: String, val expected: String)

    @Test
    fun parseReference_matchesOriginalTypeScriptTestCases() {
        val cases = listOf(
            TestCase("John 3:16", "John 3:16"),
            TestCase("John+3:16", "John 3:16"),
            TestCase("John  3:16", "John 3:16"),
            TestCase("John++3:16", "John 3:16"),
            TestCase("1st John 3:16", "1 John 3:16"),
            TestCase("1st+John+3:16", "1 John 3:16"),
            TestCase("2nd Samuel 5:4", "2 Samuel 5:4"),
            TestCase("3rd Revelation 1:1", "3 Revelation 1:1"),
            TestCase("John 3.16", "John 3:16"),
            TestCase("Genesis 1:1-3", "Genesis 1:1-3"),
            TestCase("Psalm 23:1-6", "Psalm 23:1-6"),
            TestCase("Romans 8:28", "Romans 8:28"),
            TestCase("1 Corinthians 13:4", "1 Corinthians 13:4"),
            TestCase("Matthew 5:9", "Matthew 5:9"),
            TestCase("(John 3:16)", "John 3:16"),
            TestCase("( John 3:16 )", "John 3:16"),
            TestCase("  John 3:16  ", "John 3:16"),
            TestCase("(Genesis 1:1)", "Genesis 1:1"),
            TestCase("((Psalm 23:1))", "Psalm 23:1"),
            TestCase("  (  Romans 8:28  )  ", "Romans 8:28"),
            TestCase("[John 3:16]", "John 3:16"),
            TestCase("[ John 3:16]", "John 3:16"),
            TestCase("John 3:16]", "John 3:16"),
            TestCase("{Romans 12:2}", "Romans 12:2"),
            TestCase("John 3:16,17", "John 3:16,17"),
            TestCase("John 3:16 and 17", "John 3:16,17"),
            TestCase("John 3:16, 17 and 24", "John 3:16,17,24"),
            TestCase("John 3:16,17,18", "John 3:16,17,18"),
            TestCase("John 3:16, 17, 18 and 19", "John 3:16,17,18,19"),
            TestCase("Romans 5:1,2 and 3", "Romans 5:1,2,3"),
            TestCase("John 3:16 cf Romans 5:8", "John 3:16,Romans 5:8"),
            TestCase("John 3:16 cf. Romans 5:8", "John 3:16,Romans 5:8"),
            TestCase("John 3:16 eg Romans 5:8", "John 3:16,Romans 5:8"),
            TestCase("John 3:16, cf. Romans 5:8", "John 3:16,Romans 5:8"),
            TestCase("John 3:16; see also Romans 5:8", "John 3:16,Romans 5:8"),
            TestCase("John 3:16. See also Romans 5:8", "John 3:16,Romans 5:8"),
            TestCase("Deut. 10:14", "Deut 10:14"),
            TestCase("Deut. 10:14; 1 Chron. 29:11", "Deut 10:14,1 Chron 29:11"),
            TestCase("(Deut. 10:14; 1 Chron. 29:11)", "Deut 10:14,1 Chron 29:11"),
            TestCase("Deut. 10:14; 1 Chron. 29:11; Job 41:11", "Deut 10:14,1 Chron 29:11,Job 41:11"),
            TestCase("Pss. 24:1–2", "Pss 24:1-2"),
            TestCase("Ps. 24:1–2", "Ps 24:1-2"),
        )
        for ((input, expected) in cases) {
            assertEquals("Failed for input: $input", expected, parseReference(input))
        }
    }

    @Test
    fun extractBibleReference_handlesUrlWithQuote() {
        val input = "\"Genesis 1:2-3\" https://example.org/page"
        val result = extractBibleReference(input)
        assertEquals("Genesis 1:2-3", result)
    }

    @Test
    fun extractBibleReference_handlesUrlWithScrollToText() {
        val input = "\"Genesis 1:2\u20133\"\nhttps://frame-poythress.org/page/#:~:text=of%20God%20in-,Genesis%201%3A2%E2%80%933,-.)"
        val result = extractBibleReference(input)
        // extractBibleReference only strips junk and extracts text — it doesn't normalize dashes (parseReference does)
        assertEquals("Genesis 1:2–3", result)
    }

    @Test
    fun extractBibleReference_handlesTextWithReference() {
        val input = "I just read John 3:16 and it was amazing!"
        val result = extractBibleReference(input)
        assertEquals("John 3:16", result)
    }

    @Test
    fun extractBibleReference_pureReferencePassesThrough() {
        val input = "John 3:16"
        val result = extractBibleReference(input)
        assertEquals("John 3:16", result)
    }

    @Test
    fun extractAndParse_combined() {
        val input = "\"Genesis 1:2-3\" https://example.org/page"
        val result = parseReference(extractBibleReference(input))
        assertEquals("Genesis 1:2-3", result)
    }

    @Test
    fun extract_ordinalBookName() {
        assertEquals("1 John 3:16", extractBibleReference("1 John 3:16"))
        assertEquals("2nd Samuel 5:4", extractBibleReference("2nd Samuel 5:4"))
    }

    @Test
    fun extract_noReferenceInText_returnsRawText() {
        val input = "Just a regular sentence without a reference"
        assertEquals(input, extractBibleReference(input))
    }

    @Test
    fun extract_urlOnly_returnsEmpty() {
        assertEquals("", extractBibleReference("https://example.com/some-page"))
    }

    @Test
    fun extract_emptyInput_returnsEmpty() {
        assertEquals("", extractBibleReference(""))
        assertEquals("", extractBibleReference("   "))
    }

    @Test
    fun extract_referenceEndOfSentence() {
        assertEquals("John 3:16", extractBibleReference("Read John 3:16."))
    }

    @Test
    fun extract_chapterOnly() {
        assertEquals("Psalm 23", extractBibleReference("Psalm 23"))
    }

    @Test
    fun extract_enDashInText() {
        assertEquals("Genesis 1:2–3", extractBibleReference("Genesis 1:2–3"))
    }

    @Test
    fun extract_takesFirstReference() {
        val result = extractBibleReference("John 3:16 and Romans 8:28")
        assertEquals("John 3:16", result)
    }

    @Test
    fun extract_bookNameWithPeriod() {
        assertEquals("Jn. 3:16", extractBibleReference("Jn. 3:16"))
        assertEquals("Gen. 1:1", extractBibleReference("Gen. 1:1"))
    }

    @Test
    fun extract_multiWordBookName() {
        assertEquals("1 Chronicles 29:11", extractBibleReference("1 Chronicles 29:11"))
        assertEquals("2 Corinthians 5:17", extractBibleReference("2 Corinthians 5:17"))
    }

    @Test
    fun extract_typographicDoubleQuotes() {
        assertEquals("Genesis 1:1", extractBibleReference("\u201CGenesis 1:1\u201D"))
    }

    @Test
    fun extract_typographicSingleQuotes() {
        assertEquals("John 3:16", extractBibleReference("\u2018John 3:16\u2019"))
    }

    @Test
    fun extract_mixedQuoteTypes() {
        assertEquals("Romans 8:28", extractBibleReference("\u201CRomans 8:28\u2019"))
    }

    @Test
    fun extract_backtickQuotes() {
        assertEquals("Psalm 23:1", extractBibleReference("`Psalm 23:1`"))
    }
}
