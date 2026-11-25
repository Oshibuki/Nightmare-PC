package com.abrah.nightmare.desktop.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam: [ProductIdentity.banner], the only thing :cli asks :core for right now.
 * This test exists for two reasons: it pins the product identity the CLI prints,
 * and it proves :core's test source set actually runs (a skeleton whose tests
 * are silently skipped is worse than no tests).
 */
class ProductIdentityTest {

    @Test
    fun `banner names the product and its version`() {
        // Spelled out rather than recomputed from the build, so bumping the
        // version in the catalog without meaning to fails here instead of
        // silently changing what users see.
        assertEquals("nightmare-desktop 0.1.0", ProductIdentity.banner())
    }
}
