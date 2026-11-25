package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `app/src/test/java/com/abrah/nightmare/SamplerNamingTest.kt` on
 * 2026-11-26 (ADR-0001). The assertions are the mobile ones unchanged; only the
 * receiver moved from `ModelCatalog` to [Samplers] and the tests that needed a
 * `Family` (an Android-coupled enum this copy does not carry) were trimmed --
 * see `desktop/docs/copied-files.md`.
 *
 * ⚠⚠ What these guard: the wire value is the ONLY thing that reaches the
 * backend, and an unknown base falls through to `dpm` in silence. A UI -- or a
 * command line -- that could emit `lcm_karras` would render with the wrong
 * sampler and report success.
 */
class SamplersTest {

    /** ⭐ Round trip: every shipped wire value must survive split -> join. */
    @Test
    fun everyWireValueRoundTrips() {
        for (v in Samplers.SCHEDULERS + Samplers.DIT_SCHEDULERS) {
            val (base, schedule) = Samplers.splitScheduler(v)
            assertEquals("$v did not round-trip", v, Samplers.joinScheduler(base, schedule))
        }
    }

    /** ⚠⚠ Whatever the picker can produce must be a value the backend knows. */
    @Test
    fun thePickerCanOnlyProduceKnownWireValues() {
        for ((id, _) in Samplers.SAMPLERS) {
            for ((sfx, _) in Samplers.SCHEDULES) {
                val v = Samplers.joinScheduler(id, sfx)
                assertTrue("\"$v\" is not a scheduler the backend implements", v in Samplers.SCHEDULERS)
            }
        }
    }

    /** ⚠⚠ **LCM has no schedule to change**: never `lcm_karras` or `lcm_exponential`. */
    @Test
    fun lcmNeverGainsAScheduleSuffix() {
        assertFalse(Samplers.schedulesSupported("lcm"))
        assertEquals("lcm", Samplers.joinScheduler("lcm", "_karras"))
        assertEquals("lcm", Samplers.joinScheduler("lcm", "_exponential"))
        assertEquals("lcm", Samplers.joinScheduler("lcm", true))
    }

    /** ⚠ ...and every other SD sampler has all three, or the dropdown is a lie. */
    @Test
    fun everyOtherSamplerHasEverySchedule() {
        for ((id, _) in Samplers.SAMPLERS.filter { it.first != "lcm" }) {
            assertTrue(Samplers.schedulesSupported(id))
            assertTrue(Samplers.joinScheduler(id, "_karras") in Samplers.SCHEDULERS)
            assertTrue(Samplers.joinScheduler(id, "_exponential") in Samplers.SCHEDULERS)
        }
    }

    /** ⚠ The old ids (saved flows, `config.json`s) still parse as they did. */
    @Test
    fun theOldIdsStillParse() {
        assertEquals("dpm_sde" to "_karras", Samplers.splitScheduler("dpm_sde_karras"))
        assertEquals("euler_a" to "", Samplers.splitScheduler("euler_a"))
        assertEquals("dpm" to "", Samplers.splitScheduler("dpm"))
    }

    /** ⚠ An unknown value resolves to the DEFAULT rather than passing through. */
    @Test
    fun anUnknownSchedulerFallsBackToTheDefault() {
        val (base, schedule) = Samplers.splitScheduler("unipc_exponential")
        assertEquals(Samplers.DEFAULT_SCHEDULER, base)
        assertEquals("", schedule)
    }

    /** Labels read the way other SD tools name them. */
    @Test
    fun labelsAreTheFamiliarNames() {
        assertEquals("DPM++ 2M", Samplers.schedulerLabel("dpm"))
        assertEquals("DPM++ 2M SDE Karras", Samplers.schedulerLabel("dpm_sde_karras"))
        assertEquals("DPM++ 3M SDE Exponential", Samplers.schedulerLabel("dpm3_sde_exponential"))
        assertEquals("DPM++ SDE (2x slower) Karras", Samplers.schedulerLabel("dpmpp_sde_karras"))
        assertEquals("dpmpp_sde" to "_karras", Samplers.splitScheduler("dpmpp_sde_karras"))
        assertEquals("Euler A", Samplers.schedulerLabel("euler_a"))
        assertEquals("LCM", Samplers.schedulerLabel("lcm"))
        assertEquals("ER-SDE", Samplers.schedulerLabel("er_sde"))
        assertEquals("DPM++ 2M", Samplers.schedulerLabel("dpm++2m"))
    }

