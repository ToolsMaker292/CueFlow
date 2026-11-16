package com.cueflow.app.data

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocTextExtractorTest {
    @Test
    fun `extracts text from a simple Word 97 compound document`() {
        val text = DocTextExtractor.extract(wordDoc("Hello"))
        assertEquals("Hello", text)
    }

    @Test
    fun `rejects non ole data`() {
        val error = assertThrows(DocumentImportException::class.java) {
            DocTextExtractor.extract("not a doc".toByteArray())
        }
        assertEquals(DocumentImportError.INVALID, error.reason)
    }

    /**
     * Builds a tiny but valid OLE2 file with WordDocument and 1Table streams.
     * The FIB points at a one-piece CLX whose text is stored uncompressed.
     */
    private fun wordDoc(text: String): ByteArray {
        val sectorSize = 512
        val file = ByteArray(sectorSize * 5)
        val header = file
        val signature = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        signature.forEachIndexed { index, value -> header[index] = value.toByte() }
        put16(header, 0x18, 0x003E)
        put16(header, 0x1A, 0x0003)
        put16(header, 0x1C, 0xFFFE)
        put16(header, 0x1E, 9)
        put16(header, 0x20, 6)
        put32(header, 0x2C, 1) // FAT sectors
        put32(header, 0x30, 1) // first directory sector
        put32(header, 0x38, 0) // no mini stream
        put32(header, 0x3C, END_OF_CHAIN)
        put32(header, 0x40, 0)
        put32(header, 0x44, END_OF_CHAIN)
        put32(header, 0x48, 0)
        put32(header, 0x4C, 0) // DIFAT[0] = FAT sector 0
        for (index in 1 until 109) put32(header, 0x4C + index * 4, FREE_SECTOR)

        val fatOffset = sectorSize
        for (index in 0 until sectorSize / 4) put32(file, fatOffset + index * 4, FREE_SECTOR)
        put32(file, fatOffset + 0 * 4, FATSECT)
        put32(file, fatOffset + 1 * 4, END_OF_CHAIN)
        put32(file, fatOffset + 2 * 4, END_OF_CHAIN)
        put32(file, fatOffset + 3 * 4, END_OF_CHAIN)

        val directoryOffset = sectorSize * 2
        writeDirectoryEntry(file, directoryOffset, "Root Entry", 5, END_OF_CHAIN, 0)
        writeDirectoryEntry(file, directoryOffset + 128, "WordDocument", 2, 2, sectorSize)
        writeDirectoryEntry(file, directoryOffset + 256, "1Table", 2, 3, sectorSize)

        val wordOffset = sectorSize * 3
        put16(file, wordOffset + 0x0A, 0x0200) // use 1Table
        val content = text.toByteArray(StandardCharsets.UTF_16LE)
        content.copyInto(file, wordOffset + 0x100)
        put32(file, wordOffset + 0x18, 0x100)
        put32(file, wordOffset + 0x1C, 0x100 + content.size)
        put32(file, wordOffset + 0x4C, text.length)
        put32(file, wordOffset + 0x01A2, 0)
        put32(file, wordOffset + 0x01A6, 21)

        val tableOffset = sectorSize * 4
        file[tableOffset] = 0x02
        put32(file, tableOffset + 1, 16)
        put32(file, tableOffset + 5, 0)
        put32(file, tableOffset + 9, text.length)
        put16(file, tableOffset + 13, 0)
        put32(file, tableOffset + 15, 0x100)
        put16(file, tableOffset + 19, 0)
        return file
    }

    private fun writeDirectoryEntry(
        target: ByteArray,
        offset: Int,
        name: String,
        type: Int,
        start: Int,
        size: Int,
    ) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_16LE)
        nameBytes.copyInto(target, offset)
        put16(target, offset + 64, nameBytes.size + 2)
        target[offset + 66] = type.toByte()
        target[offset + 67] = 1
        put32(target, offset + 68, FREE_SECTOR)
        put32(target, offset + 72, FREE_SECTOR)
        put32(target, offset + 76, FREE_SECTOR)
        put32(target, offset + 116, start)
        put32(target, offset + 120, size)
    }

    private fun put16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private companion object {
        const val END_OF_CHAIN = -2
        const val FREE_SECTOR = -1
        const val FATSECT = -3
    }
}
