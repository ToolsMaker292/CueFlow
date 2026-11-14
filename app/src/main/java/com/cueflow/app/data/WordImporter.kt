package com.cueflow.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Minimal, dependency-free .docx text extractor.
 *
 * A .docx file is a ZIP archive containing word/document.xml. We only read that
 * one entry, with hard limits, so a malformed file cannot exhaust memory or make
 * us parse arbitrary ZIP contents. Legacy .doc files are not ZIP archives and
 * are rejected with a clear error.
 */
object WordImporter {
    private const val DOCUMENT_XML = "word/document.xml"
    private const val MAX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 2_000

    fun extractText(input: InputStream): String {
        val zip = ZipInputStream(input)
        var entries = 0
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) {
                    throw WordImportException(WordImportError.INVALID)
                }
                if (entry.name == DOCUMENT_XML) {
                    val xml = readLimited(zip, MAX_XML_BYTES)
                    return parseDocumentXml(xml)
                }
                zip.closeEntry()
            }
        } catch (error: WordImportException) {
            throw error
        } catch (_: Exception) {
            throw WordImportException(WordImportError.INVALID)
        } finally {
            runCatching { zip.close() }
        }
        throw WordImportException(WordImportError.INVALID)
    }

    private fun parseDocumentXml(xml: ByteArray): String {
        val handler = WordTextHandler()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            runCatching {
                factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true,
                )
            }
            runCatching {
                factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false,
                )
            }
            runCatching {
                factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false,
                )
            }
            factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
        } catch (_: Exception) {
            throw WordImportException(WordImportError.INVALID)
        }
        val text = handler.cleanedText()
        if (text.isBlank()) throw WordImportException(WordImportError.NO_TEXT)
        return text
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw WordImportException(WordImportError.TOO_LARGE)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

private class WordTextHandler : DefaultHandler() {
    private val paragraphs = StringBuilder()
    private val currentParagraph = StringBuilder()
    private var inText = false

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes?,
    ) {
        when (elementName(localName, qName)) {
            "t" -> inText = true
            "tab" -> currentParagraph.append(' ')
            "br", "cr" -> currentParagraph.append('\n')
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (elementName(localName, qName)) {
            "t" -> inText = false
            "p" -> flushParagraph()
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) currentParagraph.append(ch, start, length)
    }

    fun cleanedText(): String {
        flushParagraph()
        return paragraphs.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun flushParagraph() {
        val text = currentParagraph.toString().trim()
        if (text.isNotEmpty()) {
            paragraphs.append(text).append('\n')
        }
        currentParagraph.clear()
    }

    private fun elementName(localName: String?, qName: String?): String =
        (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':'))
}

enum class WordImportError { INVALID, NO_TEXT, TOO_LARGE }

class WordImportException(val reason: WordImportError) : Exception(reason.name)

data class WordImportOutcome(
    val title: String,
    val content: String,
    val truncated: Boolean,
)
