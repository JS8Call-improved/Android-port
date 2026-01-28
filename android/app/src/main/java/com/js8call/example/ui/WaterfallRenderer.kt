package com.js8call.example.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper

/**
 * Offscreen renderer that maintains waterfall bitmap state across view lifecycles.
 */
class WaterfallRenderer {

    private var waterfallBitmap: Bitmap? = null
    private var waterfallCanvas: Canvas? = null
    private var tempBitmap: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var width = 0
    private var height = 0

    // Display range tracking
    private var currentBinHz: Float = 0f
    private var displayStartBin: Int = 0
    private var displayEndBin: Int = -1

    // Drawing rate control
    private val drawHandler = Handler(Looper.getMainLooper())
    private val drawIntervalMs = 50L
    private var isDrawing = false
    private var pendingSpectrum: FloatArray? = null

    private val drawRunnable = object : Runnable {
        override fun run() {
            val spectrum = pendingSpectrum
            if (spectrum != null && canDraw()) {
                pendingSpectrum = null
                drawSpectrum(spectrum)
                if (isDrawing) {
                    drawHandler.postDelayed(this, drawIntervalMs)
                }
            } else {
                isDrawing = false
            }
        }
    }

    private var invalidateCallback: (() -> Unit)? = null

    // Color palette (256 colors, matching JS8Call style)
    private val colorPalette = IntArray(256)

    init {
        initColorPalette()
    }

    fun setInvalidateCallback(callback: (() -> Unit)?) {
        invalidateCallback = callback
    }

