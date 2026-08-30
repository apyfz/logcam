package com.apyfz.logcam.record

import android.system.Os
import android.util.Log
import java.io.FileDescriptor

/**
 * Rewrites the colour tags in a finished MP4 so the file does not claim to be HDR.
 *
 * 10-bit capture on Android is only reachable through an HDR dynamic range profile — Camera2 offers
 * HLG10, HDR10 and HDR10+ and nothing else — so the encoder stamps the file as HLG no matter what
 * the MediaFormat asks for. The samples are a log signal, not HDR, and a file that claims otherwise
 * gets tone-mapped by every player and editor that opens it, which is the punchy clipped rendering
 * this app exists to avoid.
 *
 * The fix is to correct the claim, not the pixels. This rewrites the `colr` box in place: BT.2020
 * primaries kept, because that genuinely is the gamut, and the transfer set to BT.709 so nothing
 * treats the file as HDR. It is the same combination the Find X8 Ultra writes for its O-Log footage.
 *
 * No re-encode, no copy, no second file — six bytes change.
 */
object ColrPatcher {

    private const val TAG = "ColrPatcher"

    /** ISO/IEC 23001-8 code points. BT.709 transfer is what marks the file as ordinary SDR. */
    private const val TRANSFER_BT709 = 1
    private const val PRIMARIES_BT2020 = 9
    private const val MATRIX_BT2020_NCL = 9

    /**
     * Patches every `nclx` colour box in the file. Returns true if at least one was rewritten.
     *
     * A failure here is not fatal: the take is already recorded and playable, only mislabelled. The
     * caller logs and carries on rather than discarding footage over a tag.
     */
    fun patch(descriptor: FileDescriptor): Boolean = runCatching {
        val size = Os.fstat(descriptor).st_size
        var patched = 0
        var offset = 0L

        // MediaMuxer writes the moov atom at the END of the file, so the colour box is nowhere near
        // the start. Scanning the whole file in chunks is simpler and more robust than parsing the
        // box tree, and a local read of even a large take costs milliseconds. Chunks overlap by the
        // header length so a box straddling a boundary is still found.
        while (offset < size) {
            val want = minOf(CHUNK.toLong(), size - offset).toInt()
            if (want < HEADER) break

            val bytes = ByteArray(want)
            var read = 0
            while (read < want) {
                val n = Os.pread(descriptor, bytes, read, want - read, offset + read)
                if (n <= 0) break
                read += n
            }
            if (read < HEADER) break

            var i = 0
            while (i <= read - HEADER) {
                if (matchesColrNclx(bytes, i)) {
                    // After "colr" + "nclx": primaries, transfer and matrix as big-endian uint16,
                    // then a byte whose top bit is the full-range flag, which is left alone.
                    val payload = byteArrayOf(
                        *shortBytes(PRIMARIES_BT2020),
                        *shortBytes(TRANSFER_BT709),
                        *shortBytes(MATRIX_BT2020_NCL),
                    )
                    Os.pwrite(descriptor, payload, 0, payload.size, offset + i + 8)
                    patched++
                    i += HEADER
                } else {
                    i++
                }
            }
            offset += read - (HEADER - 1)
        }

        if (patched == 0) Log.w(TAG, "no nclx colr box found; file keeps its original tags")
        else Log.i(TAG, "rewrote $patched colr box(es) to BT.2020 primaries with BT.709 transfer")
        patched > 0
    }.onFailure { Log.e(TAG, "could not patch colour tags", it) }.getOrDefault(false)

    private fun matchesColrNclx(b: ByteArray, i: Int): Boolean =
        b[i] == 'c'.code.toByte() && b[i + 1] == 'o'.code.toByte() &&
            b[i + 2] == 'l'.code.toByte() && b[i + 3] == 'r'.code.toByte() &&
            b[i + 4] == 'n'.code.toByte() && b[i + 5] == 'c'.code.toByte() &&
            b[i + 6] == 'l'.code.toByte() && b[i + 7] == 'x'.code.toByte()

    /** Top bit set marks full range; the remaining bits of the byte are reserved. */
    private const val FULL_RANGE_FLAG: Byte = 0x80.toByte()

    private fun shortBytes(v: Int) = byteArrayOf((v ushr 8).toByte(), (v and 0xFF).toByte())

    /** "colr" + "nclx" + three uint16 + the range byte. */
    private const val HEADER = 15

    private const val CHUNK = 1 shl 22
}
