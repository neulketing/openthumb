package com.neulketing.openthumb.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Regression guard for the debug server's request parsing.
 *
 * The server used to read headers through a BufferedReader and then fill a
 * CharArray sized by Content-Length. Both halves were wrong for non-ASCII
 * bodies: the buffered reader swallowed the start of the body, and one Korean
 * character is three UTF-8 bytes, so the char loop never reached the byte
 * count and blocked until the 30s socket timeout — the caller got an empty
 * response with no error. Every Korean prompt failed while English ones
 * worked.
 *
 * These tests pin the two properties that fix depends on: the header reader
 * stops exactly at the blank line (leaving the body byte-intact), and a body
 * read by byte count decodes back to the original text.
 */
class HeaderLineReaderTest {

    private fun request(body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = "POST / HTTP/1.1\r\nContent-Length: ${bodyBytes.size}\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + bodyBytes
    }

    @Test
    fun `header reader leaves the body untouched for a Korean payload`() {
        val body = """{"prompt":"안녕하세요 한글 테스트입니다"}"""
        val input = ByteArrayInputStream(request(body))
        val reader = HeaderLineReader(input)

        assertEquals("POST / HTTP/1.1", reader.readLine())
        val contentLength = reader.readLine()!!.substringAfter(":").trim().toInt()
        assertEquals("", reader.readLine())          // blank line ends the headers

        // Korean text is longer in bytes than in characters — the exact
        // mismatch that used to stall the read.
        assertEquals(body.toByteArray(Charsets.UTF_8).size, contentLength)

        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(buf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        assertEquals(contentLength, read)
        assertEquals(body, String(buf, 0, read, Charsets.UTF_8))
    }

    @Test
    fun `bare LF terminates a line and CR is stripped`() {
        val reader = HeaderLineReader(ByteArrayInputStream("a: 1\r\nb: 2\n".toByteArray()))
        assertEquals("a: 1", reader.readLine())
        assertEquals("b: 2", reader.readLine())
    }

    @Test
    fun `end of stream reads as null`() {
        assertNull(HeaderLineReader(ByteArrayInputStream(ByteArray(0))).readLine())
    }
}
