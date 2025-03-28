package com.example.pointcloudviewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayList
import kotlin.math.cos
import kotlin.math.sin

@SuppressLint("StaticFieldLeak")
object UDPManager {
    private const val TAG = "UDPManager"
    private const val UDP_PORT = 7000
    private const val SOCKET_BUFFER_SIZE = 1024 * 1024 * 1024
    private const val PACKET_BUFFER_SIZE = 100000
    private const val MAX_QUEUE_SIZE = 1000000
    private const val FRAME_MARKER_INTERVAL = 4000
    private var onStatusUpdate: ((String) -> Unit)? = null
    private val frameBufferSize = 1  // 固定為 3 個 frame
    private val frameBuffer = ArrayList<ArrayList<LidarPoint>>(frameBufferSize)
    private var currentFrameIndex = 0
    private var displayRatio = 1.0f  // 顯示比例，默認為 1.0（顯示全部）
    private var enableIntensityFilter = false  // 新增開關，默認為關閉

    // 提供外部設置 displayRatio 的方法
    fun setDisplayRatio(ratio: Float) {
        displayRatio = ratio.coerceIn(0.0f, 1.0f)
        log("Display ratio updated to: $displayRatio")
    }

    // 提供外部設置強度過濾開關的方法
    fun setIntensityFilterEnabled(enabled: Boolean) {
        enableIntensityFilter = enabled
        log("Intensity filter ${if (enabled) "enabled" else "disabled"}")
    }

    object LidarConstants {
        const val HEADER_SIZE = 32
        const val DATA_SIZE = 784
        const val PACKET_SIZE = 816
        const val POINTS_PER_PACKET = 260
        const val TOTAL_POINTS_PER_LINE = 520
        val HEADER_MAGIC = byteArrayOf(0x55.toByte(), 0xaa.toByte(), 0x5a.toByte(), 0xa5.toByte())
        const val HEADER_START_OFFSET = 0
        const val DATA_START_OFFSET = 32
        const val LINES_PER_FRAME = 6000
        const val AZIMUTH_RESOLUTION = 0.0439f
        const val ELEVATION_START_UPPER = 12.975f
        const val ELEVATION_START_LOWER = -0.025f
        const val ELEVATION_STEP = -0.05f
        const val PACKET_UPPER = 0x10.toByte()
        const val PACKET_LOWER = 0x20.toByte()
        const val ECHO_1ST = 0x01.toByte()
        const val ECHO_2ND = 0x02.toByte()
        var LOOKUP_TABLE = intArrayOf(1454, 1459, 1463, 1468, 1472)
    }

    // 定义帧边界检测的常量 - 更精确的阈值
    private const val START_AZIMUTH_THRESHOLD = 2.0f    // 起始角度阈值（度）- 更精确
    private const val END_AZIMUTH_THRESHOLD = 358.0f    // 结束角度阈值（度）- 更精确
    private const val AZIMUTH_JUMP_LOWER = 60.0f        // 角度跳变下限
    private const val AZIMUTH_JUMP_UPPER = 300.0f       // 角度跳变上限
    private const val MIN_VALID_POINTS_RATIO = 0.5f     // 最小有效点比率

    private object FrameMarker {
        val BYTES = byteArrayOf(0x00, 0x00, 0x00, 0x00)
    }

    data class LidarPoint(
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var intensity: Float = 0f,
        var azimuth: Float = 0f,
        var elevation: Float = 0f,
        var valid: Boolean = false,
        var echoNum: Byte = 0
    )

    enum class EchoMode {
        ECHO_MODE_ALL,
        ECHO_MODE_1ST,
        ECHO_MODE_2ND
    }

    private var udpJob: Job? = null
    private var processingJob: Job? = null
    private val packetQueue = ConcurrentLinkedQueue<ByteArray>()
    private val totalBytesReceived = AtomicLong(0)
    private val totalPacketsReceived = AtomicLong(0)
    private val bytesInLastSecond = AtomicLong(0)
    private val frameLock = Any()
    private val isFrameReady = AtomicBoolean(false)
    private val pointsBuffer = ArrayList<LidarPoint>()
    private val framePointsBuffer = ArrayList<LidarPoint>()
    private val latestCompleteFrame = ArrayList<LidarPoint>()
    private val nextFrame = ArrayList<LidarPoint>()
    private var packetCounter = 0

