package com.cueflow.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Local, dependency-free text extraction for common authoring formats.
 *
 * Supported extensions: docx, doc, pdf, txt/text, md/markdown, html/htm/xhtml,
 * rtf, odt, epub and csv. DOC/PDF use best-effort parsers; scanned PDFs still
 * need OCR and legacy DOC files must not be encrypted.
 */
object DocumentImporter {
    const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 200_000
    private const val MAX_ZIP_ENTRIES = 500
    private const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024

    val supportedExtensions: List<String> = listOf(
        "docx", "doc", "pdf", "txt", "text", "md", "markdown",
        "html", "htm", "xhtml", "rtf", "odt", "epub", "csv",
    )

    fun extractText(input: InputStream, fileName: String?): String {
        val bytes = readLimited(BufferedInputStream(input), MAX_IMPORT_BYTES)
        val format = detectFormat(fileName, bytes)
        val text = when (format) {
            DocumentFormat.DOCX -> extractDocx(bytes)
            DocumentFormat.DOC -> when {
                bytes.startsWithAscii("{\\rtf") -> rtfToText(decodeText(bytes))
                bytes.startsWithOle() -> DocTextExtractor.extract(bytes)
                else -> decodeText(bytes)
            }
            DocumentFormat.PDF -> PdfTextExtractor.extract(bytes)
            DocumentFormat.ODT -> extractOdt(bytes)
            DocumentFormat.EPUB -> extractEpub(bytes)
            DocumentFormat.HTML -> stripHtml(decodeText(bytes))
            DocumentFormat.RTF -> rtfToText(decodeText(bytes))
            DocumentFormat.MARKDOWN,
            DocumentFormat.TXT,
            DocumentFormat.CSV,
            -> decodeText(bytes)
            DocumentFormat.UNKNOWN -> throw DocumentImportException(
                DocumentImportError.UNSUPPORTED,
            )
        }
        if (text.isBlank()) throw DocumentImportException(DocumentImportError.NO_TEXT)
        return cleanText(text).take(MAX_TEXT_CHARS)
    }

    fun detectFormat(fileName: String?, bytes: ByteArray): DocumentFormat {
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            .orEmpty()
        when (extension) {
            "docx" -> return DocumentFormat.DOCX
            "doc" -> return DocumentFormat.DOC
            "pdf" -> return DocumentFormat.PDF
            "txt", "text" -> return DocumentFormat.TXT
            "md", "markdown" -> return DocumentFormat.MARKDOWN
            "html", "htm", "xhtml" -> return DocumentFormat.HTML
            "rtf" -> return DocumentFormat.RTF
            "odt" -> return DocumentFormat.ODT
            "epub" -> return DocumentFormat.EPUB
            "csv" -> return DocumentFormat.CSV
        }
        return sniffFormat(bytes)
    }

