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
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class MainActivity : AppCompatActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: PointCloudRenderer
    private lateinit var touchController: TouchController
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var legendView: LegendView
    private lateinit var fpsTextView: TextView

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

        // 設置 GLSurfaceView
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

        // 初始化手勢控制器
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

        // 添加主內容到抽屜布局
        drawerLayout.addView(mainContent)

        // 設置抽屜菜單，傳入 UDPManager
        val drawerMenuManager = DrawerMenuManager(this, drawerLayout, renderer, legendView, UDPManager)
        drawerMenuManager.setupDrawer()

        // 設置主內容視圖
        setContentView(drawerLayout)

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

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
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