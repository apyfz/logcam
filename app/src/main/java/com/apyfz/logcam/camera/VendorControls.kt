package com.apyfz.logcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/**
 * Honor's own image controls, exposed as Camera2 vendor tags rather than standard keys.
 *
 * `EDGE_MODE_OFF` disables the ISP's edge enhancement stage, but Honor runs additional contrast,
 * saturation and sharpness adjustment of its own — the sliders behind their stock camera app. Those
 * live in `com.hihonor.capture.metadata` and are the only remaining way to flatten the picture on
 * this device, since replacing the tonemap curve destroys colour.
 *
 * Vendor keys are unsupported by definition: the names come from `dumpsys media.camera` on one
 * device, nothing guarantees they are writable, and a HAL is free to ignore them. Every access is
 * therefore wrapped — a missing or read-only tag degrades to "control unavailable", never a crash.
 */
object VendorControls {

    private const val SECTION = "com.hihonor.capture.metadata"
    private const val CAPABILITIES = "com.hihonor.device.capabilities"

    /** Honor spells it "constrast". Matching their typo is required; the tag name is the address. */
    val CONTRAST = requestKey("$SECTION.constrastValue")
    val SATURATION = requestKey("$SECTION.saturationValue")
    val BRIGHTNESS = requestKey("$SECTION.brightnessValue")
    val SHARPNESS = requestKey("$SECTION.sharpnessValue")

    private val AVAILABLE_CONTRAST = characteristicsKey("$CAPABILITIES.availbaleContrast")
    private val AVAILABLE_SATURATION = characteristicsKey("$CAPABILITIES.availbaleSaturation")
    private val SUPPORTED_SHARPNESS = characteristicsKey("$CAPABILITIES.supported_sharpness")

    /** What the device reports it accepts, or null where the tag is absent or unreadable. */
    data class Ranges(
        val contrast: ByteArray?,
        val saturation: ByteArray?,
        val sharpness: ByteArray?,
    ) {
        val any: Boolean get() = contrast != null || saturation != null || sharpness != null

        fun describe(): String = buildString {
            append("contrast=").append(contrast?.joinToString(",") ?: "absent")
            append(" saturation=").append(saturation?.joinToString(",") ?: "absent")
            append(" sharpness=").append(sharpness?.joinToString(",") ?: "absent")
        }
    }

    fun readRanges(characteristics: CameraCharacteristics) = Ranges(
        contrast = read(characteristics, AVAILABLE_CONTRAST),
        saturation = read(characteristics, AVAILABLE_SATURATION),
        sharpness = read(characteristics, SUPPORTED_SHARPNESS),
    )

    /** Applies a control, silently doing nothing when the device does not expose it. */
    fun set(request: CaptureRequest.Builder, key: CaptureRequest.Key<Byte>?, value: Byte) {
        if (key == null) return
        runCatching { request.set(key, value) }
    }

    private fun read(
        characteristics: CameraCharacteristics,
        key: CameraCharacteristics.Key<ByteArray>?,
    ): ByteArray? = if (key == null) null else runCatching { characteristics.get(key) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun requestKey(name: String): CaptureRequest.Key<Byte>? = runCatching {
        CaptureRequest.Key::class.java
            .getDeclaredConstructor(String::class.java, Class::class.java)
            .apply { isAccessible = true }
            .newInstance(name, Byte::class.javaObjectType) as CaptureRequest.Key<Byte>
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun characteristicsKey(name: String): CameraCharacteristics.Key<ByteArray>? =
        runCatching {
            CameraCharacteristics.Key::class.java
                .getDeclaredConstructor(String::class.java, Class::class.java)
                .apply { isAccessible = true }
                .newInstance(name, ByteArray::class.java) as CameraCharacteristics.Key<ByteArray>
        }.getOrNull()
}
