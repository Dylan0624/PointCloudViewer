package com.example.pointcloudviewer

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.atan2

/**
 * TouchController handles all touch gestures for the point cloud viewer,
 * including rotation, scaling (zoom), translation (pan), and long press.
 *
 * 手勢設定：
 * - 單指: 旋轉
 * - 雙指: 縮放
 * - 三指: 平移
 * - 長按: 顯示點信息
 * - 雙擊: 重置視圖
 *
 * 操作間有保護期，避免誤觸發
 */
class TouchController(
    context: Context,
    private val onRotation: (Float, Float) -> Unit,
    private val onScale: (Float) -> Unit,
    private val onTranslation: (Float, Float) -> Unit,
    private val onReset: () -> Unit,
    private val onLongPress: (Float, Float) -> Unit
) {
    // 操作保護相關常量和變數
    private val OPERATION_COOLDOWN = 500L // 操作保護期 (毫秒)
    private var lastOperationType = OperationType.NONE
    private var lastOperationTime = 0L
    private var isInCooldown = false
    private val handler = Handler(Looper.getMainLooper())

    // 操作類型枚舉
    private enum class OperationType {
        NONE, ROTATION, SCALING, TRANSLATION, LONG_PRESS
    }

    // Detector for scaling (pinch zoom)
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // 只有雙指且不在冷卻期時才處理縮放
            if (currentPointerCount == 2 && !isLongPressActive
                && (lastOperationType == OperationType.NONE
                        || lastOperationType == OperationType.SCALING
                        || !isInCooldown)) {

                onScale(detector.scaleFactor)
                updateOperationState(OperationType.SCALING)
                return true
            }
            return false
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // 開始縮放時更新操作狀態
            if (currentPointerCount == 2) {
                updateOperationState(OperationType.SCALING)
                cancelLongPress()
            }
            return true
        }
    })

    // For detecting rotation
    private var previousX = 0f
    private var previousY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isSignificantMove = false
    private val MOVE_THRESHOLD = 10f

    // 記錄當前觸摸點數量和上一個數量
    private var currentPointerCount = 0
    private var previousPointerCount = 0
    private var pointerCountChanged = false

    // 用於檢測手指抬起
    private var pointerUpDetected = false

    // 長按檢測相關變數
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPressActive = false
    private val LONG_PRESS_TIMEOUT = 600L

    // Detector for single finger gestures like fling and double tap
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            initialTouchX = e.x
            initialTouchY = e.y
            isSignificantMove = false
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            cancelLongPress()
            onReset()
            // 雙擊不受冷卻期限制
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // 只有單指且沒有明顯移動，且不在冷卻期時才觸發長按
            if (currentPointerCount == 1 && !isSignificantMove
                && (lastOperationType == OperationType.NONE
                        || lastOperationType == OperationType.LONG_PRESS
                        || !isInCooldown)) {

                isLongPressActive = true
                onLongPress(e.x, e.y)
                updateOperationState(OperationType.LONG_PRESS)
            }
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 檢查是否發生了明顯移動
            if (e1 != null) {
                val deltaX = abs(e2.x - initialTouchX)
                val deltaY = abs(e2.y - initialTouchY)

                if (deltaX > MOVE_THRESHOLD || deltaY > MOVE_THRESHOLD) {
                    isSignificantMove = true
                    cancelLongPress()
                }
            }

            // 如果長按已激活，則不進行滾動操作
            if (isLongPressActive) {
                return false
            }

            // 如果手指數量剛剛改變或手指抬起被檢測到，跳過當前幀
            if (pointerCountChanged || pointerUpDetected) {
                previousX = e2.x
                previousY = e2.y
                return false
            }

            // 三指平移，只要不在冷卻期或上次也是平移
            if (currentPointerCount == 3
                && (lastOperationType == OperationType.NONE
                        || lastOperationType == OperationType.TRANSLATION
                        || !isInCooldown)) {

                onTranslation(-distanceX, -distanceY)
                updateOperationState(OperationType.TRANSLATION)
                return true
            }

            // 單指旋轉，只要不在冷卻期或上次也是旋轉
            if (currentPointerCount == 1 && isSignificantMove
                && (lastOperationType == OperationType.NONE
                        || lastOperationType == OperationType.ROTATION
                        || !isInCooldown)) {

                val x = e2.x
                val y = e2.y
                val dx = x - previousX
                val dy = y - previousY

                onRotation(dy * 0.7f, dx * 0.7f)
                updateOperationState(OperationType.ROTATION)

                previousX = x
                previousY = y
                return true
            }
            return false
        }
    })

    // 更新操作狀態
    private fun updateOperationState(operation: OperationType) {
        // 只有當操作類型改變時才重置冷卻期
        if (operation != lastOperationType) {
            lastOperationType = operation
            lastOperationTime = System.currentTimeMillis()

            // 開始冷卻期
            isInCooldown = true
            handler.removeCallbacksAndMessages(null) // 清除先前的定時器
            handler.postDelayed({
                isInCooldown = false
            }, OPERATION_COOLDOWN)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        isLongPressActive = false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        // 更新上一個和當前的手指數量
        previousPointerCount = currentPointerCount
        currentPointerCount = event.pointerCount

        // 檢測手指數量是否改變
        pointerCountChanged = previousPointerCount != currentPointerCount

        // 檢測手指抬起事件
        pointerUpDetected = (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_UP)

        // 優先讓 GestureDetector 處理
        val gestureResult = gestureDetector.onTouchEvent(event)

        // 如果是雙指且沒有長按激活，則處理縮放
        if (currentPointerCount == 2 && !isLongPressActive) {
            scaleGestureDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                isSignificantMove = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指觸摸時取消長按檢測
                cancelLongPress()
            }

            MotionEvent.ACTION_MOVE -> {
                // 在移動事件中檢查是否是明顯移動
                if (!isSignificantMove && currentPointerCount == 1) {
                    val deltaX = abs(event.x - initialTouchX)
                    val deltaY = abs(event.y - initialTouchY)

                    if (deltaX > MOVE_THRESHOLD || deltaY > MOVE_THRESHOLD) {
                        isSignificantMove = true
                        cancelLongPress()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                // 所有手指抬起，重置狀態
                cancelLongPress()
                currentPointerCount = 0

                // 如果之前在縮放，並且不是雙擊，添加一個特殊的冷卻期
                if (lastOperationType == OperationType.SCALING) {
                    isInCooldown = true
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        isInCooldown = false
                    }, OPERATION_COOLDOWN)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 一根或多根手指抬起（但不是全部）
                // 這裡要特別小心處理從雙指到單指的轉換
                if (previousPointerCount == 2 && currentPointerCount == 1) {
                    // 強制更新坐標，避免旋轉彈跳
                    previousX = event.getX(0)
                    previousY = event.getY(0)

                    // 從雙指到單指時強制添加冷卻期
                    isInCooldown = true
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        isInCooldown = false
                    }, OPERATION_COOLDOWN)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // 取消時重置所有狀態
                cancelLongPress()
                currentPointerCount = 0
                isInCooldown = false
                lastOperationType = OperationType.NONE
            }
        }

        return true
    }
}