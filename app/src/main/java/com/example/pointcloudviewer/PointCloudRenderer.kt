package com.example.pointcloudviewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

class PointCloudRenderer : GLSurfaceView.Renderer {
    private val maxPoints = 850000
    private val pointsPerUpdate = 850000
    private val floatsPerPoint = 7
    var displayRatio: Float = 1.0f
    private val pointData = FloatArray(maxPoints * floatsPerPoint)
    private var numPoints = 0
    private var startPoint = 0
    private var vertexBuffer: FloatBuffer? = null
    private var program = 0
    private var isInitialized = false
    private var isReadyToRender = false

    private var showAxis = true
    private var showGrid = true
    private lateinit var axisPosBuffer: FloatBuffer
    private lateinit var axisColorBuffer: FloatBuffer
    private var axisProgram = 0

    private lateinit var gridBuffer: FloatBuffer
    private var gridProgram: Int = 0
    private var gridVertexCount: Int = 0

    private val BYTES_PER_FLOAT = 4
    private val pointVertexSize = 7

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val mvpLock = Object()
    private val latestMvpMatrix = FloatArray(16)

    private val cameraController = CameraController()

    private var currentColorMode = 0

    companion object {
        private const val TAG = "PointCloudRenderer"
    }

    private val axisVertices = floatArrayOf(
        0f, 0f, 0f, 1f, 0f, 0f, 1f,
        1f, 0f, 0f, 1f, 0f, 0f, 1f,
        0f, 0f, 0f, 0f, 1f, 0f, 1f,
        0f, 1f, 0f, 0f, 1f, 0f, 1f,
        0f, 0f, 0f, 0f, 0f, 1f, 1f,
        0f, 0f, 1f, 0f, 0f, 1f, 1f
    )

    private val vertexShaderCodeStr = """
        uniform mat4 uMVPMatrix;
        attribute vec3 aPosition;
        attribute float aIntensity;
        attribute vec3 aColor;
        varying float vIntensity;
        varying vec3 vColor;
        varying float vDepth;
        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            gl_PointSize = 2.0;
            vIntensity = aIntensity;
            vColor = aColor;
            vDepth = aPosition.z;
        }
    """.trimIndent()

    private val fragmentShaderCodeStr = """
        precision mediump float;
        varying float vIntensity;
        varying vec3 vColor;
        varying float vDepth;
        uniform int uColorMode;
        uniform float uIntensityThreshold; // 新增的 uniform
        
        void main() {
            // 根據強度過濾點
            if(vIntensity < uIntensityThreshold) {
                discard;
            }
            
            if(uColorMode == 0) {
                float normalizedIntensity = clamp(vIntensity / 255.0, 0.0, 1.0);
                vec3 intensityColor = mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), normalizedIntensity);
                gl_FragColor = vec4(intensityColor, 1.0);
            } else if(uColorMode == 1) {
                const float minDepth = -0.3;
                const float maxDepth = 0.3;
                float normalizedDepth = clamp((vDepth - minDepth) / (maxDepth - minDepth), 0.0, 1.0);
                gl_FragColor = vec4(normalizedDepth, 0.0, 1.0 - normalizedDepth, 1.0);
            } else if(uColorMode == 2) {
                vec3 mappedColor = (vColor + vec3(1.0)) * 0.5;
                gl_FragColor = vec4(mappedColor, 1.0);
            } else {
                gl_FragColor = vec4(1.0);
            }
        }

    """.trimIndent()

    private val axisVertexShaderCodeStr = """
        uniform mat4 uMVPMatrix;
        attribute vec3 aPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            vColor = aColor;
        }
    """.trimIndent()

