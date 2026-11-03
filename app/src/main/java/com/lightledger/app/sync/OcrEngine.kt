package com.lightledger.app.sync

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 本地中文 OCR。模型随 APK 打包（ML Kit bundled），识别在设备上完成，
 * 不上传任何图片，符合「不联网」的定位。
 */
class OcrEngine {

    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = ArrayList<OcrLine>(result.textBlocks.size * 6)
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val box = line.boundingBox
                        lines += OcrLine(
                            text = line.text,
                            top = box?.top?.toFloat() ?: 0f,
                            left = box?.left?.toFloat() ?: 0f,
                            right = box?.right?.toFloat() ?: 0f,
                            bottom = box?.bottom?.toFloat() ?: 0f,
                        )
                    }
                }
                if (cont.isActive) cont.resume(lines)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }
}
