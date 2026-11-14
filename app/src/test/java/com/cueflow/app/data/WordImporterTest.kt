package com.cueflow.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WordImporterTest {
    private fun docx(documentXml: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun document(body: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>$body</w:body>
        </w:document>"""

    @Test
    fun `extracts paragraphs runs breaks and tabs`() {
        val xml = document(
            """
            <w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
            <w:p><w:r><w:t>第二段</w:t><w:br/><w:t>下一行</w:t></w:r></w:p>
            <w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t></w:r></w:p>
            """.trimIndent(),
        )
        val text = WordImporter.extractText(ByteArrayInputStream(docx(xml)))
        assertEquals("Hello world\n第二段\n下一行\na b", text)
    }

    @Test
    fun `rejects a zip without word document`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("other.xml"))
            zip.write("<x/>".toByteArray())
            zip.closeEntry()
        }
        val error = assertThrows(WordImportException::class.java) {
            WordImporter.extractText(ByteArrayInputStream(output.toByteArray()))
        }
        assertEquals(WordImportError.INVALID, error.reason)
    }

    @Test
    fun `rejects legacy or malformed files`() {
        val error = assertThrows(WordImportException::class.java) {
            WordImporter.extractText(ByteArrayInputStream("not a docx".toByteArray()))
        }
        assertEquals(WordImportError.INVALID, error.reason)
    }

    @Test
    fun `rejects a document with no text`() {
        val xml = document("<w:p><w:r><w:t>   </w:t></w:r></w:p>")
        val error = assertThrows(WordImportException::class.java) {
            WordImporter.extractText(ByteArrayInputStream(docx(xml)))
        }
        assertEquals(WordImportError.NO_TEXT, error.reason)
    }

    @Test
    fun `rejects doctype injection`() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <w:document xmlns:w="x"><w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>"""
        val error = assertThrows(WordImportException::class.java) {
            WordImporter.extractText(ByteArrayInputStream(docx(xml)))
        }
        assertEquals(WordImportError.INVALID, error.reason)
    }
}
