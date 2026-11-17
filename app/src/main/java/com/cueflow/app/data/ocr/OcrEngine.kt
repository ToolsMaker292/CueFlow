package com.cueflow.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.tensorflow.lite.Interpreter

class OcrException(message: String) : Exception(message)

/**
 * Offline PP-OCRv6 small pipeline (detection + single-line recognition).
 *
 * The two Apache-2.0 TFLite models and the 18,710-entry dictionary are bundled
 * in assets. Inference uses TensorFlow Lite CPU with two threads; no network,
 * no downloaded models, no cloud fallback.
 *
 * The detection stage uses a conservative row/column projection instead of the
 * full DB polygon/unclip pipeline. It is tuned for upright document scans and
 * screenshots; rotated or perspective-heavy photos may need better geometry.
 */
object OcrEngine {
    private const val DET_SIZE = 640
    private const val REC_WIDTH = 640
    private const val DET_THRESHOLD = 0.2f
    private const val MIN_BOX_WIDTH = 8
    private const val MIN_BOX_HEIGHT = 5
    private const val MAX_REGION_TEXT = 200_000
    private const val MAX_REGIONS = 48

    private const val DET_BYTES = 12_380_664L
    private const val REC_BYTES = 21_477_068L
    private const val DICT_BYTES = 168_510L
    private const val VOCABULARY_SIZE = 18_710

    @Volatile
    private var vocabulary: List<String>? = null

    @Synchronized
    fun recognize(context: Context, bitmap: Bitmap): String {
        if (bitmap.width < 8 || bitmap.height < 8) {
            throw OcrException("Image is too small for OCR")
        }
        val modelDirectory = ensureModels(context)
        val words = loadVocabulary(File(modelDirectory, "characters.json"))
        val options = Interpreter.Options().apply { setNumThreads(2) }
        val detector = Interpreter(File(modelDirectory, "det.tflite"), options)
        val recognizer = Interpreter(File(modelDirectory, "rec.tflite"), options)
        try {
            val prepared = prepareDetectorInput(bitmap)
            val heatmap = runModel(
                detector,
                OcrPreprocessing.det(prepared.rgb),
                DET_SIZE * DET_SIZE,
            )
            val regions = detectRegions(heatmap)
                .sortedWith(compareBy({ it.top }, { it.left }))
                .take(MAX_REGIONS)
                .ifEmpty { fallbackWholeImage(bitmap) }
            if (regions.isEmpty()) throw OcrException("No text region detected")

            val lines = mutableListOf<String>()
            for (region in regions) {
                val padding = 4
                val left = ((region.left - prepared.left) / prepared.scale)
                    .roundToInt().minus(padding).coerceIn(0, bitmap.width - 1)
                val top = ((region.top - prepared.top) / prepared.scale)
                    .roundToInt().minus(padding).coerceIn(0, bitmap.height - 1)
                val right = ((region.right - prepared.left) / prepared.scale)
                    .roundToInt().plus(padding).coerceIn(left + 1, bitmap.width)
                val bottom = ((region.bottom - prepared.top) / prepared.scale)
                    .roundToInt().plus(padding).coerceIn(top + 1, bitmap.height)
                val text = recognizeRegion(bitmap, left, top, right, bottom, recognizer, words)
                if (text.isNotBlank()) lines.add(text)
                if (lines.sumOf { it.length } > MAX_REGION_TEXT) break
            }
            val result = lines.joinToString("\n").trim()
            if (result.isBlank()) throw OcrException("No readable text found")
            return result
        } finally {
            runCatching { detector.close() }
            runCatching { recognizer.close() }
        }
    }

    private fun ensureModels(context: Context): File {
        val directory = File(context.filesDir, "ocr-models-v2")
        if (!directory.exists() && !directory.mkdirs()) {
            throw OcrException("Cannot create OCR model directory")
        }
        copyAssetIfNeeded(context, "ocr/det.tflite", File(directory, "det.tflite"), DET_BYTES)
        copyAssetIfNeeded(context, "ocr/rec.tflite", File(directory, "rec.tflite"), REC_BYTES)
        copyAssetIfNeeded(
            context,
            "ocr/characters.json",
            File(directory, "characters.json"),
            DICT_BYTES,
        )
        return directory
    }

