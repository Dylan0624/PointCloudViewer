package com.example.pointcloudviewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.opengl.GLSurfaceView
import android.graphics.Color
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: PointCloudRenderer
    private lateinit var touchController: TouchController
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var legendView: LegendView
    private lateinit var fpsTextView: TextView
    private lateinit var pointInfoTextView: TextView  // 新增：顯示選中點信息的 TextView
    private lateinit var cameraPreviewContainer: FrameLayout
    private var cameraPreview: CameraPreview? = null
    private var isCameraActive = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 設置全屏和無標題
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // 創建抽屜布局作為根布局
        drawerLayout = DrawerLayout(this)
        drawerLayout.id = View.generateViewId()

        // 主內容布局
        val mainContent = ConstraintLayout(this)
        mainContent.id = View.generateViewId()

        // 初始化 GLSurfaceView 為全屏
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = PointCloudRenderer()
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 創建相機預覽容器，仍然佔據下半部分，但不強制填滿寬度
        cameraPreviewContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                0 // 高度會在顯示時調整
            ).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                matchConstraintPercentHeight = 0.5f // 50% 的屏幕高度
            }
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        // FPS 文字視圖
        fpsTextView = TextView(this).apply {
            id = View.generateViewId()
            text = "FPS: 0"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.END or Gravity.TOP
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(0, 16, 16, 0)  // 右上角留16dp邊距
            }
        }

        // 新增：點信息文字視圖
        pointInfoTextView = TextView(this).apply {
            id = View.generateViewId()
            text = ""  // 初始為空
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.END or Gravity.TOP
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = fpsTextView.id  // 放在 FPS 文字下方
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(0, 4, 16, 0)  // 與 FPS 文字保持相同的右邊距，上邊距小一些
            }
            visibility = View.GONE  // 初始時隱藏
        }

        // 設置初始距離縮放因子
        renderer.setDistanceScale(1.0f)

        // 創建漢堡菜單按鈕
        menuButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size) // 使用系統提供的漢堡按鈕圖標
            setBackgroundColor(Color.TRANSPARENT)
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(16, 16, 0, 0)
            }
            setOnClickListener {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // 添加 LegendView 到底部中央
        legendView = LegendView(this).apply {
            mode = 0 // 顯示強度漸變條
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT, // 寬度使用 match_parent
                100 // 固定高度
            ).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(32, 0, 32, 16) // 左右兩側保留邊距
            }
        }

        // 在 MainActivity.kt 的 onCreate 方法中

        // 初始化手勢控制器（改進的長按處理）
        touchController = TouchController(
            context = this,
            onRotation = { dx, dy ->
                renderer.rotate(dx, dy)
                glSurfaceView.requestRender()
            },
            onScale = { scaleFactor ->
                renderer.scale(scaleFactor)
                glSurfaceView.requestRender()
            },
            onTranslation = { dx, dy ->
                renderer.translate(dx, dy)
                glSurfaceView.requestRender()
            },
            onReset = {
                renderer.resetTransformation()
                glSurfaceView.requestRender()
            },
            onLongPress = { x, y ->
                // 處理長按事件，顯示選中點的信息
                handleLongPress(x, y)
            }
        )

        // 設置觸摸事件處理
        glSurfaceView.setOnTouchListener { _, event ->
            touchController.onTouchEvent(event)
            true
        }

        // 添加視圖到主內容布局
        mainContent.addView(glSurfaceView)
        mainContent.addView(menuButton)
        mainContent.addView(legendView)
        mainContent.addView(fpsTextView)
        mainContent.addView(pointInfoTextView) // 新增點信息視圖
        mainContent.addView(cameraPreviewContainer)
        // 添加主內容到抽屜布局
        drawerLayout.addView(mainContent)

        // 設置抽屜菜單，傳入 UDPManager
        val drawerMenuManager = DrawerMenuManager(this, drawerLayout, renderer, legendView, UDPManager)
        drawerMenuManager.setupDrawer()

        // 設置主內容視圖
        setContentView(drawerLayout)

        drawerMenuManager.setCameraToggleCallback { isChecked ->
            toggleCameraPreview(isChecked)
        }
        // 初始化 UDP 管理器
        UDPManager.initialize(
            glSurfaceView = glSurfaceView,
            renderer = renderer,
            onDataRateUpdate = { _ ->
                // 移除數據速率更新處理
            },
            onStatusUpdate = { status ->
                // 可選：如果你想保留原有的狀態更新
                runOnUiThread {
                    fpsTextView.text = status  // 這裡假設 status 會包含 FPS 信息
                }
            }
        )
    }
    // 修改切換相機預覽的方法
    private fun toggleCameraPreview(enabled: Boolean) {
        if (enabled && !isCameraActive) {
            // 請求相機權限
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST_CODE
                )
                return
            }

            // 顯示相機預覽容器
            cameraPreviewContainer.visibility = View.VISIBLE

            // 縮小 glSurfaceView 為屏幕上半部分
            (glSurfaceView.layoutParams as ConstraintLayout.LayoutParams).apply {
                height = 0
                matchConstraintPercentHeight = 0.5f
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = cameraPreviewContainer.id  // 在相機預覽上方
            }
            glSurfaceView.layoutParams = glSurfaceView.layoutParams

            // 通知渲染器視圖大小已經改變，需要調整渲染參數
            val width = resources.displayMetrics.widthPixels
            val height = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            glSurfaceView.queueEvent {
                renderer.adjustViewport(width, height)
            }

            // 初始化相機預覽
            cameraPreview = CameraPreview(this, cameraPreviewContainer)

