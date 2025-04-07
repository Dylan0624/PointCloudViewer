package com.example.pointcloudviewer

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraPreview(context: Context, private val containerView: FrameLayout) : SurfaceView(context), SurfaceHolder.Callback {

    private val TAG = "CameraPreview"
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private val cameraOpenCloseLock = Semaphore(1)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraId: String? = null

    init {
        holder.addCallback(this)
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupCamera()
    }

    private fun setupCamera() {
        try {
            for (id in cameraManager!!.cameraIdList) {
                val characteristics = cameraManager!!.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // 使用後置鏡頭
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    break
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera setup error: ${e.message}")
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera device error: $error")
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surface = holder.surface

            // 獲取相機特性
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)

            // 獲取相機支持的預覽尺寸
            val streamConfigurationMap = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // 獲取適合的預覽尺寸
            val previewSizes = streamConfigurationMap?.getOutputSizes(SurfaceHolder::class.java)
            var optimalSize: android.util.Size? = null

            // 獲取容器的尺寸
            val displayHeight = containerView.height
            val displayWidth = containerView.width

            // 找到最接近容器高度且保持相機原始比例的尺寸
            if (previewSizes != null) {
                // 按高度排序
                val sortedSizes = previewSizes.sortedByDescending { it.height }

                // 找到第一個高度不超過容器高度的尺寸
                optimalSize = sortedSizes.firstOrNull {
                    it.height <= displayHeight
                }

                // 如果沒有找到合適的尺寸，選擇最小的一個
                if (optimalSize == null) {
                    optimalSize = sortedSizes.last()
                }

                Log.d(TAG, "Selected preview size: ${optimalSize?.width}x${optimalSize?.height}")
            }

            // 不直接設置固定尺寸，而是讓 SurfaceView 按原始比例顯示
            if (optimalSize != null) {
                // 計算縮放比例，使高度填滿
                val scale = displayHeight.toFloat() / optimalSize.height.toFloat()
                val targetWidth = (optimalSize.width * scale).toInt()

                // 在主線程上設置預覽大小
                val finalWidth = targetWidth
                val finalHeight = displayHeight
                val context = context
                (context as? android.app.Activity)?.runOnUiThread {
                    holder.setFixedSize(finalWidth, finalHeight)
                }
            }

            captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)

            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        cameraCaptureSession = session
                        try {
                            captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

                            // 如果相機支持廣角模式，設置最廣的焦距
                            val focalLengths = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            if (focalLengths != null && focalLengths.isNotEmpty()) {
                                val minFocalLength = focalLengths.min()
                                captureRequestBuilder?.set(CaptureRequest.LENS_FOCAL_LENGTH, minFocalLength)
                            }

                            cameraCaptureSession?.setRepeatingRequest(
                                captureRequestBuilder!!.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error setting repeating request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creating camera preview session: ${e.message}")
        }
    }

    fun startCamera() {
        startBackgroundThread()

        if (holder.surface.isValid) {
            openCamera()
        }
    }

    fun stopCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession?.close()
            cameraCaptureSession = null

            cameraDevice?.close()
            cameraDevice = null

            stopBackgroundThread()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun openCamera() {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            cameraId?.let {
                try {
                    cameraManager?.openCamera(it, stateCallback, backgroundHandler)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Camera permission not granted: ${e.message}")
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Error opening camera: ${e.message}")
                }
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error acquiring camera lock: ${e.message}")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 預覽大小改變時可能需要處理
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopCamera()
    }
}