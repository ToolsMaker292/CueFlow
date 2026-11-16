package com.cueflow.app.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Best-effort extractor for legacy Word 97-2003 .doc files.
 *
 * A .doc is an OLE2 compound document with a WordDocument stream and a table
 * stream. We read the piece table (CLX) when present and fall back to the FIB
 * text range for older/simpler files. Encrypted and malformed files are
 * rejected; users can always re-save as .docx.
 */
object DocTextExtractor {
    private const val END_OF_CHAIN = 0xFFFFFFFE.toInt()
    private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 200_000

    fun extract(bytes: ByteArray): String {
        if (!bytes.startsWithOle()) {
            throw DocumentImportException(DocumentImportError.INVALID)
        }
        val compound = runCatching { CompoundFile(bytes) }.getOrElse {
            throw DocumentImportException(DocumentImportError.INVALID)
        }
        val word = compound.readStream("WordDocument")
            ?: throw DocumentImportException(DocumentImportError.INVALID)
        if (word.size < 0x200) throw DocumentImportException(DocumentImportError.INVALID)

        val flags = word.u16(0x0A)
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = compound.readStream(tableName)
        val ccpText = word.u32(0x4C).coerceAtMost(MAX_TEXT_CHARS)
        var text: String? = null
        if (table != null) {
            val fcClx = word.u32(0x01A2)
            val lcbClx = word.u32(0x01A6)
            if (fcClx > 0 && lcbClx > 0 &&
                fcClx.toLong() + lcbClx.toLong() <= table.size
            ) {
                text = runCatching {
                    parsePieceTable(table, fcClx, lcbClx, ccpText, word)
                }.getOrNull()
            }
        }
        if (text.isNullOrBlank()) {
            val fcMin = word.u32(0x18)
            val fcMac = word.u32(0x1C)
            if (fcMin in 0 until word.size && fcMac > fcMin && fcMac <= word.size) {
                val available = (fcMac - fcMin).coerceAtMost(ccpText * 2)
                text = String(word, fcMin, available, StandardCharsets.UTF_16LE)
            }
        }
        val cleaned = cleanWordText(text.orEmpty())
        if (cleaned.isBlank()) throw DocumentImportException(DocumentImportError.NO_TEXT)
        return cleaned.take(MAX_TEXT_CHARS)
    }

    private fun parsePieceTable(
        table: ByteArray,
        fcClx: Int,
        lcbClx: Int,
        ccpText: Int,
        word: ByteArray,
    ): String? {
        var position = fcClx
        val end = (fcClx.toLong() + lcbClx.toLong()).coerceAtMost(table.size.toLong()).toInt()
        while (position < end && table[position].toInt() and 0xFF == 0x01) {
            if (position + 3 > end) return null
            val length = table.u16(position + 1)
            position += 3 + length
        }
        if (position + 5 > end || table[position].toInt() and 0xFF != 0x02) return null
        val pieceTableLength = table.u32(position + 1)
        val pieceStart = position + 5
        if (pieceTableLength < 4) return null
        val pieceCount = (pieceTableLength - 4) / 12
        if (pieceCount <= 0 || pieceCount > 20_000) return null
        val cpEnd = pieceStart + (pieceCount + 1) * 4
        val piecesEnd = cpEnd + pieceCount * 8
        if (piecesEnd > end || piecesEnd > table.size) return null

        val output = StringBuilder()
        val limit = if (ccpText > 0) ccpText else Int.MAX_VALUE
        for (index in 0 until pieceCount) {
            if (output.length >= limit) break
            val cpStart = table.u32(pieceStart + index * 4)
            val cpStop = table.u32(pieceStart + (index + 1) * 4)
            if (cpStop <= cpStart) continue
            val pcd = cpEnd + index * 8
            val rawFc = table.u32(pcd + 2)
            val compressed = rawFc and 0x40000000 != 0
            val fc = if (compressed) {
                (rawFc and 0x3FFFFFFF) / 2
            } else {
                rawFc and 0x3FFFFFFF
            }
            val charCount = (cpStop - cpStart).coerceAtMost(limit - output.length)
            if (compressed) {
                if (fc < 0 || fc + charCount > word.size) continue
                val text = String(word, fc, charCount, Charset.forName("windows-1252"))
                output.append(text)
            } else {
                val byteCount = charCount * 2
                if (fc < 0 || fc + byteCount > word.size) continue
                output.append(String(word, fc, byteCount, StandardCharsets.UTF_16LE))
            }
        }
        return output.toString()
    }

