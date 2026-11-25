package com.abrah.nightmare.desktop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vocabulary a `txt2img` command line may emit, checked against an
 * independent copy of the engine's own arrays.
 *
 * ⚠⚠ The oracle below is transcribed from the vendored engine's source, not
 * computed from [SamplerWire]: `sample_method_to_str[]` and `scheduler_to_str[]`
 * in `desktop/engine/sdcpp/src/stable-diffusion.cpp` (lines 67 and 110) at the
 * SHA pinned in `desktop/engine/ENGINES.md`. The engine parses both with
 * `strcmp` and returns `*_COUNT` on a miss, so a value outside these two lists
 * is a value the engine will refuse -- there is no fuzzy matching to save it.
 *
 * ⚠ That is also why the CLI cannot simply take "any string": a name the engine
 * does not know is a hard error at run time, after the model has loaded.
 */
class SamplerWireTest {

    /** `sample_method_to_str[]`, verbatim. */
    private val engineSamplers = listOf(
        "euler", "euler_a", "heun", "dpm2", "dpm++2s_a", "dpm++2m", "dpm++2mv2",
        "ipndm", "ipndm_v", "lcm", "ddim_trailing", "tcd", "res_multistep",
        "res_2s", "er_sde", "euler_cfg_pp", "euler_a_cfg_pp", "euler_ge",
        "dpm++2m_sde", "dpm++2m_sde_bt", "lms",
    )

    /** `scheduler_to_str[]`, verbatim. `str_to_scheduler` also aliases "normal" to "discrete". */
    private val engineSchedulers = listOf(
        "discrete", "karras", "exponential", "ays", "gits", "sgm_uniform",
        "simple", "smoothstep", "kl_optimal", "lcm", "bong_tangent", "ltx2",
        "logit_normal", "flux2", "flux", "beta",
    )

    @Test
    fun everyAcceptedSamplerIsOneTheEngineKnows() {
        assertTrue(SamplerWire.SAMPLERS.isNotEmpty())
        for (v in SamplerWire.SAMPLERS) {
            assertTrue("'$v' is not a sample_method_to_str[] value", v in engineSamplers)
            assertTrue("'$v' should validate", SamplerWire.isSampler(v))
        }
    }

    @Test
    fun everyAcceptedSchedulerIsOneTheEngineKnows() {
        assertTrue(SamplerWire.SCHEDULERS.isNotEmpty())
        for (v in SamplerWire.SCHEDULERS) {
            assertTrue("'$v' is not a scheduler_to_str[] value", v in engineSchedulers)
            assertTrue("'$v' should validate", SamplerWire.isScheduler(v))
        }
    }

    /** The defaults the command line documents, and the first chip in each list. */
    @Test
    fun theDefaultsAreTheEngineNamesTheCliDocuments() {
        assertEquals("euler", SamplerWire.DEFAULT_SAMPLER)
        assertEquals("discrete", SamplerWire.DEFAULT_SCHEDULER)
        assertEquals(SamplerWire.DEFAULT_SAMPLER, SamplerWire.SAMPLERS.first())
        assertEquals(SamplerWire.DEFAULT_SCHEDULER, SamplerWire.SCHEDULERS.first())
        assertTrue(SamplerWire.isSampler(SamplerWire.DEFAULT_SAMPLER))
        assertTrue(SamplerWire.isScheduler(SamplerWire.DEFAULT_SCHEDULER))
    }

    /**
     * ⚠⚠ Names that exist on ONE side of the fence and not the other. The mobile
     * backend's ids (`dpm`, `dpm_sde`, ...) are its own spelling, not the
     * engine's; `h3_spectral` and the fork's newer schedules are the DiT
     * engine's, and this CLI drives the SD engine, whose arrays are the oracle
     * above. Accepting any of them would be a value the engine refuses.
     */
    @Test
    fun namesFromTheOtherVocabularyAreRejected() {
        val rejected = listOf(
            // the mobile backend's SD ids
            "dpm", "dpm_sde", "dpm3", "dpm3_sde", "dpmpp_sde",
            // the DiT engine's own
            "h3_spectral",
            // anything invented
            "unipc", "euler_a_karras", "", "Euler", "EULER_A",
        )
        for (v in rejected) {
            assertFalse("'$v' must not validate as a sampler", SamplerWire.isSampler(v))
        }
        val rejectedSchedulers = listOf(
            // fork-only schedules: this engine's scheduler_to_str[] has neither
            "linear_quadratic", "beta57", "radiance_shift",
            // the suffix spelling belongs to the app's wire values, not the engine's
            "_karras", "_exponential", "normal", "",
        )
        for (v in rejectedSchedulers) {
            assertFalse("'$v' must not validate as a scheduler", SamplerWire.isScheduler(v))
        }
    }

    /** The app's names, so metadata reads like every other SD tool's. */
    @Test
    fun labelsComeFromTheCopiedTable() {
        assertEquals("Euler", SamplerWire.samplerLabel("euler"))
        assertEquals("Euler A", SamplerWire.samplerLabel("euler_a"))
        assertEquals("DPM++ 2M", SamplerWire.samplerLabel("dpm++2m"))
        assertEquals("ER-SDE", SamplerWire.samplerLabel("er_sde"))
        assertEquals("LCM", SamplerWire.samplerLabel("lcm"))
        assertEquals("Normal", SamplerWire.schedulerLabel("discrete"))
        assertEquals("Karras", SamplerWire.schedulerLabel("karras"))
        assertEquals("Exponential", SamplerWire.schedulerLabel("exponential"))
        assertEquals("SGM Uniform", SamplerWire.schedulerLabel("sgm_uniform"))
        assertEquals("Bong Tangent", SamplerWire.schedulerLabel("bong_tangent"))
    }
}