    private fun copyAssetIfNeeded(
        context: Context,
        assetName: String,
        target: File,
        expectedBytes: Long,
    ) {
        if (target.exists() && target.length() == expectedBytes) return
        val temporary = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(assetName).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (temporary.length() != expectedBytes) {
            temporary.delete()
            throw OcrException("Bundled OCR model is incomplete: $assetName")
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw OcrException("Cannot install OCR model: $assetName")
        }
    }

    private fun loadVocabulary(file: File): List<String> {
        vocabulary?.let { return it }
        val array = JSONArray(file.readText(Charsets.UTF_8))
        if (array.length() != VOCABULARY_SIZE) {
            throw OcrException("Unexpected OCR character dictionary")
        }
        val result = List(array.length()) { array.getString(it) }
        if (result.first() != "blank" || result.last() != " ") {
            throw OcrException("Unexpected OCR character dictionary")
        }
        vocabulary = result
        return result
    }

    private data class PreparedImage(
        val rgb: ByteArray,
        val scale: Float,
        val left: Int,
        val top: Int,
    )

    private fun prepareDetectorInput(bitmap: Bitmap): PreparedImage {
        val scale = min(
            DET_SIZE.toFloat() / bitmap.width.toFloat(),
            DET_SIZE.toFloat() / bitmap.height.toFloat(),
        )
        val width = (bitmap.width * scale).roundToInt().coerceIn(1, DET_SIZE)
        val height = (bitmap.height * scale).roundToInt().coerceIn(1, DET_SIZE)
        val left = (DET_SIZE - width) / 2
        val top = (DET_SIZE - height) / 2
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val square = Bitmap.createBitmap(DET_SIZE, DET_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
        }
        val rgb = rgbBytes(square)
        if (scaled !== bitmap) scaled.recycle()
        square.recycle()
        return PreparedImage(rgb, scale, left, top)
    }