    private fun cleanWordText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0007', '\n')
            .replace('\u000B', '\n')
            .replace('\u000C', '\n')
            .replace("\u0000", "")
            .replace(Regex("[\\u0001-\\u0006\\u0008\\u000E-\\u0012\\u0014-\\u001F]"), "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private class CompoundFile(private val bytes: ByteArray) {
        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniCutoff: Int
        private val fat = mutableListOf<Int>()
        private val miniFat = mutableListOf<Int>()
        private val directory = mutableListOf<Entry>()
        private val miniStream: ByteArray

        init {
            val sectorShift = bytes.u16(0x1E)
            val miniSectorShift = bytes.u16(0x20)
            if (sectorShift !in 9..12 || miniSectorShift != 6) {
                throw DocumentImportException(DocumentImportError.INVALID)
            }
            sectorSize = 1 shl sectorShift
            miniSectorSize = 1 shl miniSectorShift
            miniCutoff = bytes.u32(0x38)
            if (bytes.size < sectorSize) throw DocumentImportException(DocumentImportError.INVALID)

            val difat = mutableListOf<Int>()
            for (index in 0 until 109) {
                val sector = bytes.u32(0x4C + index * 4)
                if (sector != END_OF_CHAIN && sector >= 0) difat.add(sector)
            }
            var difatSector = bytes.u32(0x44)
            var difatCount = 0
            while (difatSector != END_OF_CHAIN && difatCount < 10_000) {
                val values = readSector(difatSector) ?: break
                val perSector = sectorSize / 4 - 1
                for (index in 0 until perSector) {
                    val value = values.u32(index * 4)
                    if (value != END_OF_CHAIN && value >= 0) difat.add(value)
                }
                difatSector = values.u32(perSector * 4)
                difatCount++
            }

            val fatSectorCount = bytes.u32(0x2C)
            for (index in 0 until fatSectorCount.coerceAtMost(difat.size)) {
                val values = readSector(difat[index]) ?: break
                for (position in 0 until values.size / 4) fat.add(values.u32(position * 4))
            }
            if (fat.isEmpty()) throw DocumentImportException(DocumentImportError.INVALID)

            val directorySector = bytes.u32(0x30)
            for (sector in chain(directorySector)) {
                val values = readSector(sector) ?: break
                for (position in 0 until values.size / 128) {
                    val base = position * 128
                    val nameLength = values.u16(base + 64).coerceIn(0, 64)
                    if (nameLength < 2) continue
                    val name = String(
                        values,
                        base,
                        nameLength - 2,
                        StandardCharsets.UTF_16LE,
                    )
                    val type = values[base + 66].toInt() and 0xFF
                    val start = values.u32(base + 116)
                    val sizeLow = values.u32(base + 120)
                    val sizeHigh = values.u32(base + 124)
                    val size = (sizeHigh.toLong() shl 32 or (sizeLow.toLong() and 0xFFFFFFFFL))
                        .coerceAtMost(MAX_STREAM_BYTES.toLong())
                        .toInt()
                    directory.add(Entry(name, type, start, size))
                }
            }
            val root = directory.firstOrNull { it.type == 5 }
                ?: throw DocumentImportException(DocumentImportError.INVALID)
            miniStream = readRegular(root.start, root.size)

            val miniFatSector = bytes.u32(0x3C)
            val miniFatCount = bytes.u32(0x40)
            for (sector in chain(miniFatSector).take(miniFatCount.coerceAtMost(10_000))) {
                val values = readSector(sector) ?: break
                for (position in 0 until values.size / 4) miniFat.add(values.u32(position * 4))
            }
        }

        fun readStream(name: String): ByteArray? {
            val entry = directory.firstOrNull {
                it.type == 2 && it.name.equals(name, ignoreCase = true)
            } ?: return null
            return if (entry.size < miniCutoff) {
                readMini(entry.start, entry.size)
            } else {
                readRegular(entry.start, entry.size)
            }
        }

        private fun readRegular(start: Int, size: Int): ByteArray {
            if (start < 0 || size <= 0) return ByteArray(0)
            val output = ByteArray(size)
            var position = 0
            var sector = start
            var guard = 0
            while (sector != END_OF_CHAIN && position < size && guard < 100_000) {
                val values = readSector(sector) ?: break
                val count = (size - position).coerceAtMost(values.size)
                values.copyInto(output, position, 0, count)
                position += count
                sector = fat.getOrNull(sector) ?: END_OF_CHAIN
                guard++
            }
            return if (position == size) output else output.copyOf(position)
        }

        private fun readMini(start: Int, size: Int): ByteArray {
            if (start < 0 || size <= 0) return ByteArray(0)
            val output = ByteArray(size)
            var position = 0
            var sector = start
            var guard = 0
            while (sector != END_OF_CHAIN && position < size && guard < 100_000) {
                val offset = sector * miniSectorSize
                if (offset < 0 || offset + miniSectorSize > miniStream.size) break
                val count = (size - position).coerceAtMost(miniSectorSize)
                miniStream.copyInto(output, position, offset, offset + count)
                position += count
                sector = miniFat.getOrNull(sector) ?: END_OF_CHAIN
                guard++
            }
            return if (position == size) output else output.copyOf(position)
        }

        private fun chain(start: Int): List<Int> {
            val result = mutableListOf<Int>()
            var sector = start
            var guard = 0
            while (sector != END_OF_CHAIN && guard < 100_000) {
                result.add(sector)
                sector = fat.getOrNull(sector) ?: END_OF_CHAIN
                guard++
            }
            return result
        }

        private fun readSector(sector: Int): ByteArray? {
            if (sector < 0) return null
            val offset = (sector.toLong() + 1) * sectorSize
            if (offset < 0 || offset + sectorSize > bytes.size) return null
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + sectorSize)
        }

        private data class Entry(
            val name: String,
            val type: Int,
            val start: Int,
            val size: Int,
        )
    }
}

private fun ByteArray.u16(offset: Int): Int {
    if (offset < 0 || offset + 2 > size) return 0
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.u32(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return 0
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
}

private fun ByteArray.startsWithOle(): Boolean {
    val signature = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
    if (size < signature.size) return false
    return signature.indices.all { (this[it].toInt() and 0xFF) == signature[it] }
}
