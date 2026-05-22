package com.eratverbum.stepbible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            TestCase("John 3:16;.123", "John 3:16"),
            TestCase("John 3:16).123", "John 3:16"),
            TestCase("Psalm 119", "Psalm 119"),
            TestCase("KJV John 3:16", "KJV John 3:16"),
            TestCase("John 3:16—18", "John 3:16-18"),
            TestCase("John 3:16-18,20-22", "John 3:16-18,20-22"),
        )
        for ((input, expected) in cases) {
            assertEquals("Failed for input: $input", expected, parseReference(input))
        }
    }

    @Test
    fun parseReference_footnotePatterns() {
        assertEquals("John 3:16", parseReference("John 3:16;.123"))
        assertEquals("John 3:16", parseReference("John 3:16).123"))
    }

    @Test
    fun parseReference_chapterOnly() {
        assertEquals("Psalm 119", parseReference("Psalm 119"))
    }

    @Test
    fun parseReference_versionPrefix() {
        assertEquals("KJV John 3:16", parseReference("KJV John 3:16"))
    }

    @Test
    fun parseReference_emDash() {
        assertEquals("John 3:16-18", parseReference("John 3:16—18"))
    }

    @Test
    fun parseReference_multipleRanges() {
        assertEquals("John 3:16-18,20-22", parseReference("John 3:16-18,20-22"))
    }

    @Test
    fun extractBibleReference_handlesUrlWithQuote() {
        val input = "\"Genesis 1:2-3\" https://example.org/page"
        assertEquals("Genesis 1:2-3", extractBibleReference(input))
    }

    @Test
    fun extractBibleReference_handlesUrlWithScrollToText() {
        val input = "\"Genesis 1:2\u20133\"\nhttps://frame-poythress.org/page/#:~:text=of%20God%20in-,Genesis%201%3A2%E2%80%933,-.)"
        val result = extractBibleReference(input)
        assertEquals("Genesis 1:2–3", result)
    }

    @Test
    fun extractBibleReference_handlesTextWithReference() {
        assertEquals("John 3:16", extractBibleReference("I just read John 3:16 and it was amazing!"))
    }

    @Test
    fun extractBibleReference_pureReferencePassesThrough() {
        assertEquals("John 3:16", extractBibleReference("John 3:16"))
    }

    @Test
    fun extractAndParse_combined() {
        val input = "\"Genesis 1:2-3\" https://example.org/page"
        val ref = extractBibleReference(input)!!
        assertEquals("Genesis 1:2-3", parseReference(ref))
    }

    @Test
    fun extract_ordinalBookName() {
        assertEquals("1 John 3:16", extractBibleReference("1 John 3:16"))
        assertEquals("2nd Samuel 5:4", extractBibleReference("2nd Samuel 5:4"))
    }

    @Test
    fun extract_noReferenceInText_returnsNull() {
        assertNull(extractBibleReference("Just a regular sentence without a reference"))
    }

    @Test
    fun extract_urlOnly_returnsNull() {
        assertNull(extractBibleReference("https://example.com/some-page"))
    }

    @Test
    fun extract_emptyInput_returnsNull() {
        assertNull(extractBibleReference(""))
        assertNull(extractBibleReference("   "))
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
        assertEquals("John 3:16", extractBibleReference("John 3:16 and Romans 8:28"))
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

    @Test
    fun extract_shortAbbreviation() {
        assertEquals("Mt 5:3", extractBibleReference("Mt 5:3"))
    }

    @Test
    fun extract_largeVerseRange() {
        assertEquals("Psalm 23:1-176", extractBibleReference("Psalm 23:1-176"))
    }

    @Test
    fun extract_lowercaseBookName_returnsNull() {
        assertNull(extractBibleReference("john 3:16"))
    }

    @Test
    fun rebuildUrl_absoluteHttpWithPort_rewritesPort() {
        assertEquals("http://127.0.0.1:8990/some/path", rebuildUrl("http://127.0.0.1:8989/some/path", port = 8990))
    }

    @Test
    fun rebuildUrl_absoluteHttpLocalhost_rewritesPort() {
        assertEquals("http://localhost:9000/page?q=test", rebuildUrl("http://localhost:8989/page?q=test", port = 9000))
    }

    @Test
    fun rebuildUrl_absoluteHttpsExternal_unchanged() {
        assertEquals("https://example.com/page", rebuildUrl("https://example.com/page", port = 8989))
    }

    @Test
    fun rebuildUrl_relativePath_prependsLocalhost() {
        assertEquals("http://127.0.0.1:8989/some/relative/path", rebuildUrl("/some/relative/path", port = 8989))
    }

    @Test
    fun rebuildUrl_emptyString_prependsLocalhost() {
        assertEquals("http://127.0.0.1:8989", rebuildUrl("", port = 8989))
    }

    @Test
    fun rebuildUrl_defaultsToServerStatePort() {
        val originalPort = ServerState.port
        try {
            ServerState.port = 9001
            assertEquals("http://127.0.0.1:9001/path", rebuildUrl("/path"))
        } finally {
            ServerState.port = originalPort
        }
    }

    @Test
    fun rebuildUrl_fragmentPreserved() {
        assertEquals("http://127.0.0.1:8990/path#section", rebuildUrl("http://127.0.0.1:8989/path#section", port = 8990))
    }

    @Test
    fun rebuildUrl_nonLocalhostIpUnchanged() {
        assertEquals("http://10.0.0.1:8989/path", rebuildUrl("http://10.0.0.1:8989/path", port = 8990))
    }
}
