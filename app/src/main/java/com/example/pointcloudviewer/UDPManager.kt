package com.example.pointcloudviewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.pointcloudviewer.UDPManager.LidarConstants.LOOKUP_TABLE
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayList
import kotlin.math.cos
import kotlin.math.sin

@SuppressLint("StaticFieldLeak")
object UDPManager {
    private const val TAG = "UDPManager"
    private const val UDP_PORT = 7000
    private const val SOCKET_BUFFER_SIZE = 64 * 1024 * 1024
    private const val PACKET_BUFFER_SIZE = 65535
    private const val MAX_QUEUE_SIZE = 10000
    private const val REDUCED_QUEUE_SIZE = 5000

    object LidarConstants {
        const val HEADER_SIZE = 32
        const val DATA_SIZE = 784
        const val PACKET_SIZE = 816
        const val POINTS_PER_PACKET = 260
        const val TOTAL_POINTS_PER_LINE = 520
        val HEADER_MAGIC = byteArrayOf(0x55.toByte(), 0xaa.toByte(), 0x5a.toByte(), 0xa5.toByte())
        const val HEADER_START_OFFSET = 0
        const val DATA_START_OFFSET = 32
        const val LINES_PER_FRAME = 1990
        const val AZIMUTH_RESOLUTION = 0.0439f
        const val ELEVATION_START_UPPER = 12.975f
        const val ELEVATION_START_LOWER = -0.025f
        const val ELEVATION_STEP = -0.05f
        const val PACKET_UPPER = 0x10.toByte()
        const val PACKET_LOWER = 0x20.toByte()
        const val ECHO_1ST = 0x01.toByte()
        const val ECHO_2ND = 0x02.toByte()
        lateinit var LOOKUP_TABLE: IntArray
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
    private val packetBufferPool = Array(64) { ByteArray(PACKET_BUFFER_SIZE) }
    private var bufferPoolIndex = 0
    private val pointsBuffer = ArrayList<LidarPoint>()
    private val framePointsBuffer = ArrayList<LidarPoint>()

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: PointCloudRenderer? = null
    private var onDataRateUpdate: ((Double) -> Unit)? = null
    private var echoMode = EchoMode.ECHO_MODE_ALL
    private var context: Context? = null

