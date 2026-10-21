package com.apyfz.logcam.record

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class Mp4ColourPlanTest {
    // hvcC from unmodified MagicOS11 custom256 Main10 capture, run-1792580477902.
    // Independent ffmpeg trace_headers reports primaries=5 transfer=6 matrix=5 full_range=1.
    private val hvcc = hex("012220000000b0000000000078f000fcfdfafa00000303a00001001740010c01ffff222000000300b00000030000030078ac09a100010027420101222000000300b00000030000030078a003c0801107cad96bb9324ba9b828302817685094a20001000a4401c1e30f8941ef6128")
    private val old = byteArrayOf(0, 5, 0, 1, 0, 6, 0x80.toByte())
    private val correct = byteArrayOf(0, 5, 0, 6, 0, 5, 0x80.toByte())

    @Test fun `actual Honor SPS colour matches independent decoder`() {
        assertEquals(HevcColourMetadata.Colour(5, 6, 5, true), HevcColourMetadata.fromHvcc(hvcc))
    }
    @Test fun `HLG transfer in SPS remains HLG instead of being stripped`() {
        // Same fixture, transfer_characteristics at bit 261 of the SPS changed from 6 to 18.
        val config = hex("012220000000b0000000000078f000fcfdfafa00000303a00001001740010c01ffff222000000300b00000030000030078ac09a100010027420101222000000300b00000030000030078a003c0801107cad96bb9324ba9b828902817685094a20001000a4401c1e30f8941ef6128")
        assertEquals(18, HevcColourMetadata.fromHvcc(config)?.transfer)
        assertEquals(18, inspect(movie(config, old)).patches.single().bytes[3].toInt())
    }
    @Test fun `only actual sample entry nclx is patched and second pass is idempotent`() {
        val fake = box("mdat", box("colr", "nclx".toByteArray() + old))
        val file = fake + movie(hvcc, old)
        val result = inspect(file)
        assertEquals(1, result.patches.size)
        val patch = result.patches.single()
        assertArrayEquals(correct, patch.bytes)
        val changed = file.clone()
        patch.bytes.copyInto(changed, patch.offset.toInt())
        assertArrayEquals(fake, changed.copyOfRange(0, fake.size))
        assertTrue(inspect(changed).patches.isEmpty())
        assertEquals(2, file.indices.count { file[it] != changed[it] })
    }
    @Test fun `malformed and absent SPS leave tags untouched`() {
        for (config in listOf(hvcc.copyOf(40), hvcc.copyOf(23).also { it[22] = 0 }, ByteArray(23))) {
            val result = inspect(movie(config, old))
            assertTrue(result.patches.isEmpty())
            assertTrue(result.notes.any { "unchanged" in it })
        }
    }
    @Test fun `truncation at every hvcc byte cannot yield a colour`() {
        for (length in hvcc.indices) assertNull("length=$length", HevcColourMetadata.fromHvcc(hvcc.copyOf(length)))
    }
    @Test fun `invalid box size rejects complete plan before mutation`() {
        val file = movie(hvcc, old) + byteArrayOf(0, 0, 0, 4, 102, 114, 101, 101)
        assertTrue(runCatching { inspect(file) }.isFailure)
    }
    @Test fun `extended size media box is skipped`() {
        val media = ByteBuffer.allocate(32).putInt(1).put("mdat".toByteArray()).putLong(32).put(ByteArray(16)).array()
        assertEquals(1, inspect(media + movie(hvcc, old)).patches.size)
    }
    @Test fun `missing nclx leaves file unchanged with explanation`() {
        val sample = box("hvc1", ByteArray(78) + box("hvcC", hvcc))
        val file = box("moov", box("stsd", ByteArray(4) + int(1) + sample))
        assertTrue(inspect(file).patches.isEmpty())
        assertTrue(inspect(file).notes.single().contains("No supported nclx"))
    }
    private fun movie(config: ByteArray, colour: ByteArray): ByteArray {
        val sample = box("hvc1", ByteArray(78) + box("hvcC", config) + box("colr", "nclx".toByteArray() + colour))
        var tree = box("stsd", ByteArray(4) + int(1) + sample)
        for (type in listOf("stbl", "minf", "mdia", "trak", "moov")) tree = box(type, tree)
        return tree
    }
    private fun inspect(file: ByteArray) = Mp4ColourPlan.inspect(file.size.toLong()) { at, count -> file.copyOfRange(at.toInt(), at.toInt() + count) }
    private fun int(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
    private fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray() + payload
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
