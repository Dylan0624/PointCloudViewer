package com.example.pointcloudviewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class LegendView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    // mode: 0 = 強度, 1 = 隱藏（不繪製）
    var mode: Int = 0
        set(value) {
            field = value
            updateGradient()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: LinearGradient? = null

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = 30f
        textPaint.textAlign = Paint.Align.LEFT
        updateGradient() // 初始化時設置漸變
    }

    private fun updateGradient() {
        when (mode) {
            0 -> {
                // 強度模式：紅 -> 橙 -> 黃 -> 綠 -> 藍
                gradient = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    intArrayOf(Color.RED, Color.parseColor("#FF8000"), Color.YELLOW, Color.GREEN, Color.BLUE),
                    null, Shader.TileMode.CLAMP
                )
            }
            else -> {
                gradient = null // 隱藏模式
            }
        }
        paint.shader = gradient
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateGradient() // 當尺寸改變時更新漸變
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gradient == null) return // mode != 0 時隱藏

        // 繪製漸層條（長方形）
        canvas.drawRect(0f, 0f, width.toFloat(), height / 2f, paint)

        // 文字標示
        val leftLabel = "強" // 紅色表示強
        val rightLabel = "弱" // 藍色表示弱

        // 繪製左邊文字
        canvas.drawText(leftLabel, 5f, height.toFloat() - 10f, textPaint)

        // 繪製右邊文字
        val textWidth = textPaint.measureText(rightLabel)
        canvas.drawText(rightLabel, width - textWidth - 5f, height.toFloat() - 10f, textPaint)
    }
}