    private fun loadLookupTableFromResource(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("lookup_table", "raw", context.packageName)
            )
            val text = inputStream.bufferedReader().use { it.readText() }
            val values = text.split(",").map { it.trim().toInt() }
            LidarConstants.LOOKUP_TABLE = values.toIntArray()
            log("成功從資源文件載入查找表，大小: ${LidarConstants.LOOKUP_TABLE.size}")
        } catch (e: Exception) {
            log("無法載入查找表: ${e.message}")
            LidarConstants.LOOKUP_TABLE = intArrayOf(1454, 1459, 1463, 1468, 1472)
            log("使用預設查找表")
        }
    }

    fun initialize(glSurfaceView: GLSurfaceView, renderer: PointCloudRenderer, onDataRateUpdate: (Double) -> Unit) {
        this.glSurfaceView = glSurfaceView
        this.renderer = renderer
        this.onDataRateUpdate = onDataRateUpdate
        this.context = glSurfaceView.context
        context?.let { loadLookupTableFromResource(it) }
        if (udpJob == null || processingJob == null) {
            log("啟動 UDP 監聽")
            udpJob = startUdpReceiver()
            processingJob = startPacketProcessor()
        }
    }

    fun setEchoMode(mode: EchoMode) {
        echoMode = mode
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun startUdpReceiver(): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                log("手機作為主機，開始監聽 UDP 端口 $UDP_PORT")
                val socket = DatagramSocket(UDP_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                    try {
                        receiveBufferSize = SOCKET_BUFFER_SIZE
                        log("已設置 Socket 接收緩衝區大小: $receiveBufferSize bytes")
                    } catch (e: Exception) {
                        log("警告: 無法設置請求的緩衝區大小: ${e.message}")
                        log("當前緩衝區大小: $receiveBufferSize bytes")
                    }
                }
                val speedMonitorJob = launch {
                    var lastUpdateTime = System.nanoTime()
                    while (isActive) {
                        delay(1000)
                        val currentTime = System.nanoTime()
                        val elapsedSeconds = (currentTime - lastUpdateTime) / 1_000_000_000.0
                        val bytesReceived = bytesInLastSecond.getAndSet(0)
                        val speedMBps = bytesReceived / (1024.0 * 1024.0) / elapsedSeconds
                        withContext(Dispatchers.Main) {
                            onDataRateUpdate?.invoke(speedMBps)
                        }
                        lastUpdateTime = currentTime
                    }
                }
                try {
                    val receiveBuffer = ByteArray(PACKET_BUFFER_SIZE)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    while (isActive) {
                        try {
                            socket.receive(receivePacket)
                            val dataLength = receivePacket.length
                            val bufferIndex = synchronized(packetBufferPool) {
                                val index = bufferPoolIndex
                                bufferPoolIndex = (bufferPoolIndex + 1) % packetBufferPool.size
                                index
                            }
                            val actualData = packetBufferPool[bufferIndex]
                            System.arraycopy(receiveBuffer, 0, actualData, 0, dataLength)
                            totalBytesReceived.addAndGet(dataLength.toLong())
                            bytesInLastSecond.addAndGet(dataLength.toLong())
                            totalPacketsReceived.incrementAndGet()
                            packetQueue.offer(actualData)
                            if (packetQueue.size > MAX_QUEUE_SIZE) {
                                log("警告: 隊列過大 (${packetQueue.size})，丟棄舊數據")
                                while (packetQueue.size > REDUCED_QUEUE_SIZE) {
                                    packetQueue.poll()
                                }
                            }
                            receivePacket.setLength(receiveBuffer.size)
                        } catch (e: Exception) {
                            log("接收數據時出錯: ${e.message}")
                            delay(1000)
                        }
                    }
                } finally {
                    speedMonitorJob.cancel()
                    socket.close()
                    log("關閉 UDP 監聽，共接收 ${totalPacketsReceived.get()} 個封包，總計 ${totalBytesReceived.get() / (1024 * 1024)} MB")
                }
            } catch (e: Exception) {
                log("UDP 監聽失敗: ${e.message}")
                delay(1000)
                if (isActive) {
                    startUdpReceiver()
                }
            }
        }
    }

    private fun startPacketProcessor(): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            log("啟動數據包處理協程")
            var gotUpper = false
            var gotLower = false
            var lineCount = 0
            pointsBuffer.clear()
            framePointsBuffer.clear()
            framePointsBuffer.ensureCapacity(LidarConstants.TOTAL_POINTS_PER_LINE * LidarConstants.LINES_PER_FRAME)
            while (isActive) {
                val packet = packetQueue.poll()
                if (packet == null) {
                    delay(10)
                    continue
                }
                pointsBuffer.clear()
                val isUpperPacket = parseUdpPacket(packet, pointsBuffer, echoMode)
                if (pointsBuffer.isEmpty()) continue
                if (isUpperPacket) {
                    gotUpper = true
                } else {
                    gotLower = true
                }
                if (gotUpper && gotLower) {
                    framePointsBuffer.addAll(pointsBuffer)
                    lineCount++
                    gotUpper = false
                    gotLower = false
                    if (lineCount >= LidarConstants.LINES_PER_FRAME) {
                        log("Frame completed with $lineCount lines, ${framePointsBuffer.size} points")
                        synchronized(frameLock) {
                            sendPointsToRenderer()
                            lineCount = 0
                            // 不清空 framePointsBuffer，讓它保留直到下一幀到達
                        }
                    }
                }
            }
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
        if (!checkPacketHeader(data)) return false
        val returnSeq = data[LidarConstants.DATA_START_OFFSET + 1].toUByte()
        val packetType = (returnSeq.toInt() and 0xF0).toByte()
        val echoNum = (returnSeq.toInt() and 0x0F).toByte()
        when (echoMode) {
            EchoMode.ECHO_MODE_1ST -> if (echoNum != LidarConstants.ECHO_1ST) return false
            EchoMode.ECHO_MODE_2ND -> if (echoNum != LidarConstants.ECHO_2ND) return false
            else -> {}
        }
        if (packetType != LidarConstants.PACKET_UPPER && packetType != LidarConstants.PACKET_LOWER) return false
        val isUpperPacket = packetType == LidarConstants.PACKET_UPPER
        val azimuthRaw = ((data[LidarConstants.DATA_START_OFFSET + 3].toInt() and 0xFF) shl 8) or
                (data[LidarConstants.DATA_START_OFFSET + 2].toInt() and 0xFF)
        try {
            val lookupTable = LidarConstants.LOOKUP_TABLE
            val index = azimuthRaw % lookupTable.size
            val azimuth = lookupTable[index] / 100.0f
            val radAzimuth = Math.toRadians(azimuth.toDouble()).toFloat()
            val cosAzimuth = cos(radAzimuth)
            val sinAzimuth = sin(radAzimuth)
            val elevationStart = if (isUpperPacket) LidarConstants.ELEVATION_START_UPPER else LidarConstants.ELEVATION_START_LOWER
            val expectedPoints = when (echoMode) {
                EchoMode.ECHO_MODE_ALL -> LidarConstants.POINTS_PER_PACKET
                EchoMode.ECHO_MODE_1ST, EchoMode.ECHO_MODE_2ND -> {
                    if ((echoMode == EchoMode.ECHO_MODE_1ST && echoNum == LidarConstants.ECHO_1ST) ||
                        (echoMode == EchoMode.ECHO_MODE_2ND && echoNum == LidarConstants.ECHO_2ND)) {
                        LidarConstants.POINTS_PER_PACKET
                    } else 0
                }
            }
            if (expectedPoints == 0) return false
            val pointStart = LidarConstants.DATA_START_OFFSET + 4
            points.ensureCapacity(points.size + expectedPoints)
            for (i in 0 until LidarConstants.POINTS_PER_PACKET) {
                val dataOffset = pointStart + (i * 3)
                val intensity = (data[dataOffset].toInt() and 0xFF).toFloat()
                val radius = ((data[dataOffset + 2].toInt() and 0xFF) shl 8) or
                        (data[dataOffset + 1].toInt() and 0xFF)
                if (intensity > 255) continue
                val elevation = elevationStart + (i * LidarConstants.ELEVATION_STEP)
                val radElevation = Math.toRadians(elevation.toDouble()).toFloat()
                val cosElevation = cos(radElevation)
                val sinElevation = sin(radElevation)
                val point = LidarPoint(
                    x = radius * cosElevation * sinAzimuth,
                    y = radius * cosElevation * cosAzimuth,
                    z = radius * sinElevation,
                    intensity = intensity,
                    azimuth = azimuth,
                    elevation = elevation,
                    valid = true,
                    echoNum = echoNum
                )
                points.add(point)
            }
            return isUpperPacket
        } catch (e: UninitializedPropertyAccessException) {
            log("警告：查找表未初始化，嘗試重新加載")
            context?.let { loadLookupTableFromResource(it) }
            return false
        } catch (e: Exception) {
            log("處理數據包時出錯: ${e.message}")
            return false
        }
    }

    fun getLatestFramePoints(): ArrayList<LidarPoint> {
        synchronized(frameLock) {
            return ArrayList(framePointsBuffer)
        }
    }

    private fun sendPointsToRenderer() {
        val rendererCopy = renderer
        val surfaceViewCopy = glSurfaceView
        if (rendererCopy != null && surfaceViewCopy != null && framePointsBuffer.isNotEmpty()) {
            val pointArray = FloatArray(framePointsBuffer.size * 7).apply {
                framePointsBuffer.forEachIndexed { index, point ->
                    val offset = index * 7
                    this[offset] = point.x
                    this[offset + 1] = point.y
                    this[offset + 2] = point.z
                    this[offset + 3] = point.intensity
                    this[offset + 4] = 0f // normal x
                    this[offset + 5] = 0f // normal y
                    this[offset + 6] = 0f // normal z
                }
            }
            surfaceViewCopy.queueEvent {
                rendererCopy.updatePoints(pointArray)
                log("Rendered frame with ${framePointsBuffer.size} points")
            }
            // 清空 framePointsBuffer，以便下一幀重新填充
            synchronized(frameLock) {
                framePointsBuffer.clear()
            }
        }
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