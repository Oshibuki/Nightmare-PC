package com.abrah.nightmare.desktop.core

import com.abrah.nightmare.Samplers

/**
 * The sampler vocabulary a `txt2img` command line may emit, expressed in the
 * **engine's** spelling.
 *
 * ⚠⚠ Not copied from the Android app -- desktop-only glue -- but every name in
 * it comes from [Samplers], the copied table, filtered down to what the vendored
 * engine actually accepts. The two vocabularies are close but not identical:
 *
 * - `--sampling-method` takes `sample_method_to_str[]` values, and the app's own
 *   list of those is [Samplers.DIT_COMMON_SAMPLER_IDS] (the DiT engine's names,
 *   which are stable-diffusion.cpp's). `h3_spectral` is excluded: it is the
 *   fork's DiT-only name and is not in the SD engine's array.
 * - `--scheduler` takes `scheduler_to_str[]` values, and the app spells a
 *   schedule as a **suffix** on a sampler ([Samplers.SCHEDULES]). The engine
 *   spells it as its own word, so the suffix loses its underscore and the empty
 *   suffix -- the app's "Normal" -- becomes `discrete`, the engine's first entry.
 *   The fork's `_linear_quadratic`, `_beta57` and `_radiance_shift` drop out
 *   here: this engine's `scheduler_to_str[]` has none of them.
 *
 * The filter is [ENGINE_SAMPLERS]/[ENGINE_SCHEDULERS], a transcription of the
 * engine's two arrays at the pinned SHA (`desktop/engine/ENGINES.md`). It is
 * pinned by `SamplerWireTest`, so widening it is a deliberate act rather than a
 * typo. A name outside them is not "unsupported" -- `str_to_sample_method` and
 * `str_to_scheduler` return `*_COUNT` and the engine errors out, after the
 * model has loaded, which is exactly the ten-minute mistake a CLI can prevent.
 */
object SamplerWire {

    /** `sample_method_to_str[]` in `desktop/engine/sdcpp/src/stable-diffusion.cpp`. */
    private val ENGINE_SAMPLERS: Set<String> = setOf(
        "euler", "euler_a", "heun", "dpm2", "dpm++2s_a", "dpm++2m", "dpm++2mv2",
        "ipndm", "ipndm_v", "lcm", "ddim_trailing", "tcd", "res_multistep",
        "res_2s", "er_sde", "euler_cfg_pp", "euler_a_cfg_pp", "euler_ge",
        "dpm++2m_sde", "dpm++2m_sde_bt", "lms",
    )

    /** `scheduler_to_str[]` in the same file. `str_to_scheduler` also aliases "normal" to "discrete". */
    private val ENGINE_SCHEDULERS: Set<String> = setOf(
        "discrete", "karras", "exponential", "ays", "gits", "sgm_uniform",
        "simple", "smoothstep", "kl_optimal", "lcm", "bong_tangent", "ltx2",
        "logit_normal", "flux2", "flux", "beta",
    )

    /**
     * The engine's name for the app's "no schedule" (`SCHEDULES`' empty suffix).
     * The engine's own alias, `normal`, is deliberately NOT accepted: one name
     * per value keeps the metadata unambiguous.
     */
    private const val DISCRETE = "discrete"

    /**
     * `--sampling-method` values, in the copied table's order.
     *
     * ⚠ The SD engine's own default is `euler_a`, but this CLI always passes the
     * flag explicitly, so the documented default is ours to choose: [DEFAULT_SAMPLER].
     */
    val SAMPLERS: List<String> = Samplers.DIT_COMMON_SAMPLER_IDS.filter { it in ENGINE_SAMPLERS }

    /** `--scheduler` values, in the copied table's order, `discrete` first. */
    val SCHEDULERS: List<String> =
        Samplers.DIT_SCHEDULES
            .map { (suffix, _) -> if (suffix.isEmpty()) DISCRETE else suffix.removePrefix("_") }
            .filter { it in ENGINE_SCHEDULERS }
            .distinct()

    const val DEFAULT_SAMPLER: String = "euler"
    const val DEFAULT_SCHEDULER: String = DISCRETE

    fun isSampler(value: String): Boolean = value in SAMPLERS

    fun isScheduler(value: String): Boolean = value in SCHEDULERS

    /** `dpm++2m` -> "DPM++ 2M", so the PNG metadata reads like every other SD tool's. */
    fun samplerLabel(value: String): String = Samplers.samplerName(value)

    /** `discrete` -> "Normal", `karras` -> "Karras"; the app's label for the engine's word. */
    fun schedulerLabel(value: String): String {
        val suffix = if (value == DISCRETE) "" else "_$value"
        return Samplers.DIT_SCHEDULES.firstOrNull { it.first == suffix }?.second ?: value
    }
}
