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
) : ImageAnalysis.Analyzer {
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

        // Convert ImageProxy (camera frame) into ML Kit InputImage.
        // rotationDegrees is required so FaceMesh points are in the correct orientation.
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)


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

                val leftEyeRect = rectFrom(leftEyePoints, pad = 0.35f)?: run {
                            _roisOnPreview.value = emptyList()
                            return@addOnSuccessListener
                        }

                val rightEyeRect = rectFrom(rightEyePoints, pad = 0.35f)?: run {
                    _roisOnPreview.value = emptyList()
                    return@addOnSuccessListener
                }

                val mouthPts = buildList {
                    addAll(faceMesh.getPoints(FaceMesh.UPPER_LIP_TOP))
                    addAll(faceMesh.getPoints(FaceMesh.UPPER_LIP_BOTTOM))
                    addAll(faceMesh.getPoints(FaceMesh.LOWER_LIP_TOP))
                    addAll(faceMesh.getPoints(FaceMesh.LOWER_LIP_BOTTOM))
                }
                val mouthRect = rectFrom(mouthPts, pad = 0.50f)?: run {
                    _roisOnPreview.value = emptyList()
                    return@addOnSuccessListener
                }


                val convLeftEyeRect = mapRect(leftEyeRect, coordinateTransform)
                val convRightEyeRect = mapRect(rightEyeRect, coordinateTransform)
                val convMouthRect = mapRect(mouthRect, coordinateTransform)

                // Map ROI from ImageProxy coords -> PreviewView coords for drawing.
                _roisOnPreview.value = listOf(convLeftEyeRect, convRightEyeRect, convMouthRect)

            }
            .addOnFailureListener { e ->
                Log.e("CameraAnalyzer", "FaceMesh error", e)
                _roisOnPreview.value = emptyList()
            }
            .addOnCompleteListener {
                // Always close ImageProxy, otherwise CameraX pipeline stalls.
                // Always release isProcessing in complete callback.
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
        return RectF(
            (minX - w * pad),
            (minY - h * pad),
            (maxX + w * pad),
            (maxY + h * pad),
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