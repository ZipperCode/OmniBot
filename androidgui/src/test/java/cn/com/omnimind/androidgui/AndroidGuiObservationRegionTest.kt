package cn.com.omnimind.androidgui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGuiObservationRegionTest {

    @Test
    fun `valid coordinates construct region successfully`() {
        val region = AndroidGuiObservationRegion(x1 = 100.0, y1 = 200.0, x2 = 500.0, y2 = 600.0)
        assertEquals(100.0, region.x1, 0.001)
        assertEquals(200.0, region.y1, 0.001)
        assertEquals(500.0, region.x2, 0.001)
        assertEquals(600.0, region.y2, 0.001)
        assertEquals(listOf(100.0, 200.0, 500.0, 600.0), region.toNormalizedList())
    }

    @Test
    fun `out of range coordinates are strictly rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGuiObservationRegion(x1 = -1.0, y1 = 0.0, x2 = 500.0, y2 = 500.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGuiObservationRegion(x1 = 0.0, y1 = 0.0, x2 = 1001.0, y2 = 500.0)
        }
    }

    @Test
    fun `zero or negative area is strictly rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            // x2 == x1
            AndroidGuiObservationRegion(x1 = 300.0, y1 = 200.0, x2 = 300.0, y2 = 400.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            // y2 < y1
            AndroidGuiObservationRegion(x1 = 100.0, y1 = 500.0, x2 = 300.0, y2 = 200.0)
        }
    }

    @Test
    fun `fromMap parses map with separate coordinates and bounds array`() {
        assertNull(AndroidGuiObservationRegion.fromMap(null))
        assertNull(AndroidGuiObservationRegion.fromMap(emptyMap()))

        val fromSeparate = AndroidGuiObservationRegion.fromMap(
            mapOf("x1" to 50, "y1" to 100, "x2" to 400, "y2" to 500)
        )
        assertNotNull(fromSeparate)
        assertEquals(50.0, fromSeparate!!.x1, 0.001)
        assertEquals(500.0, fromSeparate.y2, 0.001)

        val fromList = AndroidGuiObservationRegion.fromMap(
            mapOf("bounds" to listOf(10.0, 20.0, 300.0, 400.0))
        )
        assertNotNull(fromList)
        assertEquals(10.0, fromList!!.x1, 0.001)
        assertEquals(400.0, fromList.y2, 0.001)
    }

    @Test
    fun `fromMap rejects incomplete or non numeric coordinates without guessing`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGuiObservationRegion.fromMap(mapOf("x1" to 50, "y1" to 100, "x2" to 400)) // missing y2
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGuiObservationRegion.fromMap(mapOf("bounds" to listOf(10, 20, 30))) // only 3 elements
        }
    }
}
