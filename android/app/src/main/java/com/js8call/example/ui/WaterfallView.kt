package com.js8call.example.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that displays a scrolling waterfall visualization of spectrum data.
 * Emulates the JS8Call waterfall display algorithm.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 0, 0)
        style = Paint.Style.FILL
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var renderer: WaterfallRenderer? = null

    // TX offset tracking
    var txOffsetHz: Float = 1500f
        set(value) {
            field = value.coerceAtLeast(500f)
            postInvalidate()
        }
    // Callback for offset changes
    var onOffsetChanged: ((Float) -> Unit)? = null

    init {
        isClickable = true
    }

    fun bindRenderer(renderer: WaterfallRenderer?) {
        this.renderer?.setInvalidateCallback(null)
        this.renderer = renderer
        this.renderer?.setInvalidateCallback { postInvalidateOnAnimation() }
        if (renderer != null && width > 0 && height > 0) {
            renderer.setSize(width, height)
        }
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer?.setSize(w, h)
    }

    fun clear() {
        renderer?.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = renderer?.getBitmap()
        if (bitmap == null) {
            canvas.drawColor(Color.BLACK)
            return
        }

        // Draw the waterfall bitmap scaled to fill the view
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        // Draw TX offset marker bar (50Hz bandwidth for normal mode)
        val currentBinHz = renderer?.getCurrentBinHz() ?: 0f
        val displayStartBin = renderer?.getDisplayStartBin() ?: 0
        val displayEndBin = renderer?.getDisplayEndBin() ?: -1
        if (currentBinHz > 0 && displayEndBin > displayStartBin) {
            val usableBins = displayEndBin - displayStartBin
            val binIndex = (txOffsetHz / currentBinHz).toInt()
            val displayBinIndex = binIndex - displayStartBin

            if (displayBinIndex in 0 until usableBins) {
                val pixelsPerBin = width.toFloat() / usableBins
                val xPosition = displayBinIndex * pixelsPerBin

                // Calculate bar width for 50Hz filter bandwidth (25Hz on each side)
                val bandwidthBins = 50f / currentBinHz
                val barWidth = bandwidthBins * pixelsPerBin

                // Draw vertical bar marker centered on offset
                canvas.drawRect(
                    xPosition - barWidth / 2f,
                    0f,
                    xPosition + barWidth / 2f,
                    height.toFloat(),
                    markerPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val currentBinHz = renderer?.getCurrentBinHz() ?: 0f
                val displayStartBin = renderer?.getDisplayStartBin() ?: 0
                val displayEndBin = renderer?.getDisplayEndBin() ?: -1
                if (currentBinHz > 0 && displayEndBin > displayStartBin) {
                    val usableBins = displayEndBin - displayStartBin
                    val pixelsPerBin = width.toFloat() / usableBins
                    val xPosition = event.x.coerceIn(0f, width.toFloat())
                    val binIndex = (xPosition / pixelsPerBin).toInt()
                    val actualBinIndex = binIndex + displayStartBin
                    val newOffsetHz = actualBinIndex * currentBinHz

                    txOffsetHz = newOffsetHz
                    onOffsetChanged?.invoke(txOffsetHz)
                    android.util.Log.d("WaterfallView", "TX offset set to $txOffsetHz Hz at x=$xPosition (bin=$actualBinIndex)")
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer?.setInvalidateCallback { postInvalidateOnAnimation() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer?.setInvalidateCallback(null)
        renderer?.stopDrawing()
    }
}
