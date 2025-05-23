package com.example.pointcloudviewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PointCloudRenderer : GLSurfaceView.Renderer {
    private var pointBuffer: FloatBuffer? = null
    private var gridBuffer: FloatBuffer? = null
    private var axisBuffer: FloatBuffer? = null
    private var pointCount: Int = 0
    private var gridLineCount: Int = 0
    private var program: Int = 0
    private var gridProgram: Int = 0
    private var axisProgram: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var gridMvpMatrixHandle: Int = 0
    private var axisMvpMatrixHandle: Int = 0
    private var positionHandle: Int = 0
    private var gridPositionHandle: Int = 0
    private var axisPositionHandle: Int = 0
    private var intensityHandle: Int = 0
    private var axisColorHandle: Int = 0
    private var isFirstFrameReceived = false
    private var showGrid: Boolean = true
    private var showAxis: Boolean = true
    private var colorMode: Int = 0 // 0: 強度, 1: 深度, 2: 顏色
    var displayRatio: Float = 1.0f // 顯示點雲比例

    // 添加一個 GLSurfaceView 的引用，用於請求重繪
    private var glSurfaceView: GLSurfaceView? = null

    private val pointSize = 1.0f
    private var distanceScale = 1.5f

    private var rotationX = 0f
    private var rotationY = 0f
    private var translateX = 0f
    private var translateY = 0f
    private var scaleFactorAccumulated = 1.0f

    private var centerX = 0f
    private var centerY = 0f
    private var centerZ = 0f

    private val rotationMatrix = FloatArray(16)
    private val translateMatrix = FloatArray(16)
    private val scaleMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private var maxRenderPoints = 500_000

    private var originalPoints: FloatArray? = null
    // 新增：用於找到最近點的數據結構
    data class PickedPointInfo(
        val x: Float,
        val y: Float,
        val z: Float,
        val distance: Float,
        val intensity: Float
    )
    // 新增：屏幕寬度和高度
    private var screenWidth = 0
    private var screenHeight = 0

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute float aIntensity;
        varying float vIntensity;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = $pointSize;
            vIntensity = aIntensity / 255.0;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying float vIntensity;
        void main() {
            vec3 color;
            if (vIntensity < 0.33) {
                color = mix(vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 0.0), vIntensity / 0.33);
            } else if (vIntensity < 0.66) {
                color = mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 1.0, 0.0), (vIntensity - 0.33) / 0.33);
            } else {
                color = mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), (vIntensity - 0.66) / 0.33);
            }
            gl_FragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    private val gridVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val gridFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0); // Pure white
        }
    """.trimIndent()

    private val axisVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vColor = aColor;
        }
    """.trimIndent()

    private val axisFragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    init {
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.setIdentityM(translateMatrix, 0)
        Matrix.setIdentityM(scaleMatrix, 0)
        createGrid(-10f, 10f, 1f)
        createAxis()
    }

    // 添加設置 GLSurfaceView 的方法
    fun setGLSurfaceView(view: GLSurfaceView) {
        this.glSurfaceView = view
    }

    private fun createGrid(min: Float, max: Float, step: Float): FloatBuffer {
        val lines = ArrayList<Float>()
        var pos = min
        // X方向線條 (沿著紅色軸)
        while (pos <= max) {
            lines.add(pos); lines.add(0f); lines.add(min)  // X-Z平面上的線
            lines.add(pos); lines.add(0f); lines.add(max)
            pos += step
        }
        pos = min
        // Z方向線條 (沿著綠色軸)
        while (pos <= max) {
            lines.add(min); lines.add(0f); lines.add(pos)
            lines.add(max); lines.add(0f); lines.add(pos)
            pos += step
        }
        gridLineCount = lines.size / 6
        val buffer = ByteBuffer.allocateDirect(lines.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(lines.toFloatArray())
                position(0)
            }
        gridBuffer = buffer
        return buffer
    }

    private fun createAxis(): FloatBuffer {
        val axisData = floatArrayOf(
            // X-axis (red, positive X)
            0f, 0f, 0f, 1f, 0f, 0f, 1f,  // Start (R=1, G=0, B=0)
            10f, 0f, 0f, 1f, 0f, 0f, 1f, // End (positive X)

            // Y-axis (blue, positive Y) - 改為藍色向上
            0f, 0f, 0f, 0f, 0f, 1f, 1f,  // Start (R=0, G=0, B=1)
            0f, 10f, 0f, 0f, 0f, 1f, 1f, // End (positive Y)

            // Z-axis (green, negative Z) - 綠色改為反方向
            0f, 0f, 0f, 0f, 1f, 0f, 1f,  // Start (R=0, G=1, B=0)
            0f, 0f, -10f, 0f, 1f, 0f, 1f // End (negative Z)
        )
        val buffer = ByteBuffer.allocateDirect(axisData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(axisData)
                position(0)
            }
        axisBuffer = buffer
        return buffer
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            checkGlError("Link Point Cloud Program")
        }
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        intensityHandle = GLES20.glGetAttribLocation(program, "aIntensity")

        val gridVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, gridVertexShaderCode)
        val gridFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, gridFragmentShaderCode)
        gridProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, gridVertexShader)
            GLES20.glAttachShader(it, gridFragmentShader)
            GLES20.glLinkProgram(it)
            checkGlError("Link Grid Program")
        }
        gridMvpMatrixHandle = GLES20.glGetUniformLocation(gridProgram, "uMVPMatrix")
        gridPositionHandle = GLES20.glGetAttribLocation(gridProgram, "aPosition")

        val axisVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, axisVertexShaderCode)
        val axisFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, axisFragmentShaderCode)
        axisProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, axisVertexShader)
            GLES20.glAttachShader(it, axisFragmentShader)
            GLES20.glLinkProgram(it)
            checkGlError("Link Axis Program")
        }
        axisMvpMatrixHandle = GLES20.glGetUniformLocation(axisProgram, "uMVPMatrix")
        axisPositionHandle = GLES20.glGetAttribLocation(axisProgram, "aPosition")
        axisColorHandle = GLES20.glGetAttribLocation(axisProgram, "aColor")

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0.8f, 1f, 0f, 0.5f, 0f, 0f, 4f, 0f)

        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.setIdentityM(translateMatrix, 0)
        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.scaleM(scaleMatrix, 0, 1f, 1f, 1f)
        rotationX = 0f
        rotationY = 0f
        translateX = 0f
        translateY = 0f
        scaleFactorAccumulated = 1.0f
        updateMVPMatrix()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        // 修改 FOV 為 45 度
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 100f)
        updateMVPMatrix()

        // 保存屏幕尺寸
        screenWidth = width
        screenHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (showGrid && gridBuffer != null && gridLineCount > 0) {
            drawGrid()
        }

        if (showAxis && axisBuffer != null) {
            drawAxis()
        }

        pointBuffer?.let { buffer ->
            if (pointCount > 0) {
                GLES20.glUseProgram(program)
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
                buffer.position(0)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 7 * 4, buffer)
                GLES20.glEnableVertexAttribArray(positionHandle)
                buffer.position(3)
                GLES20.glVertexAttribPointer(intensityHandle, 1, GLES20.GL_FLOAT, false, 7 * 4, buffer)
                GLES20.glEnableVertexAttribArray(intensityHandle)

                // 移除 displayRatio 相關計算，直接渲染所有點
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

                GLES20.glDisableVertexAttribArray(positionHandle)
                GLES20.glDisableVertexAttribArray(intensityHandle)
            }
        }
    }

    // 移除 setDisplayRatio 方法（如果之前有添加過）


    private fun drawGrid() {
        GLES20.glUseProgram(gridProgram)
        GLES20.glUniformMatrix4fv(gridMvpMatrixHandle, 1, false, mvpMatrix, 0)
        gridBuffer?.position(0)
        GLES20.glVertexAttribPointer(gridPositionHandle, 3, GLES20.GL_FLOAT, false, 0, gridBuffer)
        GLES20.glEnableVertexAttribArray(gridPositionHandle)
        GLES20.glLineWidth(1.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount * 2)
        GLES20.glDisableVertexAttribArray(gridPositionHandle)
    }

    private fun drawAxis() {
        GLES20.glUseProgram(axisProgram)
        GLES20.glUniformMatrix4fv(axisMvpMatrixHandle, 1, false, mvpMatrix, 0)
        axisBuffer?.position(0)
        GLES20.glVertexAttribPointer(axisPositionHandle, 3, GLES20.GL_FLOAT, false, 7 * 4, axisBuffer)
        GLES20.glEnableVertexAttribArray(axisPositionHandle)
        axisBuffer?.position(3)
        GLES20.glVertexAttribPointer(axisColorHandle, 4, GLES20.GL_FLOAT, false, 7 * 4, axisBuffer)
        GLES20.glEnableVertexAttribArray(axisColorHandle)
        GLES20.glLineWidth(3.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)
        GLES20.glDisableVertexAttribArray(axisPositionHandle)
        GLES20.glDisableVertexAttribArray(axisColorHandle)
    }

    private fun updatePointsCenter(points: FloatArray) {
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = Float.MIN_VALUE

        for (i in points.indices step 7) {
            val x = points[i]
            val y = points[i + 1]
            val z = points[i + 2]
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z)
            maxZ = maxOf(maxZ, z)
        }

        centerX = (minX + maxX) / 2f
        centerY = (minY + maxY) / 2f
        centerZ = (minZ + maxZ) / 2f
        updateMVPMatrix()
    }

    private fun updateMVPMatrix() {
        Matrix.setIdentityM(tempMatrix, 0)
        Matrix.translateM(tempMatrix, 0, -centerX, -centerY, -centerZ)
        Matrix.multiplyMM(tempMatrix, 0, scaleMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(tempMatrix, 0, rotationMatrix, 0, tempMatrix, 0)
        Matrix.translateM(tempMatrix, 0, centerX, centerY, centerZ)
        Matrix.multiplyMM(tempMatrix, 0, translateMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)
    }

    fun updatePoints(points: FloatArray) {
        synchronized(this) {
            // 保存原始點數據供點選擇使用
            originalPoints = points.clone()

            pointBuffer?.clear() // 如果 pointBuffer 是 FloatBuffer
            pointCount = 0       // 重置點計數
            val totalPoints = points.size / 7 // 每個點有 7 個 float

            if (totalPoints <= maxRenderPoints) {
                // 如果點數未超過上限，直接使用全部點
                pointBuffer = createBuffer(points)
                pointCount = totalPoints
            } else {
                // 超過上限，進行均勻過濾
                val sampledPoints = FloatArray(maxRenderPoints * 7)
                val step = (totalPoints - 1).toFloat() / (maxRenderPoints - 1) // 改進步長計算
                var srcIndexFloat = 0f

                for (i in 0 until maxRenderPoints) {
                    val srcIndex = (srcIndexFloat.toInt() * 7).coerceAtMost(points.size - 7)
                    val destIndex = i * 7
                    System.arraycopy(points, srcIndex, sampledPoints, destIndex, 7)
                    srcIndexFloat += step // 逐步增加索引
                }

                pointBuffer = createBuffer(sampledPoints)
                pointCount = maxRenderPoints
                log("Points exceed limit $totalPoints > $maxRenderPoints, filtered to $maxRenderPoints points")
            }

            // 當有多個 frame 時，只更新一次中心點
            if (!isFirstFrameReceived) {
                updatePointsCenter(points) // 使用原始數據計算中心點
                isFirstFrameReceived = true
            }
            log("Updated points: $pointCount from multiple frames")
        }
    }

    fun findNearestPoint(screenX: Float, screenY: Float): PickedPointInfo? {
        val points = originalPoints ?: return null

        // 轉換屏幕坐標為 OpenGL 標準化坐標 (-1 到 1)
        val normalizedX = (2.0f * screenX / screenWidth - 1.0f)
        val normalizedY = (1.0f - 2.0f * screenY / screenHeight)

        // 創建射線
        val rayStart = floatArrayOf(normalizedX, normalizedY, -1.0f, 1.0f)
        val rayEnd = floatArrayOf(normalizedX, normalizedY, 1.0f, 1.0f)

        // 計算逆矩陣用於反向轉換
        val invertedMVP = FloatArray(16)
        if (!Matrix.invertM(invertedMVP, 0, mvpMatrix, 0)) {
            return null // 無法計算逆矩陣
        }

        // 將射線起點和終點轉換到世界空間
        val worldRayStart = floatArrayOf(0f, 0f, 0f, 0f)
        val worldRayEnd = floatArrayOf(0f, 0f, 0f, 0f)

        Matrix.multiplyMV(worldRayStart, 0, invertedMVP, 0, rayStart, 0)
        Matrix.multiplyMV(worldRayEnd, 0, invertedMVP, 0, rayEnd, 0)

        // 同質座標除法
        for (i in 0..2) {
            worldRayStart[i] /= worldRayStart[3]
            worldRayEnd[i] /= worldRayEnd[3]
        }

        // 計算射線方向
        val rayDirection = floatArrayOf(
            worldRayEnd[0] - worldRayStart[0],
            worldRayEnd[1] - worldRayStart[1],
            worldRayEnd[2] - worldRayStart[2]
        )

        // 尋找最近的點
        var closestDistance = Float.MAX_VALUE
        var closestPoint: PickedPointInfo? = null

        for (i in points.indices step 7) {
            val x = points[i]
            val y = points[i + 1]
            val z = points[i + 2]
            val intensity = points[i + 3]

            // 計算點到射線的距離
            val distance = distancePointToRay(floatArrayOf(x, y, z), worldRayStart, rayDirection)

            // 如果這個點比之前找到的最近點更近，則更新最近點
            if (distance < closestDistance) {
                closestDistance = distance

                // 計算實際距離（從原點到點的距離）
                val actualDistance = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

                closestPoint = PickedPointInfo(x, y, z, actualDistance, intensity)
            }
        }

        return closestPoint
    }

    // 計算點到射線的距離
    private fun distancePointToRay(point: FloatArray, rayOrigin: FloatArray, rayDirection: FloatArray): Float {
        // 計算點到射線原點的向量
        val v = floatArrayOf(
            point[0] - rayOrigin[0],
            point[1] - rayOrigin[1],
            point[2] - rayOrigin[2]
        )

        // 計算射線方向的單位向量
        val rayDirLength = Math.sqrt((rayDirection[0] * rayDirection[0] +
                rayDirection[1] * rayDirection[1] +
                rayDirection[2] * rayDirection[2]).toDouble()).toFloat()

        val rayDirNorm = floatArrayOf(
            rayDirection[0] / rayDirLength,
            rayDirection[1] / rayDirLength,
            rayDirection[2] / rayDirLength
        )

        // 計算點在射線方向上的投影長度
        val t = v[0] * rayDirNorm[0] + v[1] * rayDirNorm[1] + v[2] * rayDirNorm[2]

        // 計算最近點
        val nearestPoint = floatArrayOf(
            rayOrigin[0] + rayDirNorm[0] * t,
            rayOrigin[1] + rayDirNorm[1] * t,
            rayOrigin[2] + rayDirNorm[2] * t
        )

        // 計算點到最近點的距離
        return Math.sqrt(
            Math.pow(point[0] - nearestPoint[0].toDouble(), 2.0) +
                    Math.pow(point[1] - nearestPoint[1].toDouble(), 2.0) +
                    Math.pow(point[2] - nearestPoint[2].toDouble(), 2.0)
        ).toFloat()
    }

    fun setDistanceScale(scale: Float) {
        distanceScale = scale.coerceAtLeast(0.1f)
    }

    fun rotate(dx: Float, dy: Float) {
        rotationX += dx
        rotationY += dy
        Matrix.setRotateM(rotationMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(rotationMatrix, 0, rotationY, 0f, 1f, 0f)
        updateMVPMatrix()
    }

    fun translate(dx: Float, dy: Float) {
        val scaleFactor = 0.1f / scaleFactorAccumulated
        translateX += dx * scaleFactor
        translateY += dy * scaleFactor
        Matrix.setIdentityM(translateMatrix, 0)
        Matrix.translateM(translateMatrix, 0, translateX, -translateY, 0f)
        updateMVPMatrix()
    }

    fun scale(scaleFactor: Float) {
        scaleFactorAccumulated *= scaleFactor
        scaleFactorAccumulated = scaleFactorAccumulated.coerceIn(0.1f, 10.0f)
        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.scaleM(scaleMatrix, 0, scaleFactorAccumulated, scaleFactorAccumulated, scaleFactorAccumulated)
        updateMVPMatrix()
    }

    fun resetTransformation() {
        rotationX = 0f
        rotationY = 0f
        translateX = 0f
        translateY = 0f
        scaleFactorAccumulated = 1.0f
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.setIdentityM(translateMatrix, 0)
        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.scaleM(scaleMatrix, 0, 1f, 1f, 1f)
        updateMVPMatrix()
    }

    // 為抽屜菜單添加的新方法
    fun resetView() {
        resetTransformation()
    }

    // 修改方法以請求重繪
    fun setAxisVisibility(visible: Boolean) {
        if (showAxis != visible) {
            showAxis = visible
            // 請求重繪
            glSurfaceView?.requestRender()
            log("Axis visibility changed to: $visible")
        }
    }

    // 修改方法以請求重繪
    fun setGridVisibility(visible: Boolean) {
        if (showGrid != visible) {
            showGrid = visible
            // 請求重繪
            glSurfaceView?.requestRender()
            log("Grid visibility changed to: $visible")
        }
    }

    fun setColorMode(mode: Int) {
        colorMode = mode
        // 這裡可以實現切換顏色模式的邏輯
        log("Color mode changed to: $mode")
    }

    fun toggleGrid(): Boolean {
        showGrid = !showGrid
        // 請求重繪
        glSurfaceView?.requestRender()
        log("Grid visibility: $showGrid")
        return showGrid
    }

    private fun createBuffer(points: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(points.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(points)
                position(0)
            }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                log("Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
            }
        }
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            log("GL Error after $operation: $error")
        }
    }

    private fun log(message: String) {
        Log.d("PointCloudRenderer", message)
    }

    fun hasReceivedFirstFrame(): Boolean = isFirstFrameReceived

    fun setMaxRenderPoints(maxPoints: Int) {
        maxRenderPoints = maxPoints
        // 可以在這裡添加日誌輸出
        log("Maximum render points set to: $maxRenderPoints")
    }

    // 在 PointCloudRenderer.kt 中添加以下方法

    fun adjustViewport(width: Int, height: Int) {
        // 更新視圖比例
        val ratio = width.toFloat() / height.toFloat()

        // 更新投影矩陣
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 100f)

        // 可能需要調整其他渲染參數，例如點的大小等

        // 更新 MVP 矩陣
        updateMVPMatrix()
    }

}