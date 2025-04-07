package com.example.pointcloudviewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class DrawerMenuManager(
    private val context: Context,
    private val drawerLayout: DrawerLayout,
    private val renderer: PointCloudRenderer,
    private val legendView: LegendView,
    private val udpManager: UDPManager,
    private var cameraToggleCallback: ((Boolean) -> Unit)? = null
) {
    @SuppressLint("WrongConstant", "UseSwitchCompatOrMaterialCode", "SetTextI18n", "ClickableViewAccessibility")
    fun setupDrawer() {
        // 創建一個自定義的ScrollView來包裹所有內容，並攔截所有觸摸事件
        val scrollView = object : ScrollView(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                // 攔截所有觸摸事件，不傳遞給底層
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                // 處理所有觸摸事件，不傳遞給底層
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                return super.onTouchEvent(ev)
            }
        }

        // 設置ScrollView的屬性
        scrollView.isVerticalScrollBarEnabled = true
        scrollView.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))

        // 創建抽屜內容的根佈局
        val drawerContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))

            // 為整個內容區域設置觸摸事件攔截
            setOnTouchListener { _, event ->
                // 攔截所有觸摸事件並阻止傳播到底層
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                true // 消耗事件，不傳遞給底層
            }
        }

        // 顯示座標軸開關
        val axisSwitchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val axisLabel = TextView(context).apply {
            text = "顯示座標軸"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val axisSwitch = Switch(context).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> renderer.setAxisVisibility(isChecked) }
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        axisSwitchLayout.addView(axisLabel)
        axisSwitchLayout.addView(axisSwitch)
        drawerContent.addView(axisSwitchLayout)

        // 顯示網格開關
        val gridSwitchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val gridLabel = TextView(context).apply {
            text = "顯示網格"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val gridSwitch = Switch(context).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> renderer.setGridVisibility(isChecked) }
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        gridSwitchLayout.addView(gridLabel)
        gridSwitchLayout.addView(gridSwitch)
        drawerContent.addView(gridSwitchLayout)

        // 顯示圖例開關
        val legendSwitchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val legendLabel = TextView(context).apply {
            text = "顯示圖例"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val legendSwitch = Switch(context).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                legendView.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            }
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        legendSwitchLayout.addView(legendLabel)
        legendSwitchLayout.addView(legendSwitch)
        drawerContent.addView(legendSwitchLayout)

        // 色彩模式選擇
        val colorModeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val colorModeLabel = TextView(context).apply {
            text = "色彩模式"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val colorModeSpinner = Spinner(context).apply {
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val colorModes = arrayOf("強度", "深度", "顏色")
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, colorModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorModeSpinner.adapter = adapter
        colorModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                renderer.setColorMode(position)
                legendView.mode = position
                drawerLayout.requestDisallowInterceptTouchEvent(true)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                drawerLayout.requestDisallowInterceptTouchEvent(true)
            }
        }
        colorModeLayout.addView(colorModeLabel)
        colorModeLayout.addView(colorModeSpinner)
        drawerContent.addView(colorModeLayout)

        // 最大渲染點數選擇
        val maxPointsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val maxPointsLabel = TextView(context).apply {
            text = "最大渲染點數"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val maxPointsSpinner = Spinner(context).apply {
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }

        // 定義可選的最大渲染點數值
        val maxPointsOptions = arrayOf(
            "300,000 點",
            "500,000 點",
            "1,000,000 點",
        )

        // 對應的實際數值
        val maxPointsValues = arrayOf(300000, 500000, 1000000)

        // 創建並設置適配器，確保文字顯示為黑色
        val maxPointsAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, maxPointsOptions)
        maxPointsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // 設置文字顏色為黑色
        maxPointsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        maxPointsSpinner.adapter = maxPointsAdapter

        // 使用以下代碼設置 Spinner 中文字的顏色
        maxPointsSpinner.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val textView = maxPointsSpinner.selectedView as? TextView
                textView?.setTextColor(android.graphics.Color.BLACK)
            } catch (e: Exception) {
                // 忽略可能的強制轉換異常
            }
        }

        // 設置默認選中項為 500,000 (第二項，索引1)
        maxPointsSpinner.setSelection(1)

        maxPointsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                // 設置選中的最大渲染點數
                val selectedValue = maxPointsValues[position]
                renderer.setMaxRenderPoints(selectedValue)
                drawerLayout.requestDisallowInterceptTouchEvent(true)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                drawerLayout.requestDisallowInterceptTouchEvent(true)
            }
        }

        maxPointsLayout.addView(maxPointsLabel)
        maxPointsLayout.addView(maxPointsSpinner)
        drawerContent.addView(maxPointsLayout)

        // 重置視圖按鈕
        val resetButton = Button(context).apply {
            text = "重置視圖"
            setOnClickListener {
                renderer.resetView()
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        drawerContent.addView(resetButton)

        // 強度過濾開關
        val intensityFilterLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val intensityFilterLabel = TextView(context).apply {
            text = "啟用強度過濾"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val intensityFilterSwitch = Switch(context).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                udpManager.setIntensityFilterEnabled(isChecked)
            }
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        intensityFilterLayout.addView(intensityFilterLabel)
        intensityFilterLayout.addView(intensityFilterSwitch)
        drawerContent.addView(intensityFilterLayout)

        // 點數比例控制
        val pointsRatioLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val pointsRatioLabel = TextView(context).apply {
            text = "顯示點數比例 (強度過濾): 100%"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val pointsRatioSeekBar = SeekBar(context).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ratio = progress / 100.0f
                    udpManager.setDisplayRatio(ratio)
                    pointsRatioLabel.text = "顯示點數比例 (強度過濾): ${progress}%"
                    drawerLayout.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    drawerLayout.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    drawerLayout.requestDisallowInterceptTouchEvent(true)
                }
            })
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        pointsRatioLayout.addView(pointsRatioLabel)
        pointsRatioLayout.addView(pointsRatioSeekBar)
        drawerContent.addView(pointsRatioLayout)

        // 新增 Echo Mode 選擇（按照 1、2、All 順序）
        val echoModeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val echoModeLabel = TextView(context).apply {
            text = "Echo Mode"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val echoModeRadioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val echoModes = listOf(
            Pair("1st Echo", UDPManager.EchoMode.ECHO_MODE_1ST),
            Pair("2nd Echo", UDPManager.EchoMode.ECHO_MODE_2ND),
            Pair("All Echoes", UDPManager.EchoMode.ECHO_MODE_ALL)
        )
        echoModes.forEachIndexed { index, (label, mode) ->
            val radioButton = RadioButton(context).apply {
                text = label
                id = index // 設置唯一的 ID
                setTextColor(android.graphics.Color.BLACK) // 設置文字為黑色
                setOnClickListener {
                    udpManager.setEchoMode(mode)
                    drawerLayout.requestDisallowInterceptTouchEvent(true)
                }
                setOnTouchListener { _, _ ->
                    drawerLayout.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
            echoModeRadioGroup.addView(radioButton)
        }
        // 設置默認選中項（初始為 ECHO_MODE_2ND，對應索引 1）
        echoModeRadioGroup.check(1)
        echoModeLayout.addView(echoModeLabel)
        echoModeLayout.addView(echoModeRadioGroup)
        drawerContent.addView(echoModeLayout)

        // 將所有內容加入到ScrollView中
        scrollView.addView(drawerContent)

        // 設置抽屜布局參數
        val drawerParams = DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.WRAP_CONTENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START
        }
        scrollView.layoutParams = drawerParams

        // 添加空白 View 作為觸摸事件捕捉器，覆蓋整個抽屜區域
        val touchBlocker = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 設置透明背景
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 攔截所有觸摸事件
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false // 繼續讓下層處理
            }
        }

        // 創建一個 FrameLayout 來包裹 ScrollView 和 touchBlocker
        val drawerContainer = FrameLayout(context).apply {
            layoutParams = drawerParams
        }
        val cameraToggleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val cameraToggleLabel = TextView(context).apply {
            text = "顯示相機畫面"
            setTextColor(android.graphics.Color.BLACK)
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val cameraToggleSwitch = Switch(context).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                cameraToggleCallback?.invoke(isChecked)
            }
            setOnTouchListener { _, _ ->
                drawerLayout.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        cameraToggleLayout.addView(cameraToggleLabel)
        cameraToggleLayout.addView(cameraToggleSwitch)
        drawerContent.addView(cameraToggleLayout)



        // 先添加 ScrollView，再添加 touchBlocker
        drawerContainer.addView(scrollView)
        //drawerContainer.addView(touchBlocker)

        // 將容器添加到抽屜中
        drawerLayout.addView(drawerContainer)

        // 監聽抽屜的狀態變化
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // 在抽屜滑動過程中也攔截觸摸事件
                drawerLayout.requestDisallowInterceptTouchEvent(true)
            }

            override fun onDrawerOpened(drawerView: View) {
                // 當抽屜打開時攔截觸摸事件
                drawerLayout.requestDisallowInterceptTouchEvent(true)
            }

            override fun onDrawerClosed(drawerView: View) {
                // 當抽屜關閉時恢復正常觸摸事件處理
                drawerLayout.requestDisallowInterceptTouchEvent(false)
            }

            override fun onDrawerStateChanged(newState: Int) {
                // 當抽屜狀態改變時也攔截觸摸事件
                if (newState != DrawerLayout.STATE_IDLE) {
                    drawerLayout.requestDisallowInterceptTouchEvent(true)
                }
            }
        })
    }
    // 添加一個方法用於註冊相機回調
    fun setCameraToggleCallback(callback: (Boolean) -> Unit) {
        cameraToggleCallback = callback
    }
}