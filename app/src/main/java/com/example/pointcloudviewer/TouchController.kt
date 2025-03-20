package com.example.pointcloudviewer

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.atan2

/**
 * TouchController handles all touch gestures for the point cloud viewer,
 * including rotation, scaling (zoom), and translation (pan).
 */
class TouchController(
    context: Context,
    private val onRotation: (Float, Float) -> Unit,
    private val onScale: (Float) -> Unit,
    private val onTranslation: (Float, Float) -> Unit,
    private val onReset: () -> Unit
) {
    // Detector for scaling (pinch zoom)
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onScale(detector.scaleFactor)
            return true
        }
    })

    // For detecting rotation
    private var previousX = 0f
    private var previousY = 0f
    private var initialDistance = 0f
    private var initialAngle = 0f

    // Detector for single finger gestures like fling and double tap
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 處理雙擊事件 - 重置視圖
            onReset()
            return true
        }
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // For two-finger pan
            if (e2.pointerCount == 2) {
                onTranslation(-distanceX, -distanceY)
                return true
            }

            // For one-finger rotation
            if (e2.pointerCount == 1) {
                val x = e2.x
                val y = e2.y
                val dx = x - previousX
                val dy = y - previousY

                // 增加靈敏度並保持自然感
                onRotation(dy * 0.7f, dx * 0.7f) // 增加靈敏度並保持自然感

                previousX = x
                previousY = y
                return true
            }
            return false
        }
    })

    fun onTouchEvent(event: MotionEvent): Boolean {
        // Handle pinch zoom
        scaleGestureDetector.onTouchEvent(event)

        // Handle other gestures
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // Two fingers just went down, record initial positions for possible rotation detection
                    initialDistance = getDistance(event)
                    initialAngle = getAngle(event)
                }
            }
        }

        return true
    }

    // Helper method to get the distance between two touch points
    private fun getDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // Helper method to get the angle between two touch points
    private fun getAngle(event: MotionEvent): Float {
        return atan2(
            event.getY(0) - event.getY(1),
            event.getX(0) - event.getX(1)
        )
    }
}