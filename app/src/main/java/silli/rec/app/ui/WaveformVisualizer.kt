package silli.rec.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import silli.rec.app.R
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class WaveformVisualizer(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2f
    }

    private val waveformData = mutableListOf<Float>()
    private var animationProgress = 0f
    private var isRecording = false
    private val random = Random(System.currentTimeMillis())

    init {
        // Initialize with some baseline data
        repeat(100) {
            waveformData.add(0.3f)
        }
    }

    fun setRecording(recording: Boolean) {
        isRecording = recording
        if (!recording) {
            waveformData.clear()
            repeat(100) {
                waveformData.add(0.3f)
            }
        }
        invalidate()
    }

    fun updateWaveform(amplitude: Float) {
        if (waveformData.size >= 200) {
            waveformData.removeAt(0)
        }
        val clampedAmplitude = (amplitude / 32768f).coerceIn(0.05f, 1f)
        waveformData.add(clampedAmplitude)

        animationProgress = (animationProgress + 0.15f) % (2 * Math.PI).toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Draw background
        canvas.drawRect(0f, 0f, width, height, Paint().apply {
            color = ContextCompat.getColor(context, R.color.primary_dark_bg)
        })

        if (waveformData.isEmpty()) return

        // Draw waveform
        paint.color = ContextCompat.getColor(context, R.color.primary_green)

        val pointSpacing = width / waveformData.size
        var lastX = 0f
        var lastY = centerY

        for (i in waveformData.indices) {
            val x = i * pointSpacing
            val amplitude = waveformData[i]

            // Add some animation wobble when recording
            val wobble = if (isRecording) {
                sin((i + animationProgress) * 0.05f) * 10
            } else {
                0f
            }

            val y = centerY - (amplitude * (centerY - 20) * (1 + wobble / 100))

            if (i > 0) {
                canvas.drawLine(lastX, lastY, x, y, paint)
            }

            lastX = x
            lastY = y
        }

        // Draw mirror effect below center line
        paint.color = ContextCompat.getColor(context, R.color.primary_green_dark)
        paint.alpha = 128

        lastX = 0f
        lastY = centerY

        for (i in waveformData.indices) {
            val x = i * pointSpacing
            val amplitude = waveformData[i]

            val wobble = if (isRecording) {
                sin((i + animationProgress) * 0.05f) * 10
            } else {
                0f
            }

            val y = centerY + (amplitude * (centerY - 20) * (1 + wobble / 100))

            if (i > 0) {
                canvas.drawLine(lastX, lastY, x, y, paint)
            }

            lastX = x
            lastY = y
        }

        // Draw glowing center line
        paint.color = ContextCompat.getColor(context, R.color.primary_green)
        paint.alpha = 80
        paint.strokeWidth = 1f
        canvas.drawLine(0f, centerY, width, centerY, paint)
    }
}
