package com.apyfz.logcam.record

import android.system.Os
import android.util.Log
import java.io.FileDescriptor

/** Keeps container labels consistent with the encoder's SPS. This is not colour calibration. */
object ColrPatcher {
    private const val TAG = "ColrPatcher"

    /** Preserves the bitstream (including HLG when present). Unknown metadata stays unchanged. */
    fun patch(descriptor: FileDescriptor): Boolean = runCatching {
        val plan = Mp4ColourPlan.inspect(Os.fstat(descriptor).st_size) { offset, count ->
            ByteArray(count).also { bytes ->
                var read = 0
                while (read < count) {
                    val n = Os.pread(descriptor, bytes, read, count - read, offset + read)
                    check(n > 0) { "Truncated MP4" }
                    read += n
                }
            }
        }
        // Parse the complete tree before making any changes, so malformed files are left intact.
        for (patch in plan.patches) {
            var written = 0
            while (written < patch.bytes.size) {
                val n = Os.pwrite(descriptor, patch.bytes, written, patch.bytes.size - written, patch.offset + written)
                check(n > 0) { "Could not write nclx metadata" }
                written += n
            }
        }
        plan.notes.forEach { Log.i(TAG, it) }
        Log.i(TAG, "Updated ${plan.patches.size} nclx boxes; encoded samples unchanged")
        plan.patches.isNotEmpty()
    }.onFailure { Log.e(TAG, "Colour metadata reconciliation failed; preserving recorded take", it) }.getOrDefault(false)
}
