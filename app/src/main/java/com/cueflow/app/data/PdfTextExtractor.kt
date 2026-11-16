package com.cueflow.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Best-effort, dependency-free PDF text extraction.
 *
 * It scans PDF streams, inflates FlateDecode content, reads Tj/TJ text
 * operators and applies ToUnicode mappings when present. It intentionally
 * skips encrypted files and scanned pages (which need OCR).
 */
object PdfTextExtractor {
    private const val MAX_STREAM_BYTES = 16 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 200_000

    fun extract(bytes: ByteArray): String {
        if (!bytes.startsWithAscii("%PDF")) {
            throw DocumentImportException(DocumentImportError.INVALID)
        }
        val latin = bytes.toString(StandardCharsets.ISO_8859_1)
        if (latin.contains("/Encrypt")) {
            throw DocumentImportException(DocumentImportError.INVALID)
        }
        val streams = extractStreams(bytes)
        val cmap = buildCMap(streams)
        val output = StringBuilder()
        for (stream in streams) {
            if (!looksLikeContent(stream.data)) continue
            val content = String(stream.data, StandardCharsets.ISO_8859_1)
            val text = decodeContent(content, cmap)
            if (text.isNotBlank()) {
                if (output.isNotEmpty()) output.append("\n\n")
                output.append(text)
            }
            if (output.length >= MAX_TEXT_CHARS) break
        }
        val result = output.toString().trim()
        if (result.isBlank()) throw DocumentImportException(DocumentImportError.NO_TEXT)
        return result.take(MAX_TEXT_CHARS)
    }

    private data class Stream(val dictionary: String, val data: ByteArray)

    private data class CMap(val twoByte: Boolean, val map: Map<Int, String>)

    private fun extractStreams(bytes: ByteArray): List<Stream> {
        val streams = mutableListOf<Stream>()
        val latin = bytes.toString(StandardCharsets.ISO_8859_1)
        var cursor = 0
        while (cursor < latin.length) {
            val keyword = latin.indexOf("stream", cursor)
            if (keyword < 0) break
            val before = latin.getOrNull(keyword - 1)
            val after = latin.getOrNull(keyword + 6)
            if ((before?.isLetterOrDigit() == true) || (after?.isLetterOrDigit() == true)) {
                cursor = keyword + 6
                continue
            }
            var dataStart = keyword + 6
            if (latin.startsWith("\r\n", dataStart)) dataStart += 2
            else if (latin.startsWith("\n", dataStart) || latin.startsWith("\r", dataStart)) {
                dataStart += 1
            }
            val end = latin.indexOf("endstream", dataStart)
            if (end < 0) break
            val dictionaryStart = (keyword - 2_048).coerceAtLeast(0)
            val dictionary = latin.substring(dictionaryStart, keyword)
            val raw = bytes.copyOfRange(dataStart, end)
            val decoded = decodeStream(dictionary, raw)
            if (decoded == null) {
                cursor = end + 9
                continue
            }
            streams.add(Stream(dictionary, decoded))
            if (streams.size >= 2_000) break
            cursor = end + 9
        }
        return streams
    }