    fun setSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == width && h == height && waterfallBitmap != null && tempBitmap != null) return

        width = w
        height = h

        val oldWaterfall = waterfallBitmap
        val oldTemp = tempBitmap

        waterfallBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tempBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        waterfallCanvas = Canvas(waterfallBitmap!!)
        waterfallCanvas?.drawColor(Color.BLACK)

        if (oldWaterfall != null && !oldWaterfall.isRecycled) {
            val srcRect = Rect(0, 0, oldWaterfall.width, oldWaterfall.height)
            val dstRect = Rect(0, 0, w, h)
            waterfallCanvas?.drawBitmap(oldWaterfall, srcRect, dstRect, null)
            oldWaterfall.recycle()
        }

        oldTemp?.recycle()
    }

    fun updateSpectrum(bins: FloatArray, binHz: Float) {
        if (bins.isEmpty()) return

        currentBinHz = binHz
        pendingSpectrum = bins.copyOf()

        if (!isDrawing) {
            isDrawing = true
            drawHandler.post(drawRunnable)
        }
    }

    fun clear() {
        isDrawing = false
        drawHandler.removeCallbacks(drawRunnable)
        pendingSpectrum = null
        drawCount = 0
        startBin = 0
        endBin = -1
        displayStartBin = 0
        displayEndBin = -1

        autoDbMin = -100f
        autoDbMax = 20f
        dbMinHistory.clear()
        dbMaxHistory.clear()

        waterfallCanvas?.drawColor(Color.BLACK)
        invalidateCallback?.invoke()
    }

    fun stopDrawing() {
        isDrawing = false
        drawHandler.removeCallbacks(drawRunnable)
    }

    fun release() {
        clear()
        waterfallBitmap?.recycle()
        waterfallBitmap = null
        tempBitmap?.recycle()
        tempBitmap = null
        waterfallCanvas = null
    }

    fun getBitmap(): Bitmap? = waterfallBitmap

    fun getCurrentBinHz(): Float = currentBinHz

    fun getDisplayStartBin(): Int = displayStartBin

    fun getDisplayEndBin(): Int = displayEndBin

    private fun canDraw(): Boolean {
        return width > 0 && height > 0 && waterfallBitmap != null && tempBitmap != null && waterfallCanvas != null
    }

    private fun initColorPalette() {
        for (i in 0 until 256) {
            val normalized = i / 255f

            colorPalette[i] = when {
                normalized < 0.15f -> {
                    val t = normalized / 0.15f
                    val b = (t * 128).toInt()
                    Color.rgb(0, 0, b)
                }
                normalized < 0.30f -> {
                    val t = (normalized - 0.15f) / 0.15f
                    val b = (128 + t * 127).toInt()
                    Color.rgb(0, 0, b)
                }
                normalized < 0.45f -> {
                    val t = (normalized - 0.30f) / 0.15f
                    val g = (t * 255).toInt()
                    Color.rgb(0, g, 255)
                }
                normalized < 0.60f -> {
                    val t = (normalized - 0.45f) / 0.15f
                    val b = ((1 - t) * 255).toInt()
                    Color.rgb(0, 255, b)
                }
                normalized < 0.75f -> {
                    val t = (normalized - 0.60f) / 0.15f
                    val r = (t * 255).toInt()
                    Color.rgb(r, 255, 0)
                }
                normalized < 0.85f -> {
                    val t = (normalized - 0.75f) / 0.10f
                    val g = ((1 - t * 0.5) * 255).toInt()
                    Color.rgb(255, g, 0)
                }
                else -> {
                    val t = (normalized - 0.85f) / 0.15f
                    val g = ((1 - t) * 128).toInt()
                    Color.rgb(255, g, 0)
                }
            }
        }
    }

    private fun drawSpectrum(bins: FloatArray) {
        val bitmap = waterfallBitmap ?: return
        val temp = tempBitmap ?: return
        val canvas = waterfallCanvas ?: return

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        if (drawCount < 10) {
            if (drawCount == 0) {
                val sampleIndices = listOf(0, bins.size / 4, bins.size / 2, 3 * bins.size / 4, bins.size - 1)
                val samples = sampleIndices.map { "bins[$it]=${bins[it]}" }.joinToString(", ")
                android.util.Log.d("WaterfallView", "Raw spectrum sample: $samples")
                android.util.Log.d(
                    "WaterfallView",
                    "Spectrum size: ${bins.size}, min=${bins.minOrNull()}, max=${bins.maxOrNull()}, avg=${bins.average()}"
                )
            }

            val mid = bins.size / 2
            val leftHalf = bins.slice(0 until mid)
            val rightHalf = bins.slice(mid until bins.size)
            val leftAvg = leftHalf.average()
            val rightAvg = rightHalf.average()

            if (rightAvg * 10 < leftAvg) {
                if (startBin == 0 && endBin == -1) {
                    android.util.Log.d(
                        "WaterfallView",
                        "Right half is mostly noise: leftAvg=$leftAvg, rightAvg=$rightAvg. Using left half only."
                    )
                    endBin = mid
                }
            } else if (leftAvg * 10 < rightAvg) {
                if (startBin == 0) {
                    android.util.Log.d(
                        "WaterfallView",
                        "Left half is mostly noise: leftAvg=$leftAvg, rightAvg=$rightAvg. Using right half only."
                    )
                    startBin = mid
                }
            }
            drawCount++
        }

        val tempCanvas = Canvas(temp)
        tempCanvas.drawColor(Color.BLACK)
        val srcRect = Rect(0, 0, bitmapWidth, bitmapHeight - 1)
        val dstRect = Rect(0, 1, bitmapWidth, bitmapHeight)
        tempCanvas.drawBitmap(bitmap, srcRect, dstRect, null)

        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(temp, 0f, 0f, null)

        val actualEndBin = if (endBin > 0) endBin else bins.size
        displayStartBin = startBin
        displayEndBin = actualEndBin
        val usableBins = actualEndBin - startBin
        val pixelsPerBin = bitmapWidth.toFloat() / usableBins

        var minDb = Float.MAX_VALUE
        var maxDb = Float.MIN_VALUE
        var avgDb = 0f
        val colorCount = IntArray(256)

        for (i in startBin until actualEndBin) {
            val linearValue = bins[i]
            val dbValue = if (linearValue > 1e-10f) {
                10f * kotlin.math.ln(linearValue) / kotlin.math.ln(10f)
            } else {
                -100f
            }

            if (dbValue > maxDb) maxDb = dbValue
            if (dbValue < minDb) minDb = dbValue
            avgDb += dbValue

            val normalized = ((dbValue - autoDbMin) / (autoDbMax - autoDbMin)).coerceIn(0f, 1f)
            val colorIndex = (normalized * 254).toInt().coerceIn(0, 254)
            val color = colorPalette[colorIndex]

            colorCount[colorIndex]++

            paint.color = color
            val binIndex = i - startBin
            val x1 = (binIndex * pixelsPerBin).toInt()
            val x2 = ((binIndex + 1) * pixelsPerBin).toInt().coerceAtMost(bitmapWidth)

            if (x2 > x1) {
                canvas.drawRect(x1.toFloat(), 0f, x2.toFloat(), 1f, paint)
            }
        }

        avgDb /= usableBins

        dbMinHistory.add(minDb)
        dbMaxHistory.add(maxDb)
        if (dbMinHistory.size > historySize) dbMinHistory.removeAt(0)
        if (dbMaxHistory.size > historySize) dbMaxHistory.removeAt(0)

        val sortedMin = dbMinHistory.sorted()
        val sortedMax = dbMaxHistory.sorted()
        val smoothedMin = sortedMin[sortedMin.size / 10]
        val smoothedMax = sortedMax[(sortedMax.size * 9) / 10]

        autoDbMin = (smoothedMin - 10f).coerceAtLeast(-100f)
        autoDbMax = (smoothedMax + 10f).coerceAtMost(100f)

        if (autoDbMax - autoDbMin < 30f) {
            val center = (autoDbMin + autoDbMax) / 2f
            autoDbMin = center - 15f
            autoDbMax = center + 15f
        }

        if (drawCount % 30 == 0) {
            val significantColors = mutableListOf<Pair<Int, Int>>()
            for (i in colorCount.indices) {
                if (colorCount[i] > 10) {
                    significantColors.add(Pair(i, colorCount[i]))
                }
            }

            android.util.Log.d(
                "WaterfallView",
                "Spectrum stats: dB range=[%.1f, %.1f], avg=%.1f dB, usableBins=%d/%d, range=[%d, %d), autoRange=[%.1f, %.1f]"
                    .format(minDb, maxDb, avgDb, usableBins, bins.size, startBin, actualEndBin, autoDbMin, autoDbMax)
            )

            if (significantColors.isNotEmpty()) {
                android.util.Log.d(
                    "WaterfallView",
                    "Color distribution: ${significantColors.take(5).joinToString { "(idx=${it.first}, cnt=${it.second})" }}"
                )
            }
        }

        invalidateCallback?.invoke()
    }

    private var drawCount = 0
    private var startBin = 0
    private var endBin = -1

    private var autoDbMin = -100f
    private var autoDbMax = 20f
    private val dbMinHistory = mutableListOf<Float>()
    private val dbMaxHistory = mutableListOf<Float>()
    private val historySize = 20
}
