package com.example.drowseydriver1

import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import kotlin.math.floor
import kotlin.math.max

data class NormParams(
    val mean: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    val std:  FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NormParams

        if (!mean.contentEquals(other.mean)) return false
        if (!std.contentEquals(other.std)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mean.contentHashCode()
        result = 31 * result + std.contentHashCode()
        return result
    }
}

/**
 * Converts a ROI from ImageProxy(YUV_420_888) into a normalized RGB CHW float tensor.
 *
 * Assumption:
 * - roiUpright is in the "upright" coordinate system (same orientation as ML Kit results
 *   when you pass rotationDegrees to InputImage).
 * - This function inverse-maps upright (x,y) -> raw YUV (x,y) before sampling.
 *
 * Output:
 * - Shape: [3, outSize, outSize] (CHW)
 * - Values: rgb in [0,1], then (rgb - mean) / std (torchvision-style)
 *
 * Note:
 * - Uses nearest-neighbor sampling for resize.
 */

@OptIn(ExperimentalGetImage::class)
fun roiYuv420ToRgbChwUprightROI(
    imageProxy: ImageProxy,
    roiUpright: RectF,
    outSize: Int,
    norm: NormParams = NormParams()
): FloatArray? {
    val img = imageProxy.image ?: return null

    val rawW = img.width
    val rawH = img.height
    val rot = imageProxy.imageInfo.rotationDegrees

    // upright-ed image size (after calculating camera rotation) - logical width/height
    val upW = if (rot == 90 || rot == 270) rawH else rawW
    val upH = if (rot == 90 || rot == 270) rawW else rawH

    // ROI clamp (to avoid IndexOutOfBoundsException) - logical x/y
    val left   = roiUpright.left.coerceIn(0f, (upW - 1).toFloat())
    val top    = roiUpright.top.coerceIn(0f, (upH - 1).toFloat())
    val right  = roiUpright.right.coerceIn(0f, upW.toFloat())
    val bottom = roiUpright.bottom.coerceIn(0f, upH.toFloat())

    val roiW = max(1f, right - left)
    val roiH = max(1f, bottom - top)

    val planes = img.planes
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    // update position for duplicated plane, keep original planes
    val yBuf = yPlane.buffer.duplicate()
    val uBuf = uPlane.buffer.duplicate()
    val vBuf = vPlane.buffer.duplicate()

    fun readPlaneByte(buf: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
        val idx = y * rowStride + x * pixelStride
        return buf.get(idx).toInt() and 0xFF
    }

    val yRow = yPlane.rowStride
    val yPix = yPlane.pixelStride
    val uRow = uPlane.rowStride
    val uPix = uPlane.pixelStride
    val vRow = vPlane.rowStride
    val vPix = vPlane.pixelStride

    fun sampleY(rawX: Int, rawY: Int): Int =
        readPlaneByte(yBuf, yRow, yPix, rawX, rawY)

    fun sampleU(rawX: Int, rawY: Int): Int =
        readPlaneByte(uBuf, uRow, uPix, rawX / 2, rawY / 2)

    fun sampleV(rawX: Int, rawY: Int): Int =
        readPlaneByte(vBuf, vRow, vPix, rawX / 2, rawY / 2)

    val out = FloatArray(3 * outSize * outSize)
    val planeSize = outSize * outSize

    for (oy in 0 until outSize) {
        val yuF = top + (oy + 0.5f) * (roiH / outSize)
        val yu = floor(yuF).toInt().coerceIn(0, upH - 1)

        for (ox in 0 until outSize) {
            val xuF = left + (ox + 0.5f) * (roiW / outSize)
            val xu = floor(xuF).toInt().coerceIn(0, upW - 1)

            // upright(xu,yu) -> raw(xr,yr)
            val (xr0, yr0) = uprightToRawXY(xu, yu, rot, rawW, rawH)
            val xr = xr0.coerceIn(0, rawW - 1)
            val yr = yr0.coerceIn(0, rawH - 1)

            val Y = sampleY(xr, yr)
            val U = sampleU(xr, yr) - 128
            val V = sampleV(xr, yr) - 128

            // YUV -> RGB
            var r = Y + 1.402f * V
            var g = Y - 0.344136f * U - 0.714136f * V
            var b = Y + 1.772f * U

            r = r.coerceIn(0f, 255f)
            g = g.coerceIn(0f, 255f)
            b = b.coerceIn(0f, 255f)

            var rf = (r / 255f - norm.mean[0]) / norm.std[0]
            var gf = (g / 255f - norm.mean[1]) / norm.std[1]
            var bf = (b / 255f - norm.mean[2]) / norm.std[2]

            val i = oy * outSize + ox
            out[0 * planeSize + i] = rf
            out[1 * planeSize + i] = gf
            out[2 * planeSize + i] = bf
        }
    }
    return out
}

private fun uprightToRawXY(
    xu: Int,
    yu: Int,
    rot: Int,
    rawW: Int,
    rawH: Int
): Pair<Int, Int> {
    return when ((rot % 360 + 360) % 360) {
        0 -> xu to yu
        90 -> {
            // upright = rotate(raw, 90 CW)
            // inverse: rawX = yu, rawY = rawH - 1 - xu
            yu to (rawH - 1 - xu)
        }
        180 -> {
            (rawW - 1 - xu) to (rawH - 1 - yu)
        }
        270 -> {
            // inverse of rotate 270 CW(=90 CCW)
            // rawX = rawW - 1 - yu, rawY = xu
            (rawW - 1 - yu) to xu
        }
        else -> xu to yu
    }
}
