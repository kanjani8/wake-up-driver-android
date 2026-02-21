package com.example.drowseydriver1

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean


@TransformExperimental
class CameraAnalyzer(
    private val previewView: PreviewView
) : ImageAnalysis.Analyzer{
    private val faceMeshDetector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH).build()
    )

    private val _roisOnPreview = MutableStateFlow<List<RectF>>(emptyList())
    val roisOnPreview: StateFlow<List<RectF>> = _roisOnPreview

    // 3) State for Debug   - For test cropped bitmap
    private val _debugBitmap = MutableStateFlow<Bitmap?>(null)
    val debugBitmap: StateFlow<Bitmap?> = _debugBitmap.asStateFlow()
    private var debugCounter = 0

    // ML Kit FaceMesh runs asynchronously
    // if we start processing a frame, drop the next ones
    // until callbacks complete. This avoids backlog + stale UI updates.
    private val isProcessing = AtomicBoolean(false)
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

    private var lastMeshMs = 0L
    private var lastEyeMs = 0L
    private var lastMouthMs = 0L

    private val meshIntervalMs = 100L
    private val eyeIntervalMs = 100L   // 0.1s
    private val mouthIntervalMs = 200L // 0.2s


    @OptIn(TransformExperimental::class)
    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if(mediaImage == null) {
            imageProxy.close()
            return
        }
        // Avoid Asynchronous Duplicate Processing
        // false -> true  / true -> discard
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()

        val doMesh = (now - lastMeshMs) >= meshIntervalMs
        if (!doMesh) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }
        lastMeshMs = now

        //Frame rate checker
