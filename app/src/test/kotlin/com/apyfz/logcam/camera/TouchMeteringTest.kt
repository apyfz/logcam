package com.apyfz.logcam.camera

import org.junit.Assert.*
import org.junit.Test

class TouchMeteringTest {
    private val flipY = floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)
    @Test fun ignoresLetterboxAndMapsVisibleCorners() {
        val p = TouchMetering.Preview(200,200,0,1f,.5625f,flipY)
        assertNull(TouchMetering.map(100f,10f,p))
        assertPoint(0f,0f,TouchMetering.map(0f,43.75f,p))
        assertPoint(1f,1f,TouchMetering.map(200f,156.25f,p))
    }
    @Test fun allRotationsMapDisplayedPointBackToSameSensorPoint() {
        for (angle in listOf(0,90,180,270)) {
            // Native upper-left quarter has GL coordinates (-.5,+.5).
            val point = when(angle) {
                0 -> 50f to 50f
                90 -> 150f to 50f
                180 -> 150f to 150f
                else -> 50f to 150f
            }
            assertPoint(.25f,.25f,TouchMetering.map(point.first,point.second,
                TouchMetering.Preview(200,200,angle,1f,1f,flipY)))
        }
    }
    @Test fun honoursTextureCrop() {
        val crop=flipY.copyOf().apply { this[0]=.8f; this[12]=.1f }
        assertPoint(.1f,.5f,TouchMetering.map(0f,100f,TouchMetering.Preview(200,200,0,1f,1f,crop)))
    }
    @Test fun regionsStayInsideOffsetSensorAtEveryCorner() {
        for(x in listOf(0f,1f)) for(y in listOf(0f,1f)) {
            val r=TouchMetering.region(x,y,10,20,4000,3000)
            assertTrue(r.left>=10 && r.top>=20)
            assertTrue(r.left+r.width<=4010 && r.top+r.height<=3020)
        }
    }
    @Test fun sixteenByNineCropIsCentredInFourByThreeSensor() {
        val r=TouchMetering.region(.5f,0f,0,0,4000,3000)
        assertEquals(375f,r.top+r.height/2f,1f)
    }
    private fun assertPoint(x:Float,y:Float,p:Pair<Float,Float>?) {
        assertNotNull(p); assertEquals(x,p!!.first,.0001f); assertEquals(y,p.second,.0001f)
    }
}
