package com.apyfz.logcam.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeParserTest {

    @Test
    fun rejectsOversized3dLutBeforeReadingRows() {
        val result = CubeParser.parse("LUT_3D_SIZE 256\n")

        assertTrue(result is CubeResult.Error)
        result as CubeResult.Error
        assertEquals(1, result.line)
        assertEquals("LUT size 256 out of range 2..65", result.message)
    }

    @Test
    fun parsesLowercaseTitleWithoutKeepingKeyword() {
        val result = CubeParser.parse(
            """
            title "Warm"
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
            """.trimIndent(),
        )

        assertTrue(result is CubeResult.Ok)
        assertEquals("Warm", (result as CubeResult.Ok).lut.title)
    }
}
