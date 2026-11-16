package com.cueflow.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** Local image/scanned-PDF OCR entry points. */
object OcrImporter {
    private const val MAX_IMAGE_SIDE = 2_200
    private const val MAX_PDF_PAGES = 30
    private const val PDF_RENDER_MAX_SIDE = 2_200

    fun recognizeImage(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw OcrException("Cannot open image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw OcrException("Cannot decode image")
        }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIDE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw OcrException("Cannot decode image")
        return try {
            OcrEngine.recognize(context, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun recognizePdf(context: Context, uri: Uri): String {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw OcrException("Cannot open PDF")
        return descriptor.use { parcel ->
            val renderer = PdfRenderer(parcel)
            try {
                if (renderer.pageCount <= 0) throw OcrException("PDF has no pages")
                val texts = mutableListOf<String>()
                val count = min(renderer.pageCount, MAX_PDF_PAGES)
                for (index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val scale = min(
                            2.5f,
                            PDF_RENDER_MAX_SIDE.toFloat() / max(page.width, page.height).toFloat(),
                        ).coerceAtLeast(1f)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try {
                            val text = OcrEngine.recognize(context, bitmap)
                            if (text.isNotBlank()) texts.add(text)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (texts.sumOf { it.length } > 200_000) break
                }
                val result = texts.joinToString("\n\n").trim()
                if (result.isBlank()) throw OcrException("No readable text found in PDF")
                result
            } finally {
                renderer.close()
            }
        }
    }
}
