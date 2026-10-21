package com.apyfz.logcam.camera

import android.hardware.camera2.params.DynamicRangeProfiles
import org.junit.Assert.*
import org.junit.Test

class RecordingProfileTest {
    @Test fun `custom curves never select HLG even when offered`() {
        val profiles = setOf(DynamicRangeProfiles.HLG10)
        for (profile in RecordingProfile.entries.filter { it.usesCustomCurve }) {
            assertEquals(DynamicRangeProfiles.STANDARD, profile.dynamicRangeProfile(profiles))
            assertEquals(DynamicRangeProfiles.STANDARD, profile.dynamicRangeProfile(emptySet()))
        }
    }

    @Test fun `oversized requests cannot cross the observed grayscale boundary`() {
        for (profile in RecordingProfile.entries.filter { it.usesCustomCurve }) {
            for (requested in listOf(256, 257, 258, 512)) {
                val curve = profile.tonemapCurve(requested)!!
                assertEquals(256 * 2, curve.size)
                assertEquals(0f, curve[0], 0f)
                assertEquals(1f, curve[curve.size - 2], 0f)
            }
            assertEquals(128 * 2, profile.tonemapCurve(128)!!.size)
        }
    }

    @Test fun `HLG requires a real HLG capability and has no custom curve`() {
        assertNull(RecordingProfile.HLG10.tonemapCurve(0))
        assertEquals(DynamicRangeProfiles.HLG10,
            RecordingProfile.HLG10.dynamicRangeProfile(setOf(DynamicRangeProfiles.HLG10)))
        assertThrows(IllegalArgumentException::class.java) {
            RecordingProfile.HLG10.dynamicRangeProfile(emptySet())
        }
    }
}
