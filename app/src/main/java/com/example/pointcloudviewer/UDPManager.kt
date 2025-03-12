package com.example.pointcloudviewer

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("StaticFieldLeak")
object UDPManager {
    private const val TAG = "UDPManager"
    private const val UDP_PORT = 12346 // 本地監聽埠
    private const val SOCKET_BUFFER_SIZE = 64 * 1024 * 1024 // 64MB
    private const val PACKET_BUFFER_SIZE = 65535 // 最大 UDP 包大小

    private var udpJob: Job? = null
    private var processingJob: Job? = null
    private val packetQueue = ConcurrentLinkedQueue<ByteArray>()
    private val totalBytesReceived = AtomicLong(0)
    private val totalPacketsReceived = AtomicLong(0)
    private val bytesInLastSecond = AtomicLong(0)

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: PointCloudRenderer? = null
    private var onDataRateUpdate: ((Double) -> Unit)? = null

    // 初始化並啟動監聽
    fun initialize(glSurfaceView: GLSurfaceView, renderer: PointCloudRenderer, onDataRateUpdate: (Double) -> Unit) {
        this.glSurfaceView = glSurfaceView
        this.renderer = renderer
        this.onDataRateUpdate = onDataRateUpdate

        if (udpJob == null || processingJob == null) {
            log("啟動 UDP 監聽")
            udpJob = startUdpReceiver()
            processingJob = startPacketProcessor()
        }
    }

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

                    while (true) { // 永遠運行
                        delay(1000)

                        val currentTime = System.nanoTime()
                        val elapsedSeconds = (currentTime - lastUpdateTime) / 1_000_000_000.0
                        val bytesReceived = bytesInLastSecond.getAndSet(0)

                        val speedMBps = bytesReceived / (1024.0 * 1024.0) / elapsedSeconds
                        onDataRateUpdate?.invoke(speedMBps)

                        lastUpdateTime = currentTime
                    }
                }

                try {
                    while (true) { // 永遠運行
                        val buffer = ByteArray(PACKET_BUFFER_SIZE)
                        val receivePacket = DatagramPacket(buffer, buffer.size)

                        try {
                            socket.receive(receivePacket)

                            val dataLength = receivePacket.length
                            val actualData = buffer.copyOfRange(0, dataLength)

                            totalBytesReceived.addAndGet(dataLength.toLong())
                            bytesInLastSecond.addAndGet(dataLength.toLong())
                            totalPacketsReceived.incrementAndGet()

                            packetQueue.offer(actualData)

                            if (packetQueue.size > 10000) {
                                log("警告: 隊列過大 (${packetQueue.size})，丟棄舊數據")
                                while (packetQueue.size > 5000) {
                                    packetQueue.poll()
                                }
                            }
                        } catch (e: Exception) {
                            log("接收數據時出錯: ${e.message}")
                            delay(1000) // 出錯時等待後重試
                        }
                    }
                } finally {
                    speedMonitorJob.cancel()
                    socket.close()
                    log("關閉 UDP 監聽，共接收 ${totalPacketsReceived.get()} 個封包，總計 ${totalBytesReceived.get() / (1024 * 1024)} MB")
                }
            } catch (e: Exception) {
                log("UDP 監聽失敗: ${e.message}")
                delay(1000) // 出錯時等待後重試
                startUdpReceiver() // 重啟監聽
            }
        }
    }

    private fun startPacketProcessor(): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            log("啟動數據包處理協程")

            try {
                while (true) { // 永遠運行
                    val packetsToProcess = mutableListOf<ByteArray>()

                    for (i in 0 until 100) {
                        val packet = packetQueue.poll() ?: break
                        packetsToProcess.add(packet)
                    }

                    if (packetsToProcess.isNotEmpty()) {
                        packetsToProcess.forEach { packet ->
                            val points = parseUDPData(packet)
                            glSurfaceView?.queueEvent { renderer?.updatePoints(points) }
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                log("數據處理出錯: ${e.message}")
                delay(1000) // 出錯時等待後重試
                startPacketProcessor() // 重啟處理
            }
        }
    }

    private fun parseUDPData(data: ByteArray): FloatArray {
        try {
            val dataString = String(data)
            val values = dataString.split(",").map { it.toFloat() }
            return floatArrayOf(
                values[0], values[1], values[2],  // x, y, z
                values[3],                        // intensity
                0f, 0f, 0f                       // normal
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 UDP 資料失敗: ${e.message}")
            return floatArrayOf()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}