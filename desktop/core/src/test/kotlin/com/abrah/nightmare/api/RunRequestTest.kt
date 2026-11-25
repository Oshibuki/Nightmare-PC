package com.abrah.nightmare.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM migration of the `/run` body assertions that do not need a `Graph` or `NODE_TYPES`. */
class RunRequestTest {

    private fun refused(json: String): String = try {
        RunRequest.parse(json)
        fail("expected a refusal for $json")
        ""
    } catch (e: ApiError) {
        assertEquals(400, e.code)
        e.message.orEmpty()
    }

    @Test
    fun aBodyIsParsedIntoItsFields() {
        val r = RunRequest.parse(
            """{"flow":"x","model":"sd15","prompt":"a cat","negative":"blur","steps":12,"cfg":4.5,"seed":7,"stream":true,"params":{"generate":{"x":"-0.2"}},"images":{"image":"AAAA"}}""",
        )
        assertEquals("x", r.flow)
        assertEquals("sd15", r.model)
        assertEquals("a cat", r.prompt)
        assertEquals("blur", r.negative)
        assertEquals(mapOf("steps" to "12", "cfg" to "4.5", "seed" to "7"), r.knobs)
        assertEquals(mapOf("generate" to mapOf("x" to "-0.2")), r.params)
        assertEquals(mapOf("image" to "AAAA"), r.images)
        assertTrue(r.stream)
    }

    @Test
    fun everyMistakeIsNamedBeforeAnythingRuns() {
        assertTrue(refused("""{"flow":"x","graph":{}}""").contains("exactly one"))
        assertTrue(refused("""{"prompt":"a"}""").contains("exactly one"))
        assertTrue(refused("""{"flow":"x","colour":"red"}""").contains("unknown field"))
        assertTrue(refused("nope").contains("not JSON"))
        assertTrue(refused("""{"flow":"x","params":{"generate":"no"}}""").contains("must be an object"))
    }
}
