package com.cueflow.app

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordImportInstrumentedTest {
    private fun makeDocx(): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段来自 Word。</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二段用于提词测试。</w:t></w:r></w:p>
              </w:body>
            </w:document>"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test
    fun importsDocxAsANewScript() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        viewModel.loading.first { !it }

        val file = File(application.cacheDir, "WordImportTest.docx")
        file.writeBytes(makeDocx())
        try {
            val result = viewModel.importWord(Uri.fromFile(file))
            assertTrue(result.isSuccess)
            val outcome = result.getOrThrow()
            assertEquals("WordImportTest", outcome.title)
            assertTrue(outcome.content.contains("第一段来自 Word。"))
            assertTrue(viewModel.scripts.value.any { it.title == "WordImportTest" })
        } finally {
            file.delete()
        }
    }
}
