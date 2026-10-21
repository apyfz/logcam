package com.apyfz.logcam.record

/** Reads the explicit colour description from an HEVC SPS; does not infer scene colourimetry. */
internal object HevcColourMetadata {
    data class Colour(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean) {
        fun nclx() = byteArrayOf(0, primaries.toByte(), 0, transfer.toByte(), 0, matrix.toByte(),
            if (fullRange) 0x80.toByte() else 0)
    }

    fun fromHvcc(data: ByteArray): Colour? = runCatching {
        require(data.size >= 23 && data[0].toInt() == 1)
        var at = 23
        fun u8(): Int { require(at < data.size); return data[at++].toInt() and 255 }
        fun u16() = (u8() shl 8) or u8()
        val colours = mutableListOf<Colour>()
        repeat(data[22].toInt() and 255) {
            val type = u8() and 63
            repeat(u16()) {
                val size = u16()
                require(size <= data.size - at)
                if (type == 33) colours += fromSps(data.copyOfRange(at, at + size))
                    ?: error("SPS has no explicit supported colour description")
                at += size
            }
        }
        require(at == data.size)
        colours.distinct().singleOrNull()
    }.getOrNull()

    private fun fromSps(nal: ByteArray): Colour? {
        require(nal.size > 2 && ((nal[0].toInt() and 126) shr 1) == 33)
        // Remove emulation-prevention bytes before interpreting bit syntax.
        val rbsp = ArrayList<Byte>()
        var zeros = 0
        for (i in 2 until nal.size) {
            val v = nal[i].toInt() and 255
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            rbsp += nal[i]
            zeros = if (v == 0) zeros + 1 else 0
        }
        val b = Bits(rbsp.toByteArray())
        b.skip(4)
        val sublayers = b.read(3)
        require(sublayers <= 6)
        b.skip(1 + 96) // nesting + general profile_tier_level
        val profiles = BooleanArray(sublayers)
        val levels = BooleanArray(sublayers)
        repeat(sublayers) { profiles[it] = b.flag(); levels[it] = b.flag() }
        if (sublayers > 0) b.skip((8 - sublayers) * 2)
        repeat(sublayers) { if (profiles[it]) b.skip(88); if (levels[it]) b.skip(8) }
        b.ue() // SPS id
        val chroma = b.ue(); require(chroma <= 3)
        if (chroma == 3) b.skip(1)
        b.ue(); b.ue() // dimensions
        if (b.flag()) repeat(4) { b.ue() }
        b.ue(); b.ue() // bit depths
        val pocBits = b.ue() + 4; require(pocBits <= 16)
        val orderingAll = b.flag()
        for (i in (if (orderingAll) 0 else sublayers)..sublayers) repeat(3) { b.ue() }
        repeat(6) { b.ue() } // coding / transform block sizes and hierarchy depths
        if (b.flag() && b.flag()) {
            for (sizeId in 0..3) {
                for (matrixId in 0..5 step (if (sizeId == 3) 3 else 1)) {
                    if (!b.flag()) b.ue() else {
                        if (sizeId > 1) b.ue() // signed Exp-Golomb has same bit length
                        repeat(minOf(64, 1 shl (4 + 2 * sizeId))) { b.ue() }
                    }
                }
            }
        }
        b.skip(2) // AMP, SAO
        if (b.flag()) { b.skip(8); b.ue(); b.ue(); b.skip(1) }
        val rpsCount = b.ue(); require(rpsCount <= 64)
        val deltas = IntArray(rpsCount)
        repeat(rpsCount) { i ->
            if (i > 0 && b.flag()) {
                b.skip(1); b.ue()
                repeat(deltas[i - 1] + 1) {
                    val used = b.flag()
                    if (used || b.flag()) deltas[i]++
                }
            } else {
                val negative = b.ue(); val positive = b.ue()
                require(negative + positive <= 64)
                deltas[i] = negative + positive
                repeat(deltas[i]) { b.ue(); b.skip(1) }
            }
        }
        if (b.flag()) { val count = b.ue(); require(count <= 32); repeat(count) { b.skip(pocBits + 1) } }
        b.skip(2) // temporal MVP, smoothing
        if (!b.flag()) return null // vui_parameters_present_flag
        if (b.flag() && b.read(8) == 255) b.skip(32)
        if (b.flag()) b.skip(1) // overscan
        if (!b.flag()) return null // video_signal_type_present_flag
        b.skip(3)
        val full = b.flag()
        if (!b.flag()) return null // colour_description_present_flag
        val colour = Colour(b.read(8), b.read(8), b.read(8), full)
        // Code 2 means unspecified. Preserve original tags rather than fabricate a description.
        if (colour.primaries !in setOf(1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 22) ||
            colour.transfer !in setOf(1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18) ||
            colour.matrix !in setOf(0, 1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)) return null
        return colour
    }

    private class Bits(val bytes: ByteArray) {
        var bit = 0
        fun skip(n: Int) { require(n >= 0 && n <= bytes.size * 8 - bit); bit += n }
        fun read(n: Int): Int {
            require(n in 0..30 && n <= bytes.size * 8 - bit)
            var result = 0
            repeat(n) { result = (result shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1); bit++ }
            return result
        }
        fun flag() = read(1) != 0
        fun ue(): Int {
            var zeros = 0
            while (!flag()) { zeros++; require(zeros <= 20) }
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
