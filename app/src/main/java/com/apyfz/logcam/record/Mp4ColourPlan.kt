package com.apyfz.logcam.record

/** Bounded ISO BMFF traversal. Never scans compressed media for strings resembling boxes. */
internal object Mp4ColourPlan {
    data class Patch(val offset: Long, val bytes: ByteArray)
    data class Plan(val patches: List<Patch>, val notes: List<String>)
    private data class Box(val type: String, val payload: Long, val end: Long)

    fun inspect(size: Long, read: (Long, Int) -> ByteArray): Plan {
        val patches = mutableListOf<Patch>()
        val notes = mutableListOf<String>()
        var boxCount = 0
        fun bytes(offset: Long, count: Int): ByteArray {
            require(offset >= 0 && count >= 0 && count <= size - offset)
            return read(offset, count).also { require(it.size == count) }
        }
        fun uint(b: ByteArray, start: Int, count: Int): Long {
            var value = 0L
            repeat(count) { require(value <= Long.MAX_VALUE ushr 8); value = (value shl 8) or (b[start + it].toLong() and 255) }
            return value
        }
        fun boxes(start: Long, end: Long): List<Box> {
            val result = mutableListOf<Box>()
            var at = start
            while (at < end) {
                require(++boxCount <= 100000 && end - at >= 8)
                val header = bytes(at, 8)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                val shortSize = uint(header, 0, 4)
                val headerSize = if (shortSize == 1L) 16 else 8
                val length = when (shortSize) {
                    0L -> end - at
                    1L -> { require(end - at >= 16); uint(bytes(at + 8, 8), 0, 8) }
                    else -> shortSize
                }
                require(length >= headerSize && length <= end - at)
                result += Box(type, at + headerSize, at + length)
                at += length
            }
            return result
        }
        fun sample(entry: Box) {
            if (entry.type != "hvc1" && entry.type != "hev1") return
            require(entry.end - entry.payload >= 78)
            val children = boxes(entry.payload + 78, entry.end)
            val config = children.filter { it.type == "hvcC" }.singleOrNull()
            val colour = config?.let {
                require(it.end - it.payload <= 1024 * 1024)
                HevcColourMetadata.fromHvcc(bytes(it.payload, (it.end - it.payload).toInt()))
            }
            if (colour == null) { notes += "HEVC SPS colour description absent, ambiguous or unsupported; tags unchanged"; return }
            val colrs = children.filter { it.type == "colr" }
            var supported = false
            for (box in colrs) {
                if (box.end - box.payload != 11L) continue
                val original = bytes(box.payload, 11)
                if (String(original, 0, 4, Charsets.US_ASCII) != "nclx") continue
                supported = true
                val replacement = colour.nclx()
                if (!original.copyOfRange(4, 11).contentEquals(replacement)) {
                    patches += Patch(box.payload + 4, replacement)
                }
            }
            notes += if (supported) "nclx matched to SPS VUI: $colour" else "No supported nclx box; tags unchanged"
        }
        fun descend(start: Long, end: Long, depth: Int) {
            require(depth <= 8)
            for (box in boxes(start, end)) {
                when (box.type) {
                    "moov", "trak", "mdia", "minf", "stbl" -> descend(box.payload, box.end, depth + 1)
                    "stsd" -> {
                        require(box.end - box.payload >= 8)
                        val header = bytes(box.payload, 8)
                        require(header[0].toInt() == 0)
                        val entries = boxes(box.payload + 8, box.end)
                        require(uint(header, 4, 4) == entries.size.toLong())
                        entries.forEach { sample(it) }
                    }
                }
            }
        }
        descend(0, size, 0)
        if (notes.isEmpty()) notes += "No HEVC sample description; tags unchanged"
        return Plan(patches, notes)
    }
}