    // 帧边界跟踪变量
    private var foundFrameStart = false
    private var foundFrameEnd = false
    private var currentLineAzimuth = 0.0f
    private var previousLineAzimuth = 0.0f
    private var azimuthJumpDetected = false
    private var frameStartTimestamp = 0L
    private var frameEndTimestamp = 0L
    private var frameCompletionTimes = ArrayList<Long>()

    // 点云质量监控
    private var currentLinePointCount = 0
    private var previousLinePointCount = 0
    private var consecutiveEmptyLines = 0
    private var totalPointsInFrame = 0

    // 帧统计变量
    private var completeFrameCount = 0
    private var incompleteFrameCount = 0
    private var avgFrameCompletionTime = 0L
    private var minAzimuthInFrame = 360.0f
    private var maxAzimuthInFrame = 0.0f

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: PointCloudRenderer? = null
    private var onDataRateUpdate: ((Double) -> Unit)? = null
    private var echoMode = EchoMode.ECHO_MODE_2ND
    private var context: Context? = null
    private var isRendering = AtomicBoolean(false)
    private var validPacketsCount = 0
    private var invalidPacketsCount = 0
    private var processedPacketsCount = 0

    private const val QUEUE_WARNING_THRESHOLD = MAX_QUEUE_SIZE * 0.7 // 70% 時開始警告並漸進丟棄
    private const val QUEUE_CRITICAL_THRESHOLD = MAX_QUEUE_SIZE * 0.9 // 90% 時加速丟棄
    private const val RESET_INTERVAL_MS = 1000L // 每 1 秒檢查一次
    private const val QUEUE_RESET_THRESHOLD = MAX_QUEUE_SIZE * 1.2 // 超過 120% 時重置


    private var resetInProgress = false // 添加：標記是否正在重置
    private var skipNextRender = false


