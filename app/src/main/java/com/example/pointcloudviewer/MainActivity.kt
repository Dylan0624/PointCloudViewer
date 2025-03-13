package com.example.pointcloudviewer

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.opengl.GLSurfaceView
import androidx.drawerlayout.widget.DrawerLayout

class MainActivity : AppCompatActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: PointCloudRenderer
    private lateinit var pointDetailTextView: TextView
    private lateinit var dataRateTextView: TextView
    private lateinit var legendView: LegendView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewManager: PointCloudViewManager
    private lateinit var drawerMenuManager: DrawerMenuManager

    companion object {
        private const val TAG = "MainActivity"

        // 載入共享庫
        init {
            System.loadLibrary("udp_receiver_node")
        }
    }

    @SuppressLint("WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created")

        drawerLayout = DrawerLayout(this)
        val rootLayout = FrameLayout(this)

        glSurfaceView = GLSurfaceView(this)
        renderer = PointCloudRenderer()
        rootLayout.addView(glSurfaceView)

        pointDetailTextView = TextView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            text = ""
        }
        val detailLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        detailLayoutParams.gravity = Gravity.TOP or Gravity.END
        detailLayoutParams.topMargin = 16
        detailLayoutParams.rightMargin = 16
        rootLayout.addView(pointDetailTextView, detailLayoutParams)

        val menuButton = Button(this).apply {
            text = "☰"
            textSize = 20f
            setPadding(16, 16, 16, 16)
            setOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        }
        val topLeftLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.START
            addView(menuButton)
        }
        rootLayout.addView(topLeftLayout)

        legendView = LegendView(this)
        val legendLayoutParams = FrameLayout.LayoutParams(350, 100)
        legendLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        legendView.layoutParams = legendLayoutParams
        legendView.mode = renderer.getColorMode()
        rootLayout.addView(legendView)

        dataRateTextView = TextView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            text = "資料速率: 0.00 MB/s"
        }
        val dataRateLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        dataRateLayoutParams.gravity = Gravity.BOTTOM or Gravity.START
        dataRateLayoutParams.bottomMargin = 16
        dataRateLayoutParams.leftMargin = 16
        rootLayout.addView(dataRateTextView, dataRateLayoutParams)

        drawerLayout.addView(rootLayout)
        setContentView(drawerLayout)

        // 初始化管理器
        viewManager = PointCloudViewManager(this, glSurfaceView, renderer, pointDetailTextView)
        drawerMenuManager = DrawerMenuManager(this, drawerLayout, renderer, legendView)

        // 設置抽屜菜單
        drawerMenuManager.setupDrawer()

        // 啟動 UDP 監聽（永不停止）
        UDPManager.initialize(glSurfaceView, renderer) { mbPerSecond ->
            runOnUiThread { dataRateTextView.text = "資料速率: %.2f MB/s".format(mbPerSecond) }
        } 

        // 啟動點雲更新
        viewManager.startPointUpdates()
//
//        // 測試 JNI 調用
//        val testResult = testNativeCall()
//        Log.i(TAG, "JNI Test Result: $testResult")
    }

    override fun onPause() {
        super.onPause()
        viewManager.pause()
        Log.i(TAG, "Activity paused")
    }

    override fun onResume() {
        super.onResume()
        viewManager.resume()
        Log.i(TAG, "Activity resumed")
    }

    override fun onDestroy() {
        super.onDestroy()
        viewManager.destroy()
        // 不停止 UDPManager，讓它繼續運行
        Log.i(TAG, "Activity destroyed")
    }

//    // JNI Native 方法聲明
//    private external fun testNativeCall(): Int
}