    private fun runModel(
        interpreter: Interpreter,
        input: FloatArray,
        outputElements: Int,
    ): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(input.size * 4)
            .order(ByteOrder.nativeOrder())
        inputBuffer.asFloatBuffer().put(input)
        inputBuffer.rewind()
        val outputBuffer = ByteBuffer.allocateDirect(outputElements * 4)
            .order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        val output = FloatArray(outputElements)
        outputBuffer.asFloatBuffer().get(output)
        return output
    }

    private data class TextRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val score: Float,
    )

    private fun detectRegions(heatmap: FloatArray): List<TextRegion> {
        if (heatmap.size != DET_SIZE * DET_SIZE) return emptyList()
        val regions = mutableListOf<TextRegion>()
        val rowActive = BooleanArray(DET_SIZE)
        for (y in 0 until DET_SIZE) {
            var count = 0
            for (x in 0 until DET_SIZE) {
                if (heatmap[y * DET_SIZE + x] >= DET_THRESHOLD) count++
            }
            rowActive[y] = count >= 2
        }

        var y = 0
        while (y < DET_SIZE) {
            if (!rowActive[y]) {
                y++
                continue
            }
            val bandStart = y
            var bandEnd = y + 1
            while (bandEnd < DET_SIZE && rowActive[bandEnd]) bandEnd++
            if (bandEnd - bandStart >= MIN_BOX_HEIGHT) {
                regions.addAll(columnSegments(heatmap, bandStart, bandEnd))
            }
            y = bandEnd
        }
        return regions
    }

    private fun columnSegments(
        heatmap: FloatArray,
        bandStart: Int,
        bandEnd: Int,
    ): List<TextRegion> {
        val columnActive = BooleanArray(DET_SIZE)
        for (x in 0 until DET_SIZE) {
            var count = 0
            for (y in bandStart until bandEnd) {
                if (heatmap[y * DET_SIZE + x] >= DET_THRESHOLD) count++
            }
            columnActive[x] = count >= 1
        }
        val result = mutableListOf<TextRegion>()
        var x = 0
        while (x < DET_SIZE) {
            while (x < DET_SIZE && !columnActive[x]) x++
            if (x >= DET_SIZE) break
            val start = x
            var lastActive = x
            var cursor = x + 1
            while (cursor < DET_SIZE) {
                if (columnActive[cursor]) {
                    lastActive = cursor
                    cursor++
                } else {
                    var gapEnd = cursor
                    while (gapEnd < DET_SIZE && !columnActive[gapEnd]) gapEnd++
                    if (gapEnd - cursor <= 60) {
                        cursor = gapEnd
                    } else {
                        break
                    }
                }
            }
            val end = lastActive + 1
            val width = end - start
            val height = bandEnd - bandStart
            if (width >= MIN_BOX_WIDTH && height >= MIN_BOX_HEIGHT) {
                val paddedLeft = (start - 3).coerceAtLeast(0)
                val paddedTop = (bandStart - 3).coerceAtLeast(0)
                val paddedRight = (end + 3).coerceAtMost(DET_SIZE)
                val paddedBottom = (bandEnd + 3).coerceAtMost(DET_SIZE)
                var score = 0.0
                var count = 0
                for (yy in bandStart until bandEnd) {
                    for (xx in start until end) {
                        score += heatmap[yy * DET_SIZE + xx]
                        count++
                    }
                }
                result.add(
                    TextRegion(
                        paddedLeft,
                        paddedTop,
                        paddedRight,
                        paddedBottom,
                        if (count > 0) (score / count).toFloat() else 0f,
                    ),
                )
            }
            x = if (cursor > lastActive) cursor else lastActive + 1
        }
        return result
    }

    private fun fallbackWholeImage(bitmap: Bitmap): List<TextRegion> {
        if (bitmap.height > bitmap.width * 1.2f) return emptyList()
        return listOf(TextRegion(0, 0, DET_SIZE, DET_SIZE, 0f))
    }

    private fun recognizeRegion(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        recognizer: Interpreter,
        words: List<String>,
    ): String {
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        return try {
            val rgb = rgbBytes(crop)
            val naturalWidth = ceil(48.0 * width / height).toInt().coerceAtLeast(1)
            if (naturalWidth <= REC_WIDTH) {
                recognizeStripe(recognizer, rgb, width, height, words)
            } else {
                val scaledWidth = naturalWidth.coerceAtMost(4_000)
                val resized = TorchUint8Resize.resize(rgb, width, height, scaledWidth, 48)
                val chunkWidth = 600
                val overlap = 16
                val chunks = mutableListOf<String>()
                var start = 0
                while (start < scaledWidth) {
                    val end = (start + chunkWidth).coerceAtMost(scaledWidth)
                    val chunk = sliceRgb(resized, scaledWidth, start, end)
                    val text = recognizeStripe(
                        recognizer,
                        chunk,
                        end - start,
                        48,
                        words,
                    )
                    if (text.isNotBlank()) chunks.add(text)
                    if (end >= scaledWidth) break
                    start = (end - overlap).coerceAtLeast(start + 1)
                }
                chunks.joinToString(" ").trim()
            }
        } finally {
            crop.recycle()
        }
    }

    private fun recognizeStripe(
        recognizer: Interpreter,
        rgb: ByteArray,
        width: Int,
        height: Int,
        words: List<String>,
    ): String {
        val input = OcrPreprocessing.rec(rgb, width, height, REC_WIDTH)
        val output = runModel(recognizer, input, 80 * VOCABULARY_SIZE)
        return decodeCtc(output, words)
    }

    private fun decodeCtc(probabilities: FloatArray, words: List<String>): String {
        val text = StringBuilder()
        var previous = -1
        val timesteps = probabilities.size / VOCABULARY_SIZE
        for (time in 0 until timesteps) {
            val offset = time * VOCABULARY_SIZE
            var best = 0
            for (index in 1 until VOCABULARY_SIZE) {
                if (probabilities[offset + index] > probabilities[offset + best]) best = index
            }
            if (best != previous && best != 0) text.append(words[best])
            previous = best
        }
        return text.toString()
    }

    private fun sliceRgb(source: ByteArray, sourceWidth: Int, start: Int, end: Int): ByteArray {
        val width = end - start
        val output = ByteArray(width * 48 * 3)
        for (y in 0 until 48) {
            val sourceRow = (y * sourceWidth + start) * 3
            val outputRow = y * width * 3
            source.copyInto(output, outputRow, sourceRow, sourceRow + width * 3)
        }
        return output
    }

    private fun rgbBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(width * height * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            rgb[index * 3] = ((pixel shr 16) and 0xFF).toByte()
            rgb[index * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[index * 3 + 2] = (pixel and 0xFF).toByte()
        }
        return rgb
    }
}
