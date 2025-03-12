package com.example.pointcloudviewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class LegendView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    // mode: 0 = 強度, 1 = 深度, 2 = 隱藏（不繪製）
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
    }

    private fun updateGradient() {
        when (mode) {
            0 -> {
                // 強度模式：從綠到黃到紅
                gradient = LinearGradient(0f, 0f, width.toFloat(), 0f,
                    intArrayOf(Color.GREEN, Color.YELLOW, Color.RED),
                    null, Shader.TileMode.CLAMP)
            }
            1 -> {
                // 深度模式：從藍到紫到紅
                gradient = LinearGradient(0f, 0f, width.toFloat(), 0f,
                    intArrayOf(Color.BLUE, Color.MAGENTA, Color.RED),
                    null, Shader.TileMode.CLAMP)
            }
            else -> {
                gradient = null
            }
        }
        paint.shader = gradient
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateGradient()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gradient == null) return  // mode==2 隱藏圖例
        // 繪製漸層條
        canvas.drawRect(0f, 0f, width.toFloat(), height / 2f, paint)
        // 根據模式選擇文字標示：
        val leftLabel = when(mode) {
            0 -> "弱"
            1 -> "深"
            else -> ""
        }
        val rightLabel = when(mode) {
            0 -> "強"
            1 -> "淺"
            else -> ""
        }
        // 繪製文字（調整 y 座標避免碰到邊緣）
        canvas.drawText(leftLabel, 5f, height.toFloat() - 20f, textPaint)
        val textWidth = textPaint.measureText(rightLabel)
        canvas.drawText(rightLabel, width - textWidth - 5f, height.toFloat() - 20f, textPaint)
    }
}
