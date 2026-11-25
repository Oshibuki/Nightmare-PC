package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.canvas.Pt
import com.abrah.nightmare.desktop.canvas.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The canvas draws in pixels while wires are defined in world units. A wire
 * that ignores the viewport leaves its ports when the canvas is zoomed or
 * panned, and then no longer matches the hit-tested curve. These tests pin the
 * observable mapping from a world wire to the points a DrawScope receives.
 */
class WireScreenPathTest {

    @Test
    fun `wire endpoints land on the transformed ports when scaled and panned`() {
        val curve = wireScreenPath(Pt(10f, 20f), Pt(90f, 60f), Viewport(Pt(30f, 40f), 2f))

        assertPoint(50f, 80f, curve(0f))
        assertPoint(210f, 160f, curve(1f))
    }

    @Test
    fun `an interior sample follows the world curve after the viewport transform`() {
        // World wire (0,0)->(100,100): dx = 50, so the cubic's control points
        // are (50,0) and (50,100). At t=0.25 that is (29.6875, 15.625); at
        // t=0.5 it is (50,50). Viewport scale 2, offset (10,20).
        val curve = wireScreenPath(Pt(0f, 0f), Pt(100f, 100f), Viewport(Pt(10f, 20f), 2f))

        assertPoint(69.375f, 51.25f, curve(0.25f))
        assertPoint(110f, 120f, curve(0.5f))
    }

    @Test
    fun `device density multiplies the scale of every sampled point`() {
        val viewport = Viewport(Pt(10f, 20f), 1.5f).forDevice(2f)
        val curve = wireScreenPath(Pt(0f, 0f), Pt(100f, 0f), viewport)

        assertPoint(10f, 20f, curve(0f))
        assertPoint(160f, 20f, curve(0.5f))
        assertPoint(310f, 20f, curve(1f))
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pt) {
        assertEquals(expectedX, actual.x, 0.001f)
        assertEquals(expectedY, actual.y, 0.001f)
    }
}
