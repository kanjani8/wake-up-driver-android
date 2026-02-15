package com.example.drowseydriver1

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
import java.util.concurrent.atomic.AtomicBoolean


@TransformExperimental
class CameraAnalyzer(
    private val previewView: PreviewView
) : ImageAnalysis.Analyzer{
    private val faceMeshDetector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder().setUseCase(FaceMeshDetectorOptions.FACE_MESH).build()
    )

    private val _roisOnPreview = MutableStateFlow<List<RectF>>(emptyList())
    val roisOnPreview: StateFlow<List<RectF>> = _roisOnPreview

    // ML Kit FaceMesh runs asynchronously.
    // Guard against overlapping frames: if we start processing a frame, drop the next ones
    // until callbacks complete. This avoids backlog + stale UI updates.
    private val isProcessing = AtomicBoolean(false)
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

    // Schedule gate
    private var lastLogMs = 0L

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

        // Processing time checker
        val t0 = android.os.SystemClock.elapsedRealtime()

        // Pass image to an ML Kit Vision API
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
                    // classifier.classifyEyes(leftEye, rightEye)

                    //Call DrowsiessTracker
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