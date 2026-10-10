package com.engcamera.scan

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 条码分析器（计划书 5.4）：
 * - 每 300ms 最多分析一帧（节流）
 * - 连续 2 帧相同内容才认为有效
 * - 冷却时间由上层（ViewModel）处理
 */
@OptIn(ExperimentalGetImage::class)
class BarcodeAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build(),
    )

    private var lastAnalyzeAt = 0L
    private var lastContent: String? = null
    private var repeatCount = 0

    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzeAt < THROTTLE_MS) {
            imageProxy.close()
            return
        }
        lastAnalyzeAt = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstNotNullOfOrNull { it.rawValue }
                if (text == null) {
                    lastContent = null
                    repeatCount = 0
                } else if (text == lastContent) {
                    repeatCount += 1
                    if (repeatCount >= STABLE_FRAMES) onResult(text)
                } else {
                    lastContent = text
                    repeatCount = 1
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun release() {
        scanner.close()
    }

    private companion object {
        const val THROTTLE_MS = 300L
        const val STABLE_FRAMES = 2
    }
}