// 使用 CENTER_INSIDE 來確保相機預覽保持其原始長寬比
            val previewParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, // 不強制填滿寬度
                FrameLayout.LayoutParams.MATCH_PARENT  // 確保填滿高度
            ).apply {
                gravity = android.view.Gravity.CENTER // 居中顯示
            }
            cameraPreviewContainer.addView(cameraPreview, previewParams)

            isCameraActive = true
        } else if (!enabled && isCameraActive) {
            // 停止並移除相機預覽
            cameraPreview?.stopCamera()
            cameraPreviewContainer.removeAllViews()
            cameraPreview = null

            // 隱藏相機預覽容器
            cameraPreviewContainer.visibility = View.GONE

            // 還原 glSurfaceView 為全屏
            (glSurfaceView.layoutParams as ConstraintLayout.LayoutParams).apply {
                height = ConstraintLayout.LayoutParams.MATCH_PARENT
                matchConstraintPercentHeight = 1.0f
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            glSurfaceView.layoutParams = glSurfaceView.layoutParams

            // 通知渲染器視圖大小已經改變，需要調整渲染參數
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            glSurfaceView.queueEvent {
                renderer.adjustViewport(width, height)
            }

            isCameraActive = false
        }

        // 更新其他視圖的位置
        updateUILayout()
    }
    // 添加更新UI布局的方法，調整其他元素位置
    private fun updateUILayout() {
        // 調整FPS文本位置
        (fpsTextView.layoutParams as ConstraintLayout.LayoutParams).apply {
            if (isCameraActive) {
                // 相機開啟時，FPS文本在點雲視圖的右上角
                topToTop = glSurfaceView.id
            } else {
                // 相機關閉時，FPS文本在屏幕的右上角
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        fpsTextView.layoutParams = fpsTextView.layoutParams

        // 調整其他UI元素...
    }

    // 添加常量
    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }

    // 添加權限處理
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 權限獲得，重新嘗試打開相機
                toggleCameraPreview(true)
            } else {
                // 權限被拒絕，可以顯示一個提示
                Toast.makeText(this, "需要相機權限來顯示相機畫面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 新增：處理長按事件的函數
    private fun handleLongPress(x: Float, y: Float) {
        val pickedPoint = renderer.findNearestPoint(x, y)

        runOnUiThread {
            if (pickedPoint != null) {
                // 格式化距離和強度信息
                val distanceStr = String.format("%.2f", pickedPoint.distance)
                val intensityStr = String.format("%.0f", pickedPoint.intensity)

                // 更新文本信息
                pointInfoTextView.text = "距離: ${distanceStr}m | 強度: $intensityStr"
                pointInfoTextView.visibility = View.VISIBLE
            } else {
                // 如果沒有找到點，隱藏信息
                pointInfoTextView.visibility = View.GONE
            }
        }
    }

    // 在 onPause 方法中添加
    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()

        // 暫停相機
        if (isCameraActive) {
            cameraPreview?.stopCamera()
        }
    }

    // 在 onResume 方法中添加
    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()

        // 如果相機預覽是活動的，重新啟動相機
        if (isCameraActive) {
            cameraPreview?.startCamera()
        }
    }

    // 處理返回鍵，如果菜單打開則關閉菜單而不是退出應用
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}