    private fun loadLookupTableFromResource(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("lookup_table", "raw", context.packageName)
            )
            val text = inputStream.bufferedReader().use { it.readText() }
            val values = text.split(",").map { it.trim().toInt() }
            LidarConstants.LOOKUP_TABLE = values.toIntArray()
            log("Successfully loaded lookup table, size: ${LidarConstants.LOOKUP_TABLE.size}")
        } catch (e: Exception) {
            log("Failed to load lookup table: ${e.message}")
            LidarConstants.LOOKUP_TABLE = IntArray(8192) { it * 10 }
            log("Using default lookup table")
        }
    }

    fun initialize(
        glSurfaceView: GLSurfaceView?,
        renderer: PointCloudRenderer?,
        onDataRateUpdate: (Double) -> Unit,
        onStatusUpdate: ((String) -> Unit)? = null
    ) {
        this.glSurfaceView = glSurfaceView
        this.renderer = renderer
        this.onDataRateUpdate = onDataRateUpdate
        this.onStatusUpdate = onStatusUpdate
        context = glSurfaceView?.context
        context?.let { loadLookupTableFromResource(it) }
        if (udpJob == null || processingJob == null) {
            log("Starting UDP listening")
            udpJob = startUdpReceiver()
            processingJob = startPacketProcessor()
            CoroutineScope(Dispatchers.Default).launch {
                startFrameChecker()
            }
            onStatusUpdate?.invoke("UDP Manager Initialized with improved azimuth detection")
        }
    }

    fun setEchoMode(mode: EchoMode) {
        echoMode = mode
    }
    init {
        repeat(frameBufferSize) {
            frameBuffer.add(ArrayList<LidarPoint>(LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME / frameBufferSize))
        }
        nextFrame.ensureCapacity(LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME)
    }
    // 在 startUdpReceiver 中改進丟棄邏輯
    @OptIn(InternalCoroutinesApi::class)
    private fun startUdpReceiver(): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                log("Device as host, starting UDP listen on port $UDP_PORT")
                val socket = DatagramSocket(UDP_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                    receiveBufferSize = SOCKET_BUFFER_SIZE
                }
                val speedMonitorJob = launch {
                    var lastUpdateTime = System.nanoTime()
                    while (isActive) {
                        delay(1000)
                        val currentTime = System.nanoTime()
                        val elapsedSeconds = (currentTime - lastUpdateTime) / 1_000_000_000.0
                        val bytesReceived = bytesInLastSecond.getAndSet(0)
                        val speedMBps = bytesReceived / (1024.0 * 1024.0) / elapsedSeconds
                        val packetStats = "Valid: $validPacketsCount, Invalid: $invalidPacketsCount, Processed: $processedPacketsCount"
                        withContext(Dispatchers.Main) {
                            onDataRateUpdate?.invoke(speedMBps)
                            onStatusUpdate?.invoke("Packet stats: $packetStats")
                        }
                        lastUpdateTime = currentTime
                    }
                }
                try {
                    val receiveBuffer = ByteArray(PACKET_BUFFER_SIZE)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    var droppedPairs = 0
                    val packetPair = arrayOfNulls<ByteArray>(2)
                    var pairIndex = 0
                    var lastResetTime = System.currentTimeMillis()

                    while (isActive) {
                        socket.receive(receivePacket)
                        val dataLength = receivePacket.length
                        val actualData = ByteArray(dataLength)
                        System.arraycopy(receiveBuffer, 0, actualData, 0, dataLength)

                        totalBytesReceived.addAndGet(dataLength.toLong())
                        bytesInLastSecond.addAndGet(dataLength.toLong())
                        totalPacketsReceived.incrementAndGet()

                        val packetType = if (dataLength == LidarConstants.PACKET_SIZE) {
                            (actualData[LidarConstants.DATA_START_OFFSET + 1].toInt() and 0xF0).toByte()
                        } else {
                            null
                        }

                        when (packetType) {
                            LidarConstants.PACKET_UPPER -> {
                                if (pairIndex == 0) {
                                    packetPair[0] = actualData
                                    pairIndex = 1
                                } else {
                                    packetPair[0] = actualData
                                    pairIndex = 1
                                }
                            }
                            LidarConstants.PACKET_LOWER -> {
                                if (pairIndex == 1 && packetPair[0] != null) {
                                    packetPair[1] = actualData
                                    packetQueue.offer(packetPair[0]!!)
                                    packetQueue.offer(packetPair[1]!!)
                                    pairIndex = 0
                                    packetCounter++
                                    if (packetCounter >= FRAME_MARKER_INTERVAL) {
                                        packetCounter = 0
                                        packetQueue.offer(FrameMarker.BYTES)
                                    }
                                } else {
                                    pairIndex = 0
                                }
                            }
                            else -> {
                                pairIndex = 0
                            }
                        }

                        // 定時清空隊列（每秒）
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastResetTime >= RESET_INTERVAL_MS) { // RESET_INTERVAL_MS = 1000L
                            log("Resetting packetQueue after 1 second, size was ${packetQueue.size}")
                            packetQueue.clear()
                            droppedPairs = 0
                            packetCounter = 0
                            pairIndex = 0
                            packetPair[0] = null
                            packetPair[1] = null
                            resetInProgress = true // 添加：標記重置開始
                            synchronized(frameLock) { // 添加：同步訪問 nextFrame
                                nextFrame.clear() // 添加：清空正在收集的 nextFrame
                                resetFrameTracking() // 添加：重置幀追蹤狀態
                            }
                            skipNextRender = true // 添加：跳過下一次渲染
                            resetInProgress = false // 添加：重置完成
                            lastResetTime = currentTime
                        }

                        receivePacket.setLength(receiveBuffer.size)
                    }
                } finally {
                    speedMonitorJob.cancel()
                    socket.close()
                    log("UDP listening closed, received ${totalPacketsReceived.get()} packets")
                }
            } catch (e: Exception) {
                log("UDP listening failed: ${e.message}")
                delay(1000)
                if (isActive) {
                    startUdpReceiver()
                }
            }
        }
    }

    private fun startPacketProcessor(): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            log("Starting packet processor with improved azimuth detection")
            nextFrame.clear()
            nextFrame.ensureCapacity(LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME)
            resetFrameTracking()

            while (isActive) {
                val packet = packetQueue.poll()
                if (packet == null) {
                    delay(10)
                    continue
                }

                if (packet === FrameMarker.BYTES) {
                    // 使用帧标记作为备用机制
                    checkFrameCompletion(true, "Frame marker")
                    continue
                }

                processedPacketsCount++
                if (packet.size != LidarConstants.PACKET_SIZE || !checkPacketHeader(packet)) {
                    invalidPacketsCount++
                    continue
                }

                validPacketsCount++
                pointsBuffer.clear()
                val isUpperPacket = parseUdpPacket(packet, pointsBuffer, echoMode)

                if (pointsBuffer.isNotEmpty()) {
                    // 使用专门的方法处理方位角跟踪
                    updateAzimuthTracking(pointsBuffer[0].azimuth, pointsBuffer.size)

                    // 更新帧内的最小和最大方位角
                    if (foundFrameStart && !foundFrameEnd) {
                        if (currentLineAzimuth < minAzimuthInFrame) minAzimuthInFrame = currentLineAzimuth
                        if (currentLineAzimuth > maxAzimuthInFrame) maxAzimuthInFrame = currentLineAzimuth
                    }

                    // 检查是否为起始点
                    if (!foundFrameStart && isFrameStart()) {
                        foundFrameStart = true
                        frameStartTimestamp = System.currentTimeMillis()
                        minAzimuthInFrame = currentLineAzimuth
                        maxAzimuthInFrame = currentLineAzimuth
                        totalPointsInFrame = 0
                        log("Frame start detected at azimuth: $currentLineAzimuth with ${pointsBuffer.size} points")

                        // 如果在检测到起始点但已有之前帧的点，则创建新帧
                        if (nextFrame.isNotEmpty()) {
                            log("Creating new frame at start boundary, discarding ${nextFrame.size} points")
                            nextFrame.clear()
                        }
                    }

                    // 将点添加到当前帧
                    nextFrame.addAll(pointsBuffer)
                    totalPointsInFrame += pointsBuffer.size

                    // 检查是否为结束点
                    if (foundFrameStart && !foundFrameEnd && isFrameEnd()) {
                        foundFrameEnd = true
                        frameEndTimestamp = System.currentTimeMillis()
                        val frameTime = frameEndTimestamp - frameStartTimestamp
                        val azimuthCoverage = maxAzimuthInFrame - minAzimuthInFrame
                        log("Frame end detected at azimuth: $currentLineAzimuth, frame completed in $frameTime ms")
                        log("Frame azimuth coverage: $minAzimuthInFrame - $maxAzimuthInFrame (${azimuthCoverage}°)")

                        // 如果找到了起始点和结束点，则表示帧完整
                        checkFrameCompletion(false, "Natural end")
                    }
                } else {
                    // 连续空行处理
                    consecutiveEmptyLines++
                    if (foundFrameStart && consecutiveEmptyLines > 10) {
                        log("Too many consecutive empty lines ($consecutiveEmptyLines), forcing frame completion")
                        checkFrameCompletion(true, "Empty lines")
                    }
                }

                // 备用机制：如果点数足够多，则认为帧完整
                val expectedPoints = LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME
                if (nextFrame.size >= expectedPoints) {
                    log("Frame size threshold reached with ${nextFrame.size} points (expected $expectedPoints)")
                    checkFrameCompletion(true, "Size threshold")
                }

                // 如果帧已经开始但长时间未结束，强制完成
                if (foundFrameStart && !foundFrameEnd &&
                    System.currentTimeMillis() - frameStartTimestamp > 1000) {
                    log("Frame timeout after ${System.currentTimeMillis() - frameStartTimestamp}ms")
                    checkFrameCompletion(true, "Timeout")
                }
            }
        }
    }

    private fun resetFrameTracking() {
        foundFrameStart = false
        foundFrameEnd = false
        currentLineAzimuth = 0.0f
        previousLineAzimuth = 0.0f
        azimuthJumpDetected = false
        currentLinePointCount = 0
        previousLinePointCount = 0
        consecutiveEmptyLines = 0
        totalPointsInFrame = 0
        minAzimuthInFrame = 360.0f
        maxAzimuthInFrame = 0.0f
    }

    private fun updateAzimuthTracking(currentAzimuth: Float, pointCount: Int) {
        previousLineAzimuth = currentLineAzimuth
        previousLinePointCount = currentLinePointCount
        currentLineAzimuth = currentAzimuth
        currentLinePointCount = pointCount

        // 检测方位角是否发生了从接近360度到接近0度的跳变
        // 这是一个完整扫描周期结束的标志
        if (previousLineAzimuth > AZIMUTH_JUMP_UPPER && currentLineAzimuth < AZIMUTH_JUMP_LOWER) {
            azimuthJumpDetected = true
            log("Azimuth cycle detected: $previousLineAzimuth° -> $currentLineAzimuth°")

            // 如果点数正常，这是有效的循环
            if (currentLinePointCount > LidarConstants.POINTS_PER_PACKET * MIN_VALID_POINTS_RATIO) {
                log("Valid azimuth cycle with ${currentLinePointCount} points")
            } else {
                log("Suspicious azimuth cycle with only ${currentLinePointCount} points")
            }
        }

        // 重置连续空行计数
        if (pointCount > 0) {
            consecutiveEmptyLines = 0
        }
    }

    private fun isFrameStart(): Boolean {
        // 使用更精细的起始检测逻辑
        // 1. 检测到方位角非常接近0度（小于2度）- 表示扫描起始
        // 2. 已经检测到从高角度到低角度的跳变
        // 3. 确保点数正常（避免噪声影响）
        val validPointCount = currentLinePointCount > LidarConstants.POINTS_PER_PACKET * MIN_VALID_POINTS_RATIO
        return (currentLineAzimuth < START_AZIMUTH_THRESHOLD && azimuthJumpDetected && validPointCount)
    }

    private fun isFrameEnd(): Boolean {
        // 使用更精细的结束检测逻辑
        // 1. 检测到方位角非常接近360度（大于358度）- 表示扫描结束
        // 2. 确保点数正常（避免噪声影响）
        // 3. 确保帧已经包含了足够的点
        val validPointCount = currentLinePointCount > LidarConstants.POINTS_PER_PACKET * MIN_VALID_POINTS_RATIO
        val sufficientFramePoints = totalPointsInFrame > LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME * 0.8
        return (currentLineAzimuth > END_AZIMUTH_THRESHOLD && validPointCount && sufficientFramePoints)
    }

    private fun checkFrameCompletion(forcedCompletion: Boolean, reason: String) {
        val isComplete = foundFrameStart && foundFrameEnd
        val frameSize = nextFrame.size

        if (isComplete || forcedCompletion) {
            if (frameSize > 0) {
                val expectedPoints = LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME
                val completeness = (frameSize * 100) / expectedPoints
                log("${if (isComplete) "Complete" else "Forced ($reason)"} frame with $frameSize points ($completeness% complete)")

                synchronized(frameLock) {
                    // 原始代碼
                    val pointsToStore = if (enableIntensityFilter) {
                        val sortedPoints = nextFrame.sortedByDescending { it.intensity }
                        val pointsToKeep = (frameSize * displayRatio).toInt().coerceAtLeast(1)
                        sortedPoints.take(pointsToKeep)
                    } else {
                        nextFrame.toList()
                    }

                    // 將處理後的點存入 frame buffer
                    frameBuffer[currentFrameIndex].clear()
                    frameBuffer[currentFrameIndex].addAll(pointsToStore)
                    currentFrameIndex = (currentFrameIndex + 1) % frameBufferSize
                    isFrameReady.set(true)
                    nextFrame.clear()

                    log("Stored ${pointsToStore.size} points in frame buffer (filter: $enableIntensityFilter, ratio: $displayRatio)")
                }

                if (isComplete) {
                    completeFrameCount++
                    val completionTime = frameEndTimestamp - frameStartTimestamp
                    frameCompletionTimes.add(completionTime)
                    if (frameCompletionTimes.size > 10) frameCompletionTimes.removeAt(0)
                    avgFrameCompletionTime = frameCompletionTimes.average().toLong()
                } else {
                    incompleteFrameCount++
                }

                val completionRate = if (completeFrameCount + incompleteFrameCount > 0) {
                    (completeFrameCount * 100) / (completeFrameCount + incompleteFrameCount)
                } else 0
                log("Frame stats: Complete: $completeFrameCount, Incomplete: $incompleteFrameCount, " +
                        "Completion rate: $completionRate%, Avg time: $avgFrameCompletionTime ms")
            }
            resetFrameTracking()
        }
    }

    private fun sendPointsToRenderer() {
        val rendererCopy = renderer
        val surfaceViewCopy = glSurfaceView

        if (rendererCopy != null && surfaceViewCopy != null) {
            synchronized(frameLock) {
                if (skipNextRender) { // 添加：檢查是否跳過渲染
                    log("Skipping render due to reset")
                    skipNextRender = false // 添加：重置標誌
                    return // 添加：跳過這次渲染
                }

                val allPoints = ArrayList<LidarPoint>()
                frameBuffer.forEach { frame ->
                    allPoints.addAll(frame)
                }

                if (allPoints.isNotEmpty()) {
                    val pointCount = allPoints.size
                    log("Sending $pointCount points from $frameBufferSize frames to renderer")
                    val pointArray = FloatArray(pointCount * 7)
                    allPoints.forEachIndexed { index, point ->
                        val offset = index * 7
                        pointArray[offset] = point.x
                        pointArray[offset + 1] = point.z
                        pointArray[offset + 2] = -point.y
                        pointArray[offset + 3] = point.intensity
                        val norm = if (point.x != 0f || point.y != 0f || point.z != 0f) {
                            val d = Math.sqrt((point.x * point.x + point.y * point.y + point.z * point.z).toDouble()).toFloat()
                            if (d < 0.001f) 1f else d
                        } else 1f
                        pointArray[offset + 4] = point.x / norm
                        pointArray[offset + 5] = point.z / norm
                        pointArray[offset + 6] = -point.y / norm
                    }

                    surfaceViewCopy.queueEvent {
                        rendererCopy.updatePoints(pointArray)
                        surfaceViewCopy.requestRender()
                        log("Render requested with $pointCount points")
                        onStatusUpdate?.invoke("Rendered $pointCount points from $frameBufferSize frames")
                    }
                }
            }
        }
    }

    private suspend fun startFrameChecker() {
        var lastQueueSize = 0
        var stallCounter = 0

        while (true) {
            if (isFrameReady.get()) {
                isRendering.set(true)
                sendPointsToRenderer()
                isFrameReady.set(false)
                isRendering.set(false)
            }

            val currentQueueSize = packetQueue.size
            if (currentQueueSize == lastQueueSize && currentQueueSize > 0) {
                stallCounter++
                if (stallCounter > 20) {
                    log("Force creating a frame after stall")
                    checkFrameCompletion(true, "Queue stall")  // 使用帧完成检查功能
                    stallCounter = 0
                }
            } else {
                stallCounter = 0
            }

            lastQueueSize = currentQueueSize
            val pointsCollected = nextFrame.size

            // 增强状态更新，包含帧完整性信息
            val frameStatus = if (foundFrameStart && !foundFrameEnd) {
                "Partial frame (started)"
            } else if (!foundFrameStart && !foundFrameEnd) {
                "Collecting points"
            } else {
                "Frame complete"
            }

            val completionRate = if (completeFrameCount + incompleteFrameCount > 0) {
                (completeFrameCount * 100) / (completeFrameCount + incompleteFrameCount)
            } else 0

            val azimuthInfo = if (foundFrameStart) {
                ", Azimuth: $minAzimuthInFrame° - $currentLineAzimuth°"
            } else {
                ", Current azimuth: $currentLineAzimuth°"
            }

            onStatusUpdate?.invoke("Packets: $currentQueueSize, Points: $pointsCollected$azimuthInfo\n" +
                    "Frame: $frameStatus, Completion: $completionRate%")
            delay(250)
        }
    }

    private fun checkPacketHeader(data: ByteArray): Boolean {
        return (data.size == LidarConstants.PACKET_SIZE) &&
                (data[0] == LidarConstants.HEADER_MAGIC[0]) &&
                (data[1] == LidarConstants.HEADER_MAGIC[1]) &&
                (data[2] == LidarConstants.HEADER_MAGIC[2]) &&
                (data[3] == LidarConstants.HEADER_MAGIC[3])
    }

    private fun parseUdpPacket(data: ByteArray, points: MutableList<LidarPoint>, echoMode: EchoMode): Boolean {
        val returnSeq = data[LidarConstants.DATA_START_OFFSET + 1].toInt() and 0xFF
        val packetType = (returnSeq and 0xF0).toByte()
        val echoNum = (returnSeq and 0x0F).toByte()

        when (echoMode) {
            EchoMode.ECHO_MODE_1ST -> if (echoNum != LidarConstants.ECHO_1ST) return false
            EchoMode.ECHO_MODE_2ND -> if (echoNum != LidarConstants.ECHO_2ND) return false
            else -> {}
        }

        if (packetType != LidarConstants.PACKET_UPPER && packetType != LidarConstants.PACKET_LOWER) return false
        val isUpperPacket = packetType == LidarConstants.PACKET_UPPER

        // 解析方位角 - 更接近C++实现
        val azimuthRawIn = ((data[LidarConstants.DATA_START_OFFSET + 3].toInt() and 0xFF) shl 8) or
                (data[LidarConstants.DATA_START_OFFSET + 2].toInt() and 0xFF)

        // 使用lookup表计算实际方位角，与C++代码完全一致
        val lookupTable = LidarConstants.LOOKUP_TABLE
        val azimuth = if (azimuthRawIn < lookupTable.size) {
            lookupTable[azimuthRawIn] / 100.0f
        } else {
            // 如果超出lookup表范围，使用默认计算方法
            (azimuthRawIn * LidarConstants.AZIMUTH_RESOLUTION)
        }

        // 预先计算三角函数值
        val radAzimuth = Math.toRadians(azimuth.toDouble()).toFloat()
        val cosAzimuth = cos(radAzimuth)
        val sinAzimuth = sin(radAzimuth)

        // 预先获取起始仰角
        val elevationStart = if (isUpperPacket) LidarConstants.ELEVATION_START_UPPER else LidarConstants.ELEVATION_START_LOWER

        if (isUpperPacket && azimuthRawIn % 100 == 0) {
            log("[Debug msg] elevation_start: $elevationStart, azimuth: $azimuth")
        }

        val pointStart = LidarConstants.DATA_START_OFFSET + 4
        points.ensureCapacity(points.size + LidarConstants.POINTS_PER_PACKET)

        for (i in 0 until LidarConstants.POINTS_PER_PACKET) {
            val dataOffset = pointStart + (i * 3)
            if (dataOffset + 2 >= data.size) continue

            val intensity = (data[dataOffset].toInt() and 0xFF).toFloat()
            var radius = (((data[dataOffset + 2].toInt() and 0xFF) shl 8) or
                    (data[dataOffset + 1].toInt() and 0xFF)).toFloat() / 16.0f
            radius = (radius + 16.0f) * 0.15f  // Match C++ radius calculation

            if (intensity > 255) continue

            val elevation = elevationStart + (i * LidarConstants.ELEVATION_STEP)
            val radElevation = Math.toRadians(elevation.toDouble()).toFloat()
            val cosElevation = cos(radElevation)
            val sinElevation = sin(radElevation)

            val point = LidarPoint(
                y = radius * cosElevation * cosAzimuth,    // Match C++ X-Y swap
                x = radius * cosElevation * sinAzimuth,    // Match C++ X-Y swap
                z = radius * sinElevation,
                intensity = intensity,
                azimuth = azimuth,
                elevation = elevation,
                valid = true,
                echoNum = echoNum
            )
            points.add(point)

            // Debug output matching C++ format for first two points
            if ((i == 1 || i == 2) && isUpperPacket && azimuthRawIn % 500 == 0) {
                log("[Debug msg] No of point & intensity & radius: $i, $intensity, $radius")
                log("[Debug msg] set XYZ: ${point.x}, ${point.y}, ${point.z}")
            }
        }

        return isUpperPacket
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}

private fun <E> MutableList<E>.ensureCapacity(requiredCapacity: Int) {
    if (this is ArrayList<*>) {
        this.ensureCapacity(requiredCapacity)
    }
}