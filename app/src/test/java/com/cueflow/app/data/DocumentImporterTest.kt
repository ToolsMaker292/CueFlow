package com.cueflow.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImporterTest {
    private fun text(bytes: ByteArray, name: String): String =
        DocumentImporter.extractText(ByteArrayInputStream(bytes), name)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `imports utf8 text markdown csv and html`() {
        assertEquals("第一行\n第二行", text("第一行\n第二行".toByteArray(), "script.txt"))
        assertEquals("# 标题\n正文", text("# 标题\n正文".toByteArray(), "script.md"))
        assertEquals("a,b\n1,2", text("a,b\n1,2".toByteArray(), "script.csv"))
        val html = "<h1>标题</h1><p>第一段 &amp; 第二段</p><p>下一段</p>"
        val extracted = text(html.toByteArray(), "script.html")
        assertTrue(extracted.contains("标题"))
        assertTrue(extracted.contains("第一段 & 第二段"))
        assertTrue(extracted.contains("下一段"))
    }

    @Test
    fun `falls back to gb18030 for legacy chinese text`() {
        val bytes = "旧版中文脚本".toByteArray(Charset.forName("GB18030"))
        assertEquals("旧版中文脚本", text(bytes, "legacy.txt"))
    }

    @Test
    fun `imports rtf without control words`() {
        val rtf = """{\rtf1\ansi 第一段\par 第二段\tab 末尾}"""
        val extracted = text(rtf.toByteArray(), "script.rtf")
        assertTrue(extracted.contains("第一段"))
        assertTrue(extracted.contains("第二段"))
        assertTrue(extracted.contains("末尾"))
        assertTrue(!extracted.contains("rtf1"))
    }

    @Test
    fun `imports odt content xml`() {
        val content = """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:text>
                <text:h>标题</text:h>
                <text:p>第一段</text:p>
                <text:p>第二段<text:tab/>末尾</text:p>
              </office:text></office:body>
            </office:document-content>"""
        val bytes = zip("content.xml" to content.toByteArray())
        val extracted = text(bytes, "script.odt")
        assertTrue(extracted.contains("标题"))
        assertTrue(extracted.contains("第一段"))
        assertTrue(extracted.contains("末尾"))
    }

    @Test
    fun `imports epub xhtml chapters`() {
        val chapter = """<html><body><h1>第一章</h1><p>这是 EPUB 正文。</p></body></html>"""
        val bytes = zip(
            "META-INF/container.xml" to "<container/>".toByteArray(),
            "OEBPS/chapter1.xhtml" to chapter.toByteArray(),
        )
        val extracted = text(bytes, "book.epub")
        assertTrue(extracted.contains("第一章"))
        assertTrue(extracted.contains("这是 EPUB 正文。"))
    }

    @Test
    fun `extracts text from a simple pdf`() {
        val bytes = simplePdf(
            content = "BT /F1 12 Tf 72 720 Td (Hello PDF) Tj ET",
            flate = false,
        )
        assertEquals("Hello PDF", text(bytes, "script.pdf"))
    }

    @Test
    fun `extracts text from a flate compressed pdf`() {
        val bytes = simplePdf(
            content = "BT /F1 12 Tf 72 720 Td (Compressed PDF) Tj ET",
            flate = true,
        )
        assertEquals("Compressed PDF", text(bytes, "script.pdf"))
    }

    @Test
    fun `rejects unsupported binary data`() {
        val error = assertThrows(DocumentImportException::class.java) {
            text(byteArrayOf(0, 1, 2, 3, 4, 5), "unknown.bin")
        }
        assertEquals(DocumentImportError.UNSUPPORTED, error.reason)
    }

    @Test
    fun `detects all advertised document formats`() {
        assertEquals(DocumentFormat.DOCX, DocumentImporter.detectFormat("a.docx", "PK".toByteArray()))
        assertEquals(DocumentFormat.DOC, DocumentImporter.detectFormat("a.doc", byteArrayOf()))
        assertEquals(DocumentFormat.PDF, DocumentImporter.detectFormat("a.pdf", byteArrayOf()))
        assertEquals(DocumentFormat.TXT, DocumentImporter.detectFormat("a.txt", byteArrayOf()))
        assertEquals(DocumentFormat.MARKDOWN, DocumentImporter.detectFormat("a.md", byteArrayOf()))
        assertEquals(DocumentFormat.HTML, DocumentImporter.detectFormat("a.html", byteArrayOf()))
        assertEquals(DocumentFormat.RTF, DocumentImporter.detectFormat("a.rtf", byteArrayOf()))
        assertEquals(DocumentFormat.ODT, DocumentImporter.detectFormat("a.odt", byteArrayOf()))
        assertEquals(DocumentFormat.EPUB, DocumentImporter.detectFormat("a.epub", byteArrayOf()))
        assertEquals(DocumentFormat.CSV, DocumentImporter.detectFormat("a.csv", byteArrayOf()))
    }

    private fun simplePdf(content: String, flate: Boolean): ByteArray {
        val contentBytes = content.toByteArray()
        val streamBytes = if (flate) {
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output).use { it.write(contentBytes) }
            output.toByteArray()
        } else {
            contentBytes
        }
        val filter = if (flate) "/Filter /FlateDecode " else ""
        val pdf = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
            append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n")
            append("4 0 obj\n<< $filter/Length ${streamBytes.size} >>\nstream\n")
            append(String(streamBytes, Charsets.ISO_8859_1))
            append("\nendstream\nendobj\n%%EOF")
        }
        return pdf.toByteArray(Charsets.ISO_8859_1)
    }
}