    private val axisFragmentShaderCodeStr = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val gridVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec3 aPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            vColor = aColor;
        }
    """.trimIndent()

    private val gridFragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    fun generateSimulatedPointsBatch(): FloatArray {
        val batch = FloatArray(pointsPerUpdate * floatsPerPoint)
        val rings = 32
        val pointsPerRing = pointsPerUpdate / rings
        var offset = 0
        for (ring in 0 until rings) {
            val verticalAngle = -15f + (ring * 1f)
            for (i in 0 until pointsPerRing) {
                val horizontalAngle = (i.toFloat() / pointsPerRing) * 360f
                val distance = 0.5f + Math.random().toFloat() * 0.5f
                val rad_h = Math.toRadians(horizontalAngle.toDouble())
                val rad_v = Math.toRadians(verticalAngle.toDouble())
                val x = (distance * cos(rad_v) * cos(rad_h)).toFloat()
                val y = (distance * cos(rad_v) * sin(rad_h)).toFloat()
                val z = (distance * sin(rad_v)).toFloat()
                val intensity = ((x + 1f) / 2f) * 255f
                val norm = if (distance != 0f) distance else 1f
                val nx = x / norm
                val ny = y / norm
                val nz = z / norm
                batch[offset++] = x
                batch[offset++] = y
                batch[offset++] = z
                batch[offset++] = intensity
                batch[offset++] = nx
                batch[offset++] = ny
                batch[offset++] = nz
            }
        }
        return batch
    }

    fun updatePoints(newBatch: FloatArray) {
        if (!isInitialized) return
        val startTime = System.currentTimeMillis()
        if (numPoints < maxPoints) {
            val available = maxPoints - numPoints
            if (pointsPerUpdate <= available) {
                val startPos = numPoints * floatsPerPoint
                System.arraycopy(newBatch, 0, pointData, startPos, newBatch.size)
                numPoints += pointsPerUpdate
            } else {
                val toAdd = available
                val startPos = numPoints * floatsPerPoint
                System.arraycopy(newBatch, 0, pointData, startPos, toAdd * floatsPerPoint)
                numPoints = maxPoints
                val remaining = pointsPerUpdate - toAdd
                for (i in 0 until remaining) {
                    val idx = (startPoint + i) % maxPoints
                    System.arraycopy(newBatch, (toAdd + i) * floatsPerPoint, pointData, idx * floatsPerPoint, floatsPerPoint)
                }
                startPoint = (startPoint + remaining) % maxPoints
            }
        } else {
            for (i in 0 until pointsPerUpdate) {
                val idx = (startPoint + i) % maxPoints
                System.arraycopy(newBatch, i * floatsPerPoint, pointData, idx * floatsPerPoint, floatsPerPoint)
            }
            startPoint = (startPoint + pointsPerUpdate) % maxPoints
        }
        updateVertexBuffer()
        isReadyToRender = true
        val updateTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Point update took: ${updateTime}ms, numPoints: $numPoints")
    }

    private fun updateVertexBuffer() {
        vertexBuffer?.let { buffer ->
            buffer.clear()
            for (i in 0 until numPoints) {
                val idx = (startPoint + i) % maxPoints
                val base = idx * floatsPerPoint
                buffer.put(pointData, base, floatsPerPoint)
            }
            buffer.position(0)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        initShaders()
        initAxisShaders()
        initGridShaders()
        setupPointCloud()
        setupAxisBuffers()
        setupGrid()
        isInitialized = true
        Log.i(TAG, "Surface created, OpenGL initialized")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 10f)
        Log.i(TAG, "Surface changed: width=$width, height=$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!isInitialized) return
        val startTime = System.currentTimeMillis()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f)

        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, cameraController.getModelMatrix(), 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        synchronized(mvpLock) {
            System.arraycopy(mvpMatrix, 0, latestMvpMatrix, 0, 16)
        }
        if (showGrid) drawGrid()
        if (isReadyToRender) drawPointCloud()
        if (showAxis) drawAxis()

        val frameTime = System.currentTimeMillis() - startTime
        if (frameTime > 16) {
            Log.w(TAG, "Frame render took: ${frameTime}ms")
        }
    }

    private fun drawPointCloud() {
        GLES20.glUseProgram(program)
        val matrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mvpMatrix, 0)

        // 設定色彩模式
        val colorModeHandle = GLES20.glGetUniformLocation(program, "uColorMode")
        GLES20.glUniform1i(colorModeHandle, currentColorMode)

        // 設定強度過濾閥值，displayRatio 越低，閥值越高
        val thresholdHandle = GLES20.glGetUniformLocation(program, "uIntensityThreshold")
        GLES20.glUniform1f(thresholdHandle, (1 - displayRatio) * 255f)

        vertexBuffer?.let { buffer ->
            val stride = pointVertexSize * BYTES_PER_FLOAT
            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            buffer.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, stride, buffer)

            val intensityHandle = GLES20.glGetAttribLocation(program, "aIntensity")
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(intensityHandle)
            GLES20.glVertexAttribPointer(intensityHandle, 1, GLES20.GL_FLOAT, false, stride, buffer)

            val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
            buffer.position(4)
            GLES20.glEnableVertexAttribArray(colorHandle)
            GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, stride, buffer)

            // 繪製全部點，shader 內部會依照強度 discard 低強度的點
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(intensityHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
    }


    private fun drawAxis() {
        GLES20.glUseProgram(axisProgram)
        val matrixHandle = GLES20.glGetUniformLocation(axisProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mvpMatrix, 0)
        val posHandle = GLES20.glGetAttribLocation(axisProgram, "aPosition")
        val colorHandle = GLES20.glGetAttribLocation(axisProgram, "aColor")
        axisPosBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, axisPosBuffer)
        axisColorBuffer.position(0)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, axisColorBuffer)
        GLES20.glLineWidth(1.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun drawGrid() {
        GLES20.glUseProgram(gridProgram)
        val mvpHandle = GLES20.glGetUniformLocation(gridProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        val posHandle = GLES20.glGetAttribLocation(gridProgram, "aPosition")
        val colorHandle = GLES20.glGetAttribLocation(gridProgram, "aColor")
        gridBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 7 * BYTES_PER_FLOAT, gridBuffer)
        gridBuffer.position(3)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 7 * BYTES_PER_FLOAT, gridBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun initShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCodeStr)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCodeStr)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
    }

    private fun initAxisShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, axisVertexShaderCodeStr)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, axisFragmentShaderCodeStr)
        axisProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(axisProgram, vertexShader)
        GLES20.glAttachShader(axisProgram, fragmentShader)
        GLES20.glLinkProgram(axisProgram)
    }

    private fun initGridShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, gridVertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, gridFragmentShaderCode)
        gridProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(gridProgram, vertexShader)
        GLES20.glAttachShader(gridProgram, fragmentShader)
        GLES20.glLinkProgram(gridProgram)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling shader: $error")
        }
        return shader
    }

    private fun setupPointCloud() {
        val vb = ByteBuffer.allocateDirect(maxPoints * pointVertexSize * BYTES_PER_FLOAT)
        vb.order(ByteOrder.nativeOrder())
        vertexBuffer = vb.asFloatBuffer()
    }

    private fun setupAxisBuffers() {
        val vertexCount = 6
        axisPosBuffer = ByteBuffer.allocateDirect(vertexCount * 3 * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        axisColorBuffer = ByteBuffer.allocateDirect(vertexCount * 4 * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until vertexCount) {
            val offset = i * 7
            axisPosBuffer.put(axisVertices, offset, 3)
            axisColorBuffer.put(axisVertices, offset + 3, 4)
        }
        axisPosBuffer.position(0)
        axisColorBuffer.position(0)
    }

    private fun generateGridVertices(): FloatArray {
        val gridMin = -100f
        val gridMax = 100f
        val step = 1f
        val lines = mutableListOf<Float>()
        var x = gridMin
        while (x <= gridMax) {
            lines.add(x); lines.add(gridMin); lines.add(0f)
            lines.add(0.7f); lines.add(0.7f); lines.add(0.7f); lines.add(1f)
            lines.add(x); lines.add(gridMax); lines.add(0f)
            lines.add(0.7f); lines.add(0.7f); lines.add(0.7f); lines.add(1f)
            x += step
        }
        var y = gridMin
        while (y <= gridMax) {
            lines.add(gridMin); lines.add(y); lines.add(0f)
            lines.add(0.7f); lines.add(0.7f); lines.add(0.7f); lines.add(1f)
            lines.add(gridMax); lines.add(y); lines.add(0f)
            lines.add(0.7f); lines.add(0.7f); lines.add(0.7f); lines.add(1f)
            y += step
        }
        return lines.toFloatArray()
    }

    private fun setupGrid() {
        val gridVerts = generateGridVertices()
        gridVertexCount = gridVerts.size / 7
        val bb = ByteBuffer.allocateDirect(gridVerts.size * BYTES_PER_FLOAT)
        bb.order(ByteOrder.nativeOrder())
        gridBuffer = bb.asFloatBuffer()
        gridBuffer.put(gridVerts)
        gridBuffer.position(0)
    }

    fun rotate(dx: Float, dy: Float) = cameraController.rotate(dx, dy)
    fun scale(factor: Float) = cameraController.scale(factor)
    fun translate(dx: Float, dy: Float) = cameraController.translate(dx, dy)
    fun resetView() = cameraController.resetView()

    fun toggleAxis() {
        showAxis = !showAxis
    }

    fun isAxisVisible(): Boolean = showAxis

    fun setAxisVisibility(visible: Boolean) {
        showAxis = visible
    }

    fun setGridVisibility(visible: Boolean) {
        showGrid = visible
    }

    fun setColorMode(mode: Int) {
        currentColorMode = mode
    }

    fun getColorMode(): Int = currentColorMode

    fun cycleColorMode() {
        currentColorMode = (currentColorMode + 1) % 3
    }

    fun pickPoint(screenX: Float, screenY: Float, screenWidth: Int, screenHeight: Int): FloatArray? {
        val currentMvp = FloatArray(16)
        synchronized(mvpLock) {
            System.arraycopy(latestMvpMatrix, 0, currentMvp, 0, 16)
        }
        var minDistance = Float.MAX_VALUE
        var pickedPoint: FloatArray? = null
        val threshold = 30f
        for (i in 0 until numPoints) {
            val idx = (startPoint + i) % maxPoints
            val base = idx * floatsPerPoint
            val x = pointData[base]
            val y = pointData[base + 1]
            val z = pointData[base + 2]
            val pointVec = floatArrayOf(x, y, z, 1f)
            val clipVec = FloatArray(4)
            Matrix.multiplyMV(clipVec, 0, currentMvp, 0, pointVec, 0)
            if (clipVec[3] == 0f) continue
            val ndcX = clipVec[0] / clipVec[3]
            val ndcY = clipVec[1] / clipVec[3]
            val screenXPoint = ((ndcX + 1f) / 2f) * screenWidth
            val screenYPoint = ((1f - ndcY) / 2f) * screenHeight
            val dx = screenX - screenXPoint
            val dy = screenY - screenYPoint
            val distance = dx * dx + dy * dy
            if (distance < minDistance && distance < threshold * threshold) {
                minDistance = distance
                pickedPoint = pointData.copyOfRange(base, base + floatsPerPoint)
            }
        }
        return pickedPoint
    }
}