package com.example.pointcloudviewer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.TextView
import java.util.concurrent.Executors

class PointCloudViewManager(
    private val context: Context,
    private val glSurfaceView: GLSurfaceView,
    private val renderer: PointCloudRenderer,
    private val pointDetailTextView: TextView
) {
    companion object {
        private const val TAG = "PointCloudViewManager"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isUsingSimulatedData = false

    init {
        setupGLSurfaceView()
        setupGestureDetectors()
    }

    private fun setupGLSurfaceView() {
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glSurfaceView.setOnTouchListener { _, event ->
            val scaleHandled = scaleGestureDetector.onTouchEvent(event)
            val gestureHandled = gestureDetector.onTouchEvent(event)
            scaleHandled || gestureHandled
        }
    }

    private fun setupGestureDetectors() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                Log.d(TAG, "onScroll: pointerCount=${e2.pointerCount}, dx=$distanceX, dy=$distanceY")
                if (e2.pointerCount == 1) {
                    renderer.rotate(distanceX, distanceY)
                } else {
                    renderer.translate(-distanceX, distanceY)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "onDoubleTap triggered")
                renderer.resetView()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val touchX = e.x
                val touchY = e.y
                glSurfaceView.queueEvent {
                    val pickedPoint = renderer.pickPoint(touchX, touchY, glSurfaceView.width, glSurfaceView.height)
                    (context as MainActivity).runOnUiThread {
                        if (pickedPoint != null) {
                            val detailText = "位置: (%.2f, %.2f, %.2f)\n法向量: (%.2f, %.2f, %.2f)\n強度: %.2f".format(
                                pickedPoint[0], pickedPoint[1], pickedPoint[2],
                                pickedPoint[4], pickedPoint[5], pickedPoint[6],
                                pickedPoint[3]
                            )
                            pointDetailTextView.text = detailText
                        } else {
                            pointDetailTextView.text = "未選取到點"
                        }
                    }
                }
            }
        })

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                Log.d(TAG, "onScale: scaleFactor=${detector.scaleFactor}")
                renderer.scale(detector.scaleFactor)
                return true
            }
        })
    }

    fun startSimulatedData() {
        if (!isUsingSimulatedData) {
            isUsingSimulatedData = true
            Log.i(TAG, "沒有 UDP 數據，使用模擬數據")
            executor.execute {
                val points = renderer.generateSimulatedPointsBatch()
                glSurfaceView.queueEvent { renderer.updatePoints(points) }
            }
        }
    }

    fun stopSimulatedData() {
        if (isUsingSimulatedData) {
            isUsingSimulatedData = false
            Log.i(TAG, "收到 UDP 數據，切換到真實數據")
        }
    }

    fun pause() {
        glSurfaceView.onPause()
    }

    fun resume() {
        glSurfaceView.onResume()
    }

    fun destroy() {
        executor.shutdown()
    }
}