    /** ⭐ SGM Uniform and Bong Tangent (backend 039): every DiT sampler has them, no SD sampler does. */
    @Test
    fun theDitModelsGetSgmUniformAndBongTangent() {
        assertTrue("euler_sgm_uniform" in Samplers.DIT_SCHEDULERS)
        assertTrue("euler_a_bong_tangent" in Samplers.DIT_SCHEDULERS)
        assertTrue(Samplers.SCHEDULERS.none { it.endsWith("_sgm_uniform") || it.endsWith("_bong_tangent") })
        assertEquals("euler_a" to "_bong_tangent", Samplers.splitScheduler("euler_a_bong_tangent"))
        // ⭐ GitHub #111: `_beta57` must not read as `_beta` + "57", nor `_beta` as the start of `_beta57`.
        assertEquals("euler" to "_beta57", Samplers.splitScheduler("euler_beta57"))
        assertEquals("euler" to "_beta", Samplers.splitScheduler("euler_beta"))
        assertEquals("euler" to "_radiance_shift", Samplers.splitScheduler("euler_radiance_shift"))
        assertEquals("heun" to "_linear_quadratic", Samplers.splitScheduler("heun_linear_quadratic"))
        assertEquals("dpm++2m_sde" to "_sgm_uniform", Samplers.splitScheduler("dpm++2m_sde_sgm_uniform"))
        assertEquals("Euler SGM Uniform", Samplers.schedulerLabel("euler_sgm_uniform"))
        assertEquals("lcm", Samplers.joinScheduler("lcm", "_sgm_uniform"))
        assertEquals("euler" to "_simple", Samplers.splitScheduler("euler_simple"))
        assertEquals("res_multistep" to "_beta", Samplers.splitScheduler("res_multistep_beta"))
        assertTrue(Samplers.SCHEDULERS.none { it.endsWith("_simple") || it.endsWith("_beta") })
    }

    /**
     * ⭐ The trimmed half of the mobile `theSpectralSamplerIsH3ImagesAlone`:
     * the parts that need no `Family`. The spectral sampler is its own name on
     * the wire and takes no schedule.
     */
    @Test
    fun theSpectralSamplerIsItsOwnWireValue() {
        assertEquals(Samplers.H3_SPECTRAL to "", Samplers.splitScheduler(Samplers.H3_SPECTRAL))
        assertEquals(Samplers.H3_SPECTRAL, Samplers.joinScheduler(Samplers.H3_SPECTRAL, "_karras"))
        assertEquals("Euler Spectral", Samplers.schedulerLabel(Samplers.H3_SPECTRAL))
        assertTrue(Samplers.H3_SPECTRAL in Samplers.DIT_SAMPLER_IDS)
        assertTrue(Samplers.H3_SPECTRAL !in Samplers.DIT_COMMON_SAMPLER_IDS)
    }

    /**
     * ⭐ The trimmed half of the mobile `ditFamiliesOfferTheEnginesSamplers`:
     * no `Family` here, so only the wire-level assertions survive.
     */
    @Test
    fun ditSamplersJoinAndSplitTheSameWay() {
        assertEquals("dpm++2m_karras", Samplers.joinScheduler("dpm++2m", "_karras"))
        assertEquals("er_sde" to "_exponential", Samplers.splitScheduler("er_sde_exponential"))
        assertEquals("ER-SDE Karras", Samplers.schedulerLabel("er_sde_karras"))
        assertEquals("lcm", Samplers.joinScheduler("lcm", "_karras"))
    }
}