    private fun sniffFormat(bytes: ByteArray): DocumentFormat {
        if (bytes.startsWithAscii("%PDF")) return DocumentFormat.PDF
        if (bytes.startsWithAscii("{\\rtf")) return DocumentFormat.RTF
        if (bytes.startsWithOle()) return DocumentFormat.DOC
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte()
        ) {
            return when {
                zipContains(bytes, "word/document.xml") -> DocumentFormat.DOCX
                zipContains(bytes, "content.xml") -> DocumentFormat.ODT
                zipContains(bytes, "META-INF/container.xml") -> DocumentFormat.EPUB
                else -> DocumentFormat.UNKNOWN
            }
        }
        val sample = bytes.take(4_096)
        val nulCount = sample.count { it == 0.toByte() }
        if (nulCount > sample.size / 20) return DocumentFormat.UNKNOWN
        val ascii = sample.count { b ->
            val value = b.toInt() and 0xFF
            value == 9 || value == 10 || value == 13 || value in 32..126
        }
        return if (sample.isEmpty() || ascii >= sample.size * 3 / 4) {
            DocumentFormat.TXT
        } else {
            DocumentFormat.UNKNOWN
        }
    }

    private fun extractDocx(bytes: ByteArray): String = try {
        WordImporter.extractText(ByteArrayInputStream(bytes))
    } catch (error: WordImportException) {
        throw DocumentImportException(
            when (error.reason) {
                WordImportError.NO_TEXT -> DocumentImportError.NO_TEXT
                WordImportError.TOO_LARGE -> DocumentImportError.TOO_LARGE
                WordImportError.INVALID -> DocumentImportError.INVALID
            },
        )
    }

    private fun extractOdt(bytes: ByteArray): String {
        val xml = readZipEntry(bytes, "content.xml")
            ?: throw DocumentImportException(DocumentImportError.INVALID)
        val handler = OdtTextHandler()
        try {
            secureSaxFactory().newSAXParser().parse(ByteArrayInputStream(xml), handler)
        } catch (_: Exception) {
            throw DocumentImportException(DocumentImportError.INVALID)
        }
        return handler.cleanedText()
    }

    private fun extractEpub(bytes: ByteArray): String {
        val output = StringBuilder()
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        var entries = 0
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) {
                    throw DocumentImportException(DocumentImportError.TOO_LARGE)
                }
                val lowerName = entry.name.lowercase(Locale.US)
                val isHtml = lowerName.endsWith(".xhtml") ||
                    lowerName.endsWith(".html") ||
                    lowerName.endsWith(".htm")
                if (entry.isDirectory || !isHtml) {
                    zip.closeEntry()
                    continue
                }
                val baseName = lowerName.substringAfterLast('/')
                if ("nav" in baseName || "toc" in baseName) {
                    zip.closeEntry()
                    continue
                }
                val html = String(
                    readLimited(zip, MAX_ZIP_ENTRY_BYTES),
                    StandardCharsets.UTF_8,
                )
                val text = stripHtml(html)
                if (text.isNotBlank()) {
                    if (output.isNotEmpty()) output.append("\n\n")
                    output.append(text)
                }
                zip.closeEntry()
                if (output.length >= MAX_TEXT_CHARS) break
            }
        } catch (error: DocumentImportException) {
            throw error
        } catch (_: Exception) {
            throw DocumentImportException(DocumentImportError.INVALID)
        } finally {
            runCatching { zip.close() }
        }
        return output.toString()
    }

    private fun stripHtml(html: String): String {
        var text = html
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1\\s*>"), " ")
            .replace(
                Regex("(?is)</?(p|div|li|tr|h[1-6]|blockquote|pre|section|article|br)\\b[^>]*>"),
                "\n",
            )
            .replace(Regex("(?is)<[^>]+>"), " ")
        text = decodeHtmlEntities(text)
        return text
    }

    private fun decodeHtmlEntities(text: String): String {
        val named = mapOf(
            "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
            "quot" to "\"", "apos" to "'", "ndash" to "–", "mdash" to "—",
            "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™",
            "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        )
        var result = text.replace(
            Regex("(?i)&([a-z][a-z0-9]{1,8});"),
        ) { match -> named[match.groupValues[1].lowercase(Locale.US)] ?: match.value }
        result = result.replace(Regex("(?i)&#x([0-9a-f]+);")) { match ->
            codePointToString(match.groupValues[1].toIntOrNull(16))
        }
        result = result.replace(Regex("(?i)&#([0-9]+);")) { match ->
            codePointToString(match.groupValues[1].toIntOrNull())
        }
        return result
    }

    private fun codePointToString(value: Int?): String {
        if (value == null || value !in 0..0x10FFFF) return ""
        return runCatching { String(Character.toChars(value)) }.getOrDefault("")
    }

    private fun rtfToText(rtf: String): String {
        var text = rtf.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace(
            Regex(
                """\{\\(fonttbl|colortbl|stylesheet|info|pict|object|header|footer|footnote|annotation|field|fldinst)\b[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""",
                RegexOption.IGNORE_CASE,
            ),
            " ",
        )
        text = text.replace(Regex("""\\u(-?\d+)\s?""")) { match ->
            val raw = match.groupValues[1].toIntOrNull() ?: 0
            val codePoint = if (raw < 0) raw + 65536 else raw
            codePointToString(codePoint)
        }
        text = text.replace(Regex("""\\'([0-9a-fA-F]{2})""")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString().orEmpty()
        }
        text = text
            .replace(Regex("""\\(par|line|row)\b\s?"""), "\n")
            .replace(Regex("""\\cell\b\s?"""), "\t")
            .replace(Regex("""\\tab\b\s?"""), "\t")
            .replace(Regex("""\\emdash\b\s?"""), "—")
            .replace(Regex("""\\endash\b\s?"""), "–")
            .replace(Regex("""\\~"""), " ")
            .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), "")
            .replace(Regex("""\\[\\{}]""")) { match -> match.value.drop(1) }
            .replace("{", "")
            .replace("}", "")
        return text
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            runCatching {
                String(bytes, Charset.forName("GB18030"))
            }.getOrDefault(String(bytes, StandardCharsets.ISO_8859_1))
        }
    }

    private fun cleanText(text: String): String {
        val normalized = text
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
        return normalized
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun secureSaxFactory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw DocumentImportException(DocumentImportError.TOO_LARGE)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun readZipEntry(bytes: ByteArray, entryName: String): ByteArray? {
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        var entries = 0
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) {
                    throw DocumentImportException(DocumentImportError.TOO_LARGE)
                }
                if (entry.name == entryName) {
                    return readLimited(zip, MAX_ZIP_ENTRY_BYTES)
                }
                zip.closeEntry()
            }
        } catch (error: DocumentImportException) {
            throw error
        } catch (_: Exception) {
            throw DocumentImportException(DocumentImportError.INVALID)
        } finally {
            runCatching { zip.close() }
        }
        return null
    }

    private fun zipContains(bytes: ByteArray, entryName: String): Boolean {
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        var entries = 0
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) return false
                if (entry.name == entryName) return true
                zip.closeEntry()
            }
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { zip.close() }
        }
        return false
    }
}

