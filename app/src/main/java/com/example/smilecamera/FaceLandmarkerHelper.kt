package com.example.smilecamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.hypot

data class SmileResult(val isSmiling: Boolean, val mouthWidthRatio: Double)

class FaceLandmarkerHelper(private val context: Context) {
    private val TAG = "FaceLandmarkerHelper"
    private var faceLandmarker: FaceLandmarker? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun initialize(onDone: (Boolean) -> Unit) {
        scope.launch {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()

                faceLandmarker = FaceLandmarker.createFromOptions(context, options)
                Log.d(TAG, "FaceLandmarker 초기화 성공")
                withContext(Dispatchers.Main) { onDone(true) }
            } catch (e: Exception) {
                Log.e(TAG, "FaceLandmarker 초기화 실패: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    fun analyze(imageProxy: ImageProxy, callback: (SmileResult) -> Unit) {
        scope.launch {
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap == null) {
                    Log.e(TAG, "Bitmap 변환 실패")
                    withContext(Dispatchers.Main) {
                        callback(SmileResult(false, 0.0))
                    }
                    return@launch
                }

                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                val result = faceLandmarker?.detect(mpImage)

                val smileResult = if (result?.faceLandmarks()?.isNotEmpty() == true) {
                    val landmarks = result.faceLandmarks()[0]
                    Log.d(TAG, "랜드마크 개수: ${landmarks.size}")

                    if (landmarks.size >= 468) { // MediaPipe Face Mesh는 468개 랜드마크
                        // 입 끝점들 (MediaPipe Face Mesh 기준)
                        val leftMouthCorner = landmarks[61]   // 왼쪽 입꼬리
                        val rightMouthCorner = landmarks[291] // 오른쪽 입꼬리
                        val upperLip = landmarks[13]          // 상순 중앙
                        val lowerLip = landmarks[14]          // 하순 중앙

                        // 얼굴 너비 기준점들
                        val leftCheek = landmarks[234]
                        val rightCheek = landmarks[454]

                        val mouthWidth = distance(
                            leftMouthCorner.x(), leftMouthCorner.y(),
                            rightMouthCorner.x(), rightMouthCorner.y()
                        )
                        val mouthHeight = distance(
                            upperLip.x(), upperLip.y(),
                            lowerLip.x(), lowerLip.y()
                        )
                        val faceWidth = distance(
                            leftCheek.x(), leftCheek.y(),
                            rightCheek.x(), rightCheek.y()
                        )

                        val widthRatio = mouthWidth / faceWidth
                        val aspectRatio = mouthWidth / mouthHeight

                        // 미소 판단 기준 개선
                        val isSmiling = widthRatio > 0.045 && aspectRatio > 3.0

                        Log.d(TAG, "입 너비 비율: $widthRatio, 종횡비: $aspectRatio, 미소: $isSmiling")
                        SmileResult(isSmiling, widthRatio)
                    } else {
                        Log.w(TAG, "랜드마크 수가 부족함: ${landmarks.size}")
                        SmileResult(false, 0.0)
                    }
                } else {
                    Log.d(TAG, "얼굴이 감지되지 않음")
                    SmileResult(false, 0.0)
                }

                withContext(Dispatchers.Main) {
                    callback(smileResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyze error: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(SmileResult(false, 0.0))
                }
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Double {
        val dx = (x1 - x2).toDouble()
        val dy = (y1 - y2).toDouble()
        return hypot(dx, dy)
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null

            when (image.format) {
                ImageFormat.YUV_420_888 -> {
                    yuvToBitmap(image)
                }
                else -> {
                    Log.w(TAG, "지원되지 않는 이미지 형식: ${image.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap 변환 오류: ${e.message}")
            null
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y 평면 복사
        yBuffer.get(nv21, 0, ySize)

        // UV 평면을 NV21 형식으로 변환
        val uvPixelStride = planes[1].pixelStride
        val uvRowStride = planes[1].rowStride
        val width = image.width
        val height = image.height

        if (uvPixelStride == 1) {
            // 연속된 UV 데이터인 경우
            uBuffer.get(nv21, ySize, uSize)
            vBuffer.get(nv21, ySize + uSize, vSize)
        } else {
            // 인터리브된 UV 데이터 처리
            val uvHeight = height / 2
            val uvWidth = width / 2
            var uvIndex = ySize

            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uIndex = row * uvRowStride + col * uvPixelStride
                    val vIndex = row * planes[2].rowStride + col * planes[2].pixelStride

                    if (uIndex < uSize && vIndex < vSize) {
                        nv21[uvIndex++] = vBuffer.get(vIndex)  // V가 먼저 (NV21)
                        nv21[uvIndex++] = uBuffer.get(uIndex)  // U가 다음
                    }
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        val success = yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)

        if (!success) {
            Log.e(TAG, "JPEG 압축 실패")
            throw RuntimeException("JPEG 압축 실패")
        }

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw RuntimeException("Bitmap 디코딩 실패")
    }

    fun close() {
        scope.cancel()
        faceLandmarker?.close()
        faceLandmarker = null
        Log.d(TAG, "FaceLandmarkerHelper 종료됨")
    }
}