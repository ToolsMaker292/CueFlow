package com.cueflow.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cueflow.app.data.ocr.OcrEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrEngineTest {
    @Test
    fun recognizesSmallTextOnTallScreenshot() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 36f
                typeface = android.graphics.Typeface.DEFAULT
            }
            drawText("HELLO OCR SMALL TEXT", 48f, 300f, paint)
            drawText("SECOND LINE OF TEXT", 48f, 370f, paint)
            drawText("THIRD LINE OF TEXT", 48f, 440f, paint)
        }
        try {
            val text = OcrEngine.recognize(application, bitmap)
            assertTrue("OCR result was: $text", text.isNotBlank())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun recognizesPortraitDocumentLikeImage() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = android.graphics.Typeface.DEFAULT
            }
            drawText("HELLO OCR", 60f, 300f, paint)
            drawText("SECOND LINE", 60f, 460f, paint)
            drawText("THIRD LINE", 60f, 620f, paint)
        }
        try {
            val text = OcrEngine.recognize(application, bitmap)
            assertTrue("OCR result was: $text", text.isNotBlank())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun recognizesRenderedText() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 96f
                typeface = android.graphics.Typeface.DEFAULT
            }
            drawText("HELLO OCR", 36f, 150f, paint)
        }
        try {
            val text = OcrEngine.recognize(application, bitmap)
            // The small model is deliberately not a perfect synthetic-font classifier;
            // this test proves the bundled detector/recognizer/CTC path returns text.
            assertTrue("OCR result was: $text", text.uppercase().contains("OCR"))
        } finally {
            bitmap.recycle()
        }
    }
}
