package com.cueflow.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Local image/scanned-PDF OCR entry points. */
object OcrImporter {
    private const val MAX_IMAGE_SIDE = 2_200
    private const val MAX_PDF_PAGES = 30
    private const val PDF_RENDER_MAX_SIDE = 2_200

    fun recognizeImage(context: Context, uri: Uri): String {
        // Copy once. Some content providers allow only one open stream, and EXIF
        // needs a seekable file for reliable orientation handling.
        val temporary = File.createTempFile("cueflow-ocr-", ".img", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: throw OcrException("Cannot open image")
            if (temporary.length() == 0L) throw OcrException("Cannot decode image")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
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
            val decoded = BitmapFactory.decodeFile(temporary.absolutePath, options)
                ?: throw OcrException("Cannot decode image")
            val oriented = applyExifOrientation(temporary, decoded)
            var result: String? = null
            try {
                result = runCatching { OcrEngine.recognize(context, oriented) }.getOrNull()
                if (result.isNullOrBlank()) {
                    // EXIF is missing or wrong on some camera providers. Retry the
                    // other cardinal rotations before giving up.
                    for (angle in intArrayOf(90, 180, 270)) {
                        val rotated = rotate(oriented, angle)
                        try {
                            val candidate = runCatching {
                                OcrEngine.recognize(context, rotated)
                            }.getOrNull()
                            if (!candidate.isNullOrBlank()) {
                                result = candidate
                                break
                            }
                        } finally {
                            if (rotated !== oriented) rotated.recycle()
                        }
                    }
                }
            } finally {
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
            return result
                ?.takeIf { it.isNotBlank() }
                ?: throw OcrException("No readable text found")
        } finally {
            temporary.delete()
        }
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val oriented = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            if (oriented !== bitmap) bitmap.recycle()
            oriented
        }.getOrDefault(bitmap)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