enum class DocumentFormat {
    DOCX, DOC, PDF, TXT, MARKDOWN, HTML, RTF, ODT, EPUB, CSV, UNKNOWN
}

enum class DocumentImportError { INVALID, NO_TEXT, TOO_LARGE, UNSUPPORTED }

class DocumentImportException(val reason: DocumentImportError) : Exception(reason.name)

data class DocumentImportOutcome(
    val title: String,
    val content: String,
    val truncated: Boolean,
)

private class OdtTextHandler : DefaultHandler() {
    private val paragraphs = StringBuilder()
    private val current = StringBuilder()

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes?,
    ) {
        when (elementName(localName, qName)) {
            "tab" -> current.append('\t')
            "line-break" -> current.append('\n')
            "s" -> {
                val count = attributes
                    ?.getValue("c")
                    ?.toIntOrNull()
                    ?.coerceIn(1, 80)
                    ?: 1
                repeat(count) { current.append(' ') }
            }
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (elementName(localName, qName)) {
            "p", "h" -> flush()
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        current.append(ch, start, length)
    }

    fun cleanedText(): String {
        flush()
        return paragraphs.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun flush() {
        val text = current.toString().trim()
        if (text.isNotEmpty()) paragraphs.append(text).append('\n')
        current.clear()
    }

    private fun elementName(localName: String?, qName: String?): String =
        (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':'))
}

private fun ByteArray.startsWithAscii(value: String): Boolean {
    if (size < value.length) return false
    for (index in value.indices) {
        if (this[index].toInt() and 0xFF != value[index].code) return false
    }
    return true
}

private fun ByteArray.startsWithOle(): Boolean {
    val signature = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
    if (size < signature.size) return false
    return signature.indices.all { (this[it].toInt() and 0xFF) == signature[it] }
}

private fun ByteArray.take(limit: Int): ByteArray =
    if (size <= limit) this else copyOf(limit)
