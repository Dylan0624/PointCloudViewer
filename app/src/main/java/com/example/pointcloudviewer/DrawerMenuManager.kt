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
    private val legendView: LegendView
) {
    @SuppressLint("WrongConstant", "UseSwitchCompatOrMaterialCode", "SetTextI18n")
    fun setupDrawer() {
        val drawerContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
        }

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

        val resetButton = Button(context).apply {
            text = "重置視圖"
            setOnClickListener {
                renderer.resetView()
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            setPadding(0, 8, 0, 8)
        }
        drawerContent.addView(resetButton)

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
                    renderer.displayRatio = ratio
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

        val drawerParams = DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.WRAP_CONTENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        )
        drawerParams.gravity = Gravity.START
        drawerContent.layoutParams = drawerParams

        drawerLayout.addView(drawerContent)
    }
}