//        val dt = if (lastLogMs == 0L) 0 else now - lastLogMs
//        lastLogMs = now
//        Log.d("AnalyzerRate", "analyze dt=${dt}ms rot=${imageProxy.imageInfo.rotationDegrees}")


        // Convert ImageProxy (camera frame) into ML Kit InputImage.
        // rotationDegrees is required so FaceMesh points are in the correct orientation.
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        val t0 = android.os.SystemClock.elapsedRealtime()

        //  (D) Run Face mesh asynchronous   - Pass image to an ML Kit Vision API
        faceMeshDetector.process(image)
            .addOnSuccessListener { faceMeshs ->
                val faceMesh = faceMeshs.firstOrNull()

                val imageTransform: OutputTransform? = transformFactory.getOutputTransform(imageProxy)
                val viewTransform: OutputTransform? = previewView.outputTransform


                if (faceMesh == null ||
                    previewView.width == 0 || previewView.height == 0 ||
                    imageTransform == null || viewTransform == null
                ) {
                    _roisOnPreview.value = emptyList()
                    return@addOnSuccessListener
                }

                // CameraX coordinate mapping:
                // FaceMesh points are in ImageProxy coordinates,
                // but our overlay Canvas draws in PreviewView coordinates.
                // CoordinateTransform maps between the two.
                val coordinateTransform = CoordinateTransform(imageTransform, viewTransform)


                // ROI rectangles are computed from FaceMesh contour points (LEFT_EYE / RIGHT_EYE / lips).
                // These rects are still in ImageProxy coordinates at this moment.
                val leftEyePoints = faceMesh.getPoints(FaceMesh.LEFT_EYE)
                val rightEyePoints = faceMesh.getPoints(FaceMesh.RIGHT_EYE)

                // 1.5f Padding because it is similar range with the training dataset
                val leftEyeRect = rectFrom(leftEyePoints, pad = 1.5f)?: run {
                            _roisOnPreview.value = emptyList()
                            return@addOnSuccessListener
                        }

                val rightEyeRect = rectFrom(rightEyePoints, pad = 1.5f)?: run {
                    _roisOnPreview.value = emptyList()
                    return@addOnSuccessListener
                }

                val mouthPts = buildList {
                    addAll(faceMesh.getPoints(FaceMesh.UPPER_LIP_TOP))
                    addAll(faceMesh.getPoints(FaceMesh.UPPER_LIP_BOTTOM))
                    addAll(faceMesh.getPoints(FaceMesh.LOWER_LIP_TOP))
                    addAll(faceMesh.getPoints(FaceMesh.LOWER_LIP_BOTTOM))
                }
                val mouthRect = rectFrom(mouthPts, pad = 1.8f)?: run {
                    _roisOnPreview.value = emptyList()
                    return@addOnSuccessListener
                }
                //convert to draw box in previewView coords
                val convLeftEyeRect = mapRect(leftEyeRect, coordinateTransform)
                val convRightEyeRect = mapRect(rightEyeRect, coordinateTransform)
                val convMouthRect = mapRect(mouthRect, coordinateTransform)

                // Map ROI from ImageProxy coords -> PreviewView coords for drawing.
                _roisOnPreview.value = listOf(convLeftEyeRect, convRightEyeRect, convMouthRect)

                val now2 = android.os.SystemClock.elapsedRealtime()
                val doEye = (now2 - lastEyeMs) >= eyeIntervalMs
                val doMouth = (now2 - lastMouthMs) >= mouthIntervalMs

                if (!doEye && !doMouth) return@addOnSuccessListener

                if (doEye) {
                    lastEyeMs = now2
                    val leftEye = roiYuv420ToRgbChwUprightROI(imageProxy, leftEyeRect, outSize = 128)
                    val rightEye = roiYuv420ToRgbChwUprightROI(imageProxy, rightEyeRect, outSize = 128)

                    //Debug: Create cropped bitmap preview once in ten (super slow)
                    debugCounter++
                    if (debugCounter % 10 == 0) {
                        leftEye?.let { _debugBitmap.value = chwNormalizedToBitmap(it, outSize = 128) }
                    }

                    //TODO:
                    // classifier.classifyEyes(leftEye, rightEye)

                    //TODO:
                    //Call DrowsinessTracker
                }

                if (doMouth) {
                    lastMouthMs = now2
                    val mouth = roiYuv420ToRgbChwUprightROI(imageProxy, mouthRect, outSize = 160)
                    // classifier.classifyMouth(mouth)
                    //Call DrowsiessTracker
                }

            }
            .addOnFailureListener { e ->
                Log.e("CameraAnalyzer", "FaceMesh error", e)
                _roisOnPreview.value = emptyList()
            }
            .addOnCompleteListener {
                // Always close ImageProxy, otherwise CameraX pipeline stalls.
                // Always release isProcessing in complete callback.

                val t1 = android.os.SystemClock.elapsedRealtime()
                Log.d("MeshTime", "faceMesh took ${t1 - t0}ms") // around 60ms

                isProcessing.set(false)
                imageProxy.close()
            }
    }

    private fun rectFrom(points: List<FaceMeshPoint>, pad: Float): RectF? {
        if (points.isEmpty()) return null

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (p in points) {
            val x = p.position.x
            val y = p.position.y
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val w = maxX - minX
        val h = maxY - minY

        val Left = minX
        val Top = minY
        val Right = maxX
        val Bottom = maxY

        val cx = (Left + Right) * 0.5f
        val cy = (Top + Bottom) * 0.5f

        val size = maxOf(w, h) * (pad * 1.0f)
        val half = size * 0.5f

        return RectF(
            cx - half,
            cy - half,
            cx + half,
            cy + half
        )
    }

    @OptIn(TransformExperimental::class)
    private fun mapRect(bbox: RectF, ct: CoordinateTransform): RectF {
        // 4 corners
        val pts = floatArrayOf(
            bbox.left,  bbox.top,
            bbox.right, bbox.top,
            bbox.right, bbox.bottom,
            bbox.left,  bbox.bottom
        )
        ct.mapPoints(pts)

        val left = minOf(pts[0], pts[2], pts[4], pts[6])
        val top = minOf(pts[1], pts[3], pts[5], pts[7])
        val right = maxOf(pts[0], pts[2], pts[4], pts[6])
        val bottom = maxOf(pts[1], pts[3], pts[5], pts[7])

        return RectF(left, top, right, bottom)
    }
}

/* -------------------------------------------------------
   Below: Debug utilities
   ------------------------------------------------------- */

// Reverse normalization From Frame Preprocessor adnn change it to Bitmap to check at the preview
fun chwNormalizedToBitmap(
chw: FloatArray,
outSize: Int,
mean: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
std:  FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
): Bitmap {
    val hw = outSize * outSize
    require(chw.size >= 3 * hw)

    val pixels = IntArray(hw)

    fun denorm(v: Float, c: Int): Float = v * std[c] + mean[c] // back to 0..1

    for (i in 0 until hw) {
        val r01 = denorm(chw[i], 0).coerceIn(0f, 1f)
        val g01 = denorm(chw[hw + i], 1).coerceIn(0f, 1f)
        val b01 = denorm(chw[2 * hw + i], 2).coerceIn(0f, 1f)

        val r = (r01 * 255f).toInt().coerceIn(0, 255)
        val g = (g01 * 255f).toInt().coerceIn(0, 255)
        val b = (b01 * 255f).toInt().coerceIn(0, 255)

        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    return Bitmap.createBitmap(pixels, outSize, outSize, Bitmap.Config.ARGB_8888)
}