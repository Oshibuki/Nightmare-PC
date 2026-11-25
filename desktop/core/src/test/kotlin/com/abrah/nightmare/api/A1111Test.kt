package com.abrah.nightmare.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM migration of the platform-free A1111 assertions in the mobile `ApiTest`. */
class A1111Test {

    @Test
    fun onlySdApiV1PathsAreHandled() {
        assertTrue(A1111.handles(listOf("sdapi", "v1", "txt2img")))
        assertTrue(A1111.handles(listOf("sdapi", "v1")))
        assertFalse(A1111.handles(listOf("models")))
        assertFalse(A1111.handles(listOf("sdapi", "v2", "txt2img")))
        assertFalse(A1111.handles(emptyList()))
        assertEquals(listOf("sdapi", "v1", "txt2img"), A1111.TXT2IMG)
    }

    @Test
    fun theNearestAspectIsTheOneAClientAskedFor() {
        assertEquals("16:9", A1111.nearestAspect(1920, 1080))
        assertEquals("1:1", A1111.nearestAspect(512, 512))
        assertEquals("2:3", A1111.nearestAspect(832, 1216))
    }
}
