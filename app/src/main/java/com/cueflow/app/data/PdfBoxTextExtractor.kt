package com.cueflow.app.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Local PDF text-layer extraction with PDFBox-Android.
 *
 * A PDF with a real text layer returns text here and is imported directly.
 * A scanned PDF returns null/blank and is routed to OCR by the caller.
 */
object PdfBoxTextExtractor {
    fun extract(context: Context, input: InputStream): String? {
        PDFBoxResourceLoader.init(context.applicationContext)
        return runCatching {
            PDDocument.load(input).use { document ->
                if (document.isEncrypted) return@use null
                val text = PDFTextStripper().getText(document)
                text
                    .replace("\u0000", "")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replace(Regex("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
                    .trim()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