    private fun decodeStream(dictionary: String, raw: ByteArray): ByteArray? {
        val lower = dictionary.lowercase(Locale.US)
        val filter = Regex("""/filter\s*(\[[^\]]*]|/\w+)""")
            .find(lower)?.groupValues?.get(1)
            .orEmpty()
        return when {
            filter.contains("flate") -> inflate(raw)
            filter.contains("asciihex") -> asciiHexDecode(raw)
            filter.contains("ascii85") -> ascii85Decode(raw)
            filter.isBlank() -> raw
            else -> null
        }
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        val attempts = listOf(false, true)
        for (nowrap in attempts) {
            try {
                val inflater = Inflater(nowrap)
                val input = InflaterInputStream(ByteArrayInputStream(raw), inflater)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_STREAM_BYTES) return null
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            } catch (_: Exception) {
                // Try the other zlib wrapper mode.
            }
        }
        return null
    }

    private fun asciiHexDecode(raw: ByteArray): ByteArray? {
        val digits = raw
            .map { it.toInt().toChar() }
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .joinToString("")
        if (digits.length % 2 != 0) return null
        return ByteArray(digits.length / 2) { index ->
            digits.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
    }

    private fun ascii85Decode(raw: ByteArray): ByteArray? {
        val chars = raw
            .map { it.toInt().toChar() }
            .filterNot { it.isWhitespace() }
            .dropWhile { it == '<' || it == '~' }
            .takeWhile { it != '~' }
        val output = ByteArrayOutputStream()
        var tuple = 0
        var count = 0
        for (char in chars) {
            if (char == 'z' && count == 0) {
                repeat(4) { output.write(0) }
                continue
            }
            val value = char.code - 33
            if (value !in 0..84) continue
            tuple = tuple * 85 + value
            count++
            if (count == 5) {
                output.write((tuple ushr 24) and 0xFF)
                output.write((tuple ushr 16) and 0xFF)
                output.write((tuple ushr 8) and 0xFF)
                output.write(tuple and 0xFF)
                tuple = 0
                count = 0
            }
        }
        if (count > 0) {
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            repeat(count - 1) { output.write((tuple ushr (24 - it * 8)) and 0xFF) }
        }
        return output.toByteArray()
    }

    private fun looksLikeContent(data: ByteArray): Boolean {
        val text = String(data, StandardCharsets.ISO_8859_1)
        if (!text.contains("BT")) return false
        return text.contains("Tj") || text.contains("TJ")
    }

    private fun buildCMap(streams: List<Stream>): CMap? {
        val map = mutableMapOf<Int, String>()
        var twoByte = false
        for (stream in streams) {
            val text = String(stream.data, StandardCharsets.ISO_8859_1)
            if (!text.contains("beginbfchar") && !text.contains("beginbfrange")) continue
            val hexPattern = Regex("""<([0-9A-Fa-f]+)>""")
            val charBlocks = Regex("""(?s)beginbfchar(.*?)endbfchar""").findAll(text)
            for (block in charBlocks) {
                val values = hexPattern.findAll(block.groupValues[1])
                    .map { it.groupValues[1] }
                    .toList()
                var index = 0
                while (index + 1 < values.size) {
                    val source = values[index]
                    val target = hexStringToUnicode(values[index + 1])
                    if (source.length > 2) twoByte = true
                    source.hexToIntOrNull()?.let { map[it] = target }
                    index += 2
                }
            }
            val rangeBlocks = Regex("""(?s)beginbfrange(.*?)endbfrange""").findAll(text)
            for (block in rangeBlocks) {
                val body = block.groupValues[1]
                val values = hexPattern.findAll(body).map { it.groupValues[1] }.toList()
                var index = 0
                while (index + 2 < values.size) {
                    val start = values[index].hexToIntOrNull() ?: break
                    val end = values[index + 1].hexToIntOrNull() ?: break
                    val target = values[index + 2]
                    if (values[index].length > 2) twoByte = true
                    val count = (end - start).coerceIn(0, 4_096)
                    for (offset in 0..count) {
                        val base = hexStringToUnicode(target)
                        val codePoint = base.firstOrNull()?.code?.plus(offset)
                        val value = codePoint?.let { String(Character.toChars(it)) }.orEmpty()
                        map[start + offset] = value
                    }
                    index += 3
                }
            }
        }
        if (map.isEmpty()) return null
        return CMap(twoByte, map)
    }

    private fun decodeContent(content: String, cmap: CMap?): String {
        val lexer = ContentLexer(content)
        val output = StringBuilder()
        var lastString: ByteArray? = null
        var lastArray: List<ArrayToken>? = null
        var inArray = false
        var array = mutableListOf<ArrayToken>()

        fun newline() {
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
        }

        while (true) {
            val token = lexer.next() ?: break
            when (token) {
                is ContentToken.ArrayStart -> {
                    inArray = true
                    array = mutableListOf()
                }
                is ContentToken.ArrayEnd -> {
                    inArray = false
                    lastArray = array
                }
                is ContentToken.Number -> if (inArray) array.add(ArrayToken.Number(token.value))
                is ContentToken.StringValue -> {
                    if (inArray) array.add(ArrayToken.Bytes(token.bytes))
                    else lastString = token.bytes
                }
                is ContentToken.HexValue -> {
                    if (inArray) array.add(ArrayToken.Bytes(token.bytes))
                    else lastString = token.bytes
                }
                is ContentToken.Operator -> {
                    when (token.value) {
                        "Tj" -> lastString?.let { appendText(output, it, cmap) }
                        "TJ" -> lastArray?.forEach { item ->
                            when (item) {
                                is ArrayToken.Bytes -> appendText(output, item.value, cmap)
                                is ArrayToken.Number -> if (item.value < -120) {
                                    if (output.isNotEmpty() && output.last() != ' ') output.append(' ')
                                }
                            }
                        }
                        "T*", "Td", "TD", "ET" -> newline()
                    }
                    lastString = null
                    lastArray = null
                    array.clear()
                }
                is ContentToken.Name -> Unit
            }
        }
        return output.toString()
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun appendText(output: StringBuilder, bytes: ByteArray, cmap: CMap?) {
        var value = if (cmap == null) {
            decodeRawPdfString(bytes)
        } else if (cmap.twoByte && bytes.size >= 2) {
            buildString {
                var index = 0
                while (index + 1 < bytes.size) {
                    val code = ((bytes[index].toInt() and 0xFF) shl 8) or
                        (bytes[index + 1].toInt() and 0xFF)
                    append(cmap.map[code].orEmpty())
                    index += 2
                }
            }
        } else {
            buildString {
                for (byte in bytes) append(cmap.map[byte.toInt() and 0xFF].orEmpty())
            }
        }
        if (value.isBlank()) value = decodeRawPdfString(bytes)
        if (value.isBlank()) return
        if (output.isNotEmpty() &&
            !output.last().isWhitespace() &&
            value.first().isLetterOrDigit()
        ) {
            output.append(' ')
        }
        output.append(value)
    }

    private fun decodeRawPdfString(bytes: ByteArray): String {
        if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val chars = bytes.map { byte ->
            val value = byte.toInt() and 0xFF
            when {
                value == 9 || value == 10 || value == 13 -> value.toChar()
                value in 32..126 -> value.toChar()
                value in 0xA0..0xFF -> value.toChar()
                else -> ' '
            }
        }
        return chars.joinToString("")
            .replace(Regex("[ \\t]{2,}"), " ")
    }

    private sealed interface ArrayToken {
        data class Bytes(val value: ByteArray) : ArrayToken
        data class Number(val value: Double) : ArrayToken
    }

    private sealed interface ContentToken {
        data class StringValue(val bytes: ByteArray) : ContentToken
        data class HexValue(val bytes: ByteArray) : ContentToken
        data class Number(val value: Double) : ContentToken
        data class Operator(val value: String) : ContentToken
        data class Name(val value: String) : ContentToken
        data object ArrayStart : ContentToken
        data object ArrayEnd : ContentToken
    }

    private class ContentLexer(private val text: String) {
        private var index = 0

        fun next(): ContentToken? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (text[index]) {
                '(' -> ContentToken.StringValue(readLiteralString())
                '<' -> if (text.startsWith("<<", index)) {
                    index += 2
                    ContentToken.Operator("<<")
                } else {
                    ContentToken.HexValue(readHexString())
                }
                '>' -> {
                    index += if (text.startsWith(">>", index)) 2 else 1
                    ContentToken.Operator(">>")
                }
                '[' -> {
                    index++
                    ContentToken.ArrayStart
                }
                ']' -> {
                    index++
                    ContentToken.ArrayEnd
                }
                '/' -> {
                    index++
                    val start = index
                    while (index < text.length && !text[index].isWhitespace() &&
                        text[index] !in "()<>[]{}/%"
                    ) {
                        index++
                    }
                    ContentToken.Name(text.substring(start, index))
                }
                else -> {
                    val start = index
                    while (index < text.length && !text[index].isWhitespace() &&
                        text[index] !in "()<>[]{}/%"
                    ) {
                        index++
                    }
                    if (start == index) {
                        index++
                        return next()
                    }
                    val value = text.substring(start, index)
                    value.toDoubleOrNull()?.let { ContentToken.Number(it) }
                        ?: ContentToken.Operator(value)
                }
            }
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                val char = text[index]
                if (char.isWhitespace()) {
                    index++
                } else if (char == '%') {
                    while (index < text.length && text[index] != '\n') index++
                } else {
                    break
                }
            }
        }

        private fun readLiteralString(): ByteArray {
            index++ // '('
            val output = ByteArrayOutputStream()
            var depth = 1
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '\\' -> {
                        if (index >= text.length) break
                        when (val escaped = text[index++]) {
                            'n' -> output.write('\n'.code)
                            'r' -> output.write('\r'.code)
                            't' -> output.write('\t'.code)
                            'b' -> output.write('\b'.code)
                            'f' -> output.write(0x0C)
                            '(', ')', '\\' -> output.write(escaped.code)
                            '\r' -> if (index < text.length && text[index] == '\n') index++
                            '\n' -> Unit
                            in '0'..'7' -> {
                                var value = escaped.digitToInt()
                                repeat(2) {
                                    val next = text.getOrNull(index)
                                    if (next != null && next in '0'..'7') {
                                        value = value * 8 + next.digitToInt()
                                        index++
                                    }
                                }
                                output.write(value)
                            }
                            else -> output.write(escaped.code)
                        }
                    }
                    '(' -> {
                        depth++
                        output.write(char.code)
                    }
                    ')' -> {
                        depth--
                        if (depth == 0) break
                        output.write(char.code)
                    }
                    else -> output.write(char.code)
                }
            }
            return output.toByteArray()
        }

        private fun readHexString(): ByteArray {
            index++ // '<'
            val digits = StringBuilder()
            while (index < text.length && text[index] != '>') {
                val char = text[index++]
                if (char.isDigit() || char.lowercaseChar() in 'a'..'f') digits.append(char)
            }
            if (index < text.length) index++
            if (digits.length % 2 != 0) digits.append('0')
            return ByteArray(digits.length / 2) { position ->
                digits.substring(position * 2, position * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
            }
        }
    }

    private fun hexStringToUnicode(hex: String): String {
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        return String(bytes, StandardCharsets.UTF_16BE)
    }

    private fun String.hexToIntOrNull(): Int? = toIntOrNull(16)

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        if (size < value.length) return false
        return value.indices.all { (this[it].toInt() and 0xFF) == value[it].code }
    }
}
