package com.example.drowseydriver1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pytorch.executorch.Module
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

@TransformExperimental
class CameraAnalyzer(
    private val context: Context,
    private val previewView: PreviewView
) : ImageAnalysis.Analyzer{

    // 1) ML Kit FaceMesh detector
    private val faceMeshDetector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH).build()
    )

    // 2) State(상태) for Preview
    private val _roisOnPreview = MutableStateFlow<List<RectF>>(emptyList())
    val roisOnPreview: StateFlow<List<RectF>> = _roisOnPreview.asStateFlow()


    // 3) State for Debug   - For test cropped bitmap
    private val _debugBitmap = MutableStateFlow<Bitmap?>(null)
    val debugBitmap: StateFlow<Bitmap?> = _debugBitmap.asStateFlow()
    private var debugCounter = 0


    // 4) Guard against overlapping frames:

    // ML Kit FaceMesh runs asynchronously
    // if we start processing a frame, drop the next ones
    // until callbacks complete. This avoids backlog + stale UI updates.
    private val isProcessing = AtomicBoolean(false)

    // 5) ImageProxy -> PreviewView Transform
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

    // 6) 실행 주기 제한(성능용)
    private var lastMeshMs = 0L
    private var lastEyeMs = 0L
    private var lastMouthMs = 0L

    private val meshIntervalMs = 100L
    private val eyeIntervalMs = 100L   // 0.1s
    private val mouthIntervalMs = 200L // 0.2s

    // 7) Classification Labels
    interface FaceState {
        val isWarningNeeded: Boolean
    }

    enum class EyeState(override val isWarningNeeded: Boolean) : FaceState {
        OPEN(false),
        CLOSED(true)
    }

    enum class MouthState(override val isWarningNeeded: Boolean) : FaceState {
        NO_YAWN(false),
        YAWN(true)
    }

    // 8) Classification Models
    private val eyeModelPath: String by lazy {
        getAssetFilePath(context, "eye_model.pte")
    }

    private val eyeModel = Module.load(eyeModelPath)

    private val mouthModelPath: String by lazy {
        getAssetFilePath(context, "mouth_model.pte")
    }
    private val mouthModel = Module.load(mouthModelPath)

    // -------------------------
    // Main entry
    // -------------------------

    @OptIn(TransformExperimental::class)
    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if(mediaImage == null) {
            imageProxy.close()
            return
        }
        // (A) Avoid Asynchronous Duplicate Processing
        // false -> true  / true -> discard
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()

        // (B) Run Mesh only once per interval
        val doMesh = (now - lastMeshMs) >= meshIntervalMs
        if (!doMesh) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }
        lastMeshMs = now


        //(C)  Convert ImageProxy (camera frame) into ML Kit InputImage.
        // rotationDegrees is required so FaceMesh points are in the correct orientation.
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        val t0 = SystemClock.elapsedRealtime()

        //  (D) Run Face mesh asynchronous   - Pass image to an ML Kit Vision API
        faceMeshDetector.process(image)
            .addOnSuccessListener { faceMeshes ->
                val faceMesh = faceMeshes.firstOrNull()


                // ----------D-0 Basic validity check-----------------
                val imageTransform: OutputTransform? = transformFactory.getOutputTransform(imageProxy)
                val viewTransform: OutputTransform? = previewView.outputTransform


                if (faceMesh == null ||
                    previewView.width == 0 || previewView.height == 0 ||
                    imageTransform == null || viewTransform == null
                ) {
                    isProcessing.set(false)
                    _roisOnPreview.value = emptyList()
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ---------- (D-1) Preparing for coordinate transformation ----------

                // CameraX coordinate mapping:
                // FaceMesh points are in ImageProxy coordinates,
                // but our overlay Canvas draws in PreviewView coordinates.
                // CoordinateTransform maps between the two.
                val coordinateTransform = CoordinateTransform(imageTransform, viewTransform)


                // ---------- (D-2) Calculate ROI (ImageProxy Coordinates) ----------

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

                // ---------- (D-3) Convert into PreviewView Coordinates----------
                //convert to draw box in previewView coords
                val convLeftEyeRect = mapRect(leftEyeRect, coordinateTransform)
                val convRightEyeRect = mapRect(rightEyeRect, coordinateTransform)
                val convMouthRect = mapRect(mouthRect, coordinateTransform)

                // Map ROI from ImageProxy coords -> PreviewView coords for drawing.
                _roisOnPreview.value = listOf(convLeftEyeRect, convRightEyeRect, convMouthRect)


                // ---------- (D-4) Generate model inputs (rate-limited) + optional debug preview ----------
                val now2 = SystemClock.elapsedRealtime()
                val doEye = (now2 - lastEyeMs) >= eyeIntervalMs
                val doMouth = (now2 - lastMouthMs) >= mouthIntervalMs

                if (!doEye && !doMouth){
                    isProcessing.set(false)
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                CoroutineScope(Dispatchers.Default).launch { // background processing
                    try {
                        if (doEye) {
                            lastEyeMs = now2

                            // Generating the actual model input tensor
                            // Note: roiYuv420ToRgbChwUprightROI may return null if ImageProxy.image is null.
                            val leftEye = roiYuv420ToRgbChwUprightROI(imageProxy, leftEyeRect, outSize = 128) ?: return@launch
                             val rightEye = roiYuv420ToRgbChwUprightROI(imageProxy, rightEyeRect, outSize = 128) ?: return@launch
                            //Debug: Convert the *actual model input tensor* back to a Bitmap.
                            // This is intentionally slow; run once every N frames.
                            debugCounter++
                            if (debugCounter % 10 == 0) {
                                leftEye?.let { _debugBitmap.value = chwNormalizedToBitmap(it, outSize = 128) }
                            }

                            // Classify
                            val eyeResult1 =
                            executorchBinaryClassifier(
                                eyeModel, EyeState.entries, 128, leftEye
                            )
                            Log.d("faceResult", "eyeResult1:  ${eyeResult1}")
                            if(eyeResult1 == EyeState.CLOSED){
                                val eyeResult2 =
                                    executorchBinaryClassifier(
                                        eyeModel, EyeState.entries, 128, rightEye
                                    )
                            }



                            //TODO:
                            // - Call DrowsinessTracker

                        }

                        if (doMouth) {
                            lastMouthMs = now2
                            val mouth =
                                roiYuv420ToRgbChwUprightROI(imageProxy, mouthRect, outSize = 160)?: return@launch

                            val mouthResult =
                                executorchBinaryClassifier(
                                    mouthModel, MouthState.entries, 160, mouth
                                );

                            Log.d("faceResult", "mouthResult:  ${mouthResult}")

                                    //TODO:
                                    // - Call DrowsinessTracker
                        }
                    } finally{
                        isProcessing.set(false)
                        imageProxy.close()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("CameraAnalyzer", "FaceMesh error", e)
                _roisOnPreview.value = emptyList()
            }
    }

    /**
     * Get Asset File Path
     */
    fun getAssetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        context.assets.open(assetName).use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
            }
        }
        return file.absolutePath
    }

    /**
     * FaceMesh points -> Square ROI
     */
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

    /**
     * ImageProxy rect coords ->  PreviewView Rect coords
     */
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

/**
 * Debug helper:
 * - Takes a normalized RGB CHW tensor (same as model input) and converts it back to a Bitmap.
 * - Performs de-normalization: x * std + mean, then maps [0,1] -> [0,255].
 * - Useful to visually verify that ROI crop + rotation + channel order + normalization are correct.
 */
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