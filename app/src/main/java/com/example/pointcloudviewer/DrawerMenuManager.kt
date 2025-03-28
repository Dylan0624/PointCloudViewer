package com.example.pointcloudviewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class DrawerMenuManager(
    private val context: Context,
    private val drawerLayout: DrawerLayout,
    private val renderer: PointCloudRenderer,
    private val legendView: LegendView,
    private val udpManager: UDPManager
) {
    @SuppressLint("WrongConstant", "UseSwitchCompatOrMaterialCode", "SetTextI18n")
    fun setupDrawer() {
        val drawerContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
        }

        // 顯示座標軸開關
        val axisSwitchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val axisLabel = TextView(context).apply { text = "顯示座標軸"; setTextColor(android.graphics.Color.BLACK) }
        val axisSwitch = Switch(context).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> renderer.setAxisVisibility(isChecked) }
        }
        axisSwitchLayout.addView(axisLabel)
        axisSwitchLayout.addView(axisSwitch)
        drawerContent.addView(axisSwitchLayout)

        // 顯示網格開關
        val gridSwitchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val gridLabel = TextView(context).apply { text = "顯示網格"; setTextColor(android.graphics.Color.BLACK) }
        val gridSwitch = Switch(context).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> renderer.setGridVisibility(isChecked) }
        }
        gridSwitchLayout.addView(gridLabel)
        gridSwitchLayout.addView(gridSwitch)
        drawerContent.addView(gridSwitchLayout)

        // 顯示圖例開關
        val legendSwitchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val legendLabel = TextView(context).apply { text = "顯示圖例"; setTextColor(android.graphics.Color.BLACK) }
        val legendSwitch = Switch(context).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                legendView.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
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
        }
        val colorModeLabel = TextView(context).apply { text = "色彩模式"; setTextColor(android.graphics.Color.BLACK) }
        val colorModeSpinner = Spinner(context)
        val colorModes = arrayOf("強度", "深度", "顏色")
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, colorModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorModeSpinner.adapter = adapter
        colorModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                renderer.setColorMode(position)
                legendView.mode = position
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        colorModeLayout.addView(colorModeLabel)
        colorModeLayout.addView(colorModeSpinner)
        drawerContent.addView(colorModeLayout)

        // 重置視圖按鈕
        val resetButton = Button(context).apply {
            text = "重置視圖"
            setOnClickListener {
                renderer.resetView()
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            setPadding(0, 8, 0, 8)
        }
        drawerContent.addView(resetButton)

        // 強度過濾開關
        val intensityFilterLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val intensityFilterLabel = TextView(context).apply {
            text = "啟用強度過濾"
            setTextColor(android.graphics.Color.BLACK)
        }
        val intensityFilterSwitch = Switch(context).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                udpManager.setIntensityFilterEnabled(isChecked)
            }
        }
        intensityFilterLayout.addView(intensityFilterLabel)
        intensityFilterLayout.addView(intensityFilterSwitch)
        drawerContent.addView(intensityFilterLayout)

        // 點數比例控制
        val pointsRatioLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> drawerLayout.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawerLayout.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        val pointsRatioLabel = TextView(context).apply {
            text = "顯示點數比例 (強度過濾): 100%"
            setTextColor(android.graphics.Color.BLACK)
        }
        val pointsRatioSeekBar = SeekBar(context).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ratio = progress / 100.0f
                    udpManager.setDisplayRatio(ratio)
                    pointsRatioLabel.text = "顯示點數比例 (強度過濾): ${progress}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> drawerLayout.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawerLayout.requestDisallowInterceptTouchEvent(false)
                }
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
        }
        val echoModeLabel = TextView(context).apply {
            text = "Echo Mode"
            setTextColor(android.graphics.Color.BLACK)
        }
        val echoModeRadioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
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
                }
            }
            echoModeRadioGroup.addView(radioButton)
        }
        // 設置默認選中項（初始為 ECHO_MODE_2ND，對應索引 1）
        echoModeRadioGroup.check(1)
        echoModeLayout.addView(echoModeLabel)
        echoModeLayout.addView(echoModeRadioGroup)
        drawerContent.addView(echoModeLayout)

        // 設置抽屜布局參數
        val drawerParams = DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.WRAP_CONTENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START
        }
        drawerContent.layoutParams = drawerParams

        drawerLayout.addView(drawerContent)
    }
}