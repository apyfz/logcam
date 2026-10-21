package com.apyfz.logcam.camera

import kotlin.math.roundToInt

/** Maps the displayed GL quad back to the camera buffer, including its actual texture transform. */
object TouchMetering {
    data class Preview(val width: Int, val height: Int, val rotation: Int,
        val fitX: Float, val fitY: Float, val texture: FloatArray)

    fun map(x: Float, y: Float, preview: Preview): Pair<Float, Float>? {
        if (preview.width <= 0 || preview.height <= 0) return null
        val sx = 2f * x / preview.width - 1f
        val sy = 1f - 2f * y / preview.height
        val (rx, ry) = when ((preview.rotation % 360 + 360) % 360) {
            90 -> -sy to sx
            180 -> -sx to -sy
            270 -> sy to -sx
            else -> sx to sy
        }
        val qx = rx / preview.fitX
        val qy = ry / preview.fitY
        if (qx !in -1f..1f || qy !in -1f..1f) return null // letterbox
        val u = (qx + 1f) / 2f
        val v = (qy + 1f) / 2f
        val m = preview.texture
        return (m[0] * u + m[4] * v + m[12]).coerceIn(0f, 1f) to
            (m[1] * u + m[5] * v + m[13]).coerceIn(0f, 1f)
    }

    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    /** zoomRatio uses post-zoom coordinates; only the stream aspect crop is needed here. */
    fun region(x: Float, y: Float, left: Int, top: Int, width: Int, height: Int): Region {
        val cropW = minOf(width.toFloat(), height * (16f / 9f))
        val cropH = minOf(height.toFloat(), width / (16f / 9f))
        val w = (cropW * .15f).roundToInt().coerceIn(1, width)
        val h = (cropH * .15f).roundToInt().coerceIn(1, height)
        val cx = left + (width - cropW) / 2f + x.coerceIn(0f, 1f) * cropW
        val cy = top + (height - cropH) / 2f + y.coerceIn(0f, 1f) * cropH
        return Region((cx - w / 2f).roundToInt().coerceIn(left, left + width - w),
            (cy - h / 2f).roundToInt().coerceIn(top, top + height - h), w, h)
    }
}
