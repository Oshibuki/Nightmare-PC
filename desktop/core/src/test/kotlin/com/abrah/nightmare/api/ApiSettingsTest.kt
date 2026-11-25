package com.abrah.nightmare.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM migration of the token half of the mobile `ApiTest`. */
class ApiSettingsTest {

    @Test
    fun theTokenMustMatchExactly() {
        assertTrue(ApiSettings.matches("abc", "Bearer abc", null))
        assertTrue(ApiSettings.matches("abc", "bearer abc", null))
        assertTrue(ApiSettings.matches("abc", null, "abc"))
        assertFalse(ApiSettings.matches("abc", "Bearer abd", null))
        assertFalse(ApiSettings.matches("abc", null, null))
        assertFalse(ApiSettings.matches("abc", "Bearer ", null))
        // ⭐ HTTP Basic, the token as the password: an A1111 client's --api-auth.
        val basic = java.util.Base64.getEncoder().encodeToString("layla:abc".toByteArray())
        assertTrue(ApiSettings.matches("abc", "Basic $basic", null))
        assertFalse(
            ApiSettings.matches(
                "abc",
                "Basic " + java.util.Base64.getEncoder().encodeToString("layla:abd".toByteArray()),
                null,
            ),
        )
    }
}
