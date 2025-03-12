package com.example.pointcloudviewer

import android.opengl.Matrix

class CameraController {
    private var scaleFactor = 1.0f
    private var rotateX = 0.0f
    private var rotateY = 0.0f
    private var translateX = 0.0f
    private var translateY = 0.0f

    private val modelMatrix = FloatArray(16)

    init {
        Matrix.setIdentityM(modelMatrix, 0)
    }

    fun rotate(dx: Float, dy: Float) {
        rotateX += dy / 5f
        rotateY += dx / 5f
        updateModelMatrix()
    }

    fun scale(factor: Float) {
        scaleFactor *= factor
        scaleFactor = scaleFactor.coerceIn(0.1f, 5.0f)
        updateModelMatrix()
    }

    fun translate(dx: Float, dy: Float) {
        translateX += dx * 0.01f / scaleFactor
        translateY += dy * 0.01f / scaleFactor
        translateX = translateX.coerceIn(-5f, 5f)
        translateY = translateY.coerceIn(-5f, 5f)
        updateModelMatrix()
    }

    fun resetView() {
        scaleFactor = 1.0f
        rotateX = 0f
        rotateY = 0f
        translateX = 0f
        translateY = 0f
        updateModelMatrix()
    }

    fun getModelMatrix(): FloatArray = modelMatrix

    private fun updateModelMatrix() {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, translateX, translateY, 0f)
        Matrix.rotateM(modelMatrix, 0, rotateX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotateY, 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, scaleFactor, scaleFactor, scaleFactor)
    }
}