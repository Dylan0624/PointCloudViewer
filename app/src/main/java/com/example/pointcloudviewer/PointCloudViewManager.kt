package com.example.pointcloudviewer

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
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

    private val updateHandler = Handler(Looper.getMainLooper())
    private var isUpdating = false
    private val executor = Executors.newSingleThreadExecutor()
    private var lastUpdateTime = 0L
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector

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

    fun startPointUpdates() {
        updateHandler.postDelayed({
            isUpdating = true
            updatePoints()
            Log.i(TAG, "Point updates started after 2s delay")
        }, 2000)
    }

    private fun updatePoints() {
        updateHandler.post(object : Runnable {
            override fun run() {
                if (isUpdating) {
                    val currentTime = System.currentTimeMillis()
                    if (lastUpdateTime > 0) {
                        val deltaTime = currentTime - lastUpdateTime
                        val fps = 1000f / deltaTime
                        Log.d(TAG, "FPS: %.1f".format(fps))
                    }
                    lastUpdateTime = currentTime

                    executor.execute {
                        val startTime = System.currentTimeMillis()
                        val points = renderer.generateSimulatedPointsBatch()
                        val genTime = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Point generation took: ${genTime}ms")
                        glSurfaceView.queueEvent { renderer.updatePoints(points) }
                    }
                    updateHandler.postDelayed(this, 33)
                }
            }
        })
    }

    fun pause() {
        glSurfaceView.onPause()
        isUpdating = false
        updateHandler.removeCallbacksAndMessages(null)
    }

    fun resume() {
        glSurfaceView.onResume()
        if (!isUpdating) startPointUpdates()
    }

    fun destroy() {
        isUpdating = false
        updateHandler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }
}