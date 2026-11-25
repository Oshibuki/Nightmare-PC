// Copied from app/src/main/java/com/abrah/nightmare/ModelCatalog.kt on 2026-11-26 (ADR-0001). Edit the desktop copy; never sync back.
package com.abrah.nightmare

/**
 * ⭐⭐ The sampler / schedule vocabulary, extracted from the mobile
 * `ModelCatalog` (see the provenance line above). This is an **excerpt**, not
 * the whole file: `ModelCatalog.kt` is Android-coupled at file level
 * (`android.content.Context`) and owns `Family`, `ModelSpec` and
 * `SelectedModel`, so only the declarations below -- the ones that are pure
 * data or pure logic over that data -- were lifted out. The receiver is called
 * [Samplers] instead of `ModelCatalog`; every declaration is otherwise
 * verbatim. What was left behind, and why, is recorded in
 * `desktop/docs/copied-files.md`.
 *
 * ⚠⚠ Why this matters on the desktop: the wire value is the ONLY thing that
 * reaches a backend, and the mobile backend's `Pipeline.hpp` matches a sampler
 * by name and **falls through to `dpm` in silence** on anything it does not
 * recognise. A command line that could emit `lcm_karras` would render with the
 * wrong sampler and report success, so the CLI validates against these tables
 * rather than keeping a list of its own.
 */
object Samplers {

    /**
     * ⭐⭐ The SD samplers, named the way every other SD tool names them (`local-dream`'s
     * picker: names plus a schedule). ⭐ DPM++ 3M and 3M SDE since backend 029 (GitHub #19:
     * "Pony Realism's creator recommends DPM++ 3M"): the solver's third order was there and
     * never selected; the SDE branch at that order is diffusers'.
     *
     * ⚠ The stored param is still ONE wire value ([joinScheduler]), so a saved workflow, a
     * plugin manifest and a bug report all carry what the backend receives.
     */
    val SAMPLERS: List<Pair<String, String>> = listOf(
        "dpm" to "DPM++ 2M",
        "dpm_sde" to "DPM++ 2M SDE",
        "dpm3" to "DPM++ 3M",
        "dpm3_sde" to "DPM++ 3M SDE",
        // ⭐ GitHub #121 (an SDXL model made for it): k-diffusion's single-step DPM++ SDE, backend 058. ⚠ Two model calls
        // a step but the last, so twice the time of the others, said in its name like Heun's.
        "dpmpp_sde" to "DPM++ SDE (2x slower)",
        "euler_a" to "Euler A",
        "euler" to "Euler",
        "lcm" to "LCM",
    )

    /**
     * ⭐⭐ The sigma SCHEDULES, as a suffix on the wire (`dpm3_sde_exponential`). "Exponential"
     * is what turbo / hyper SDXL checkpoints recommend (GitHub #19, 2026-10-14) -- a schedule,
     * like Karras, not a sampler; backend 029 runs it on DPM, Euler and Euler A.
     */
    val SCHEDULES: List<Pair<String, String>> = listOf(
        "" to "Normal",
        "_karras" to "Karras",
        "_exponential" to "Exponential",
    )

    /**
     * ⭐ The DiT engine's schedules: [SCHEDULES] plus SGM Uniform and Bong Tangent (testers' wishes, 2026-10-20, the
     * user's call: every DiT model). stable-diffusion.cpp already knows both names (`str_to_scheduler`); backend 039
     * passes the suffixes through. ⚠ Not on the SD samplers: their backend has neither. ⭐ Simple and Beta too
     * (ComfyUI's names, a tester's wish for MOSS-Image, 2026-10-22): the ABI 108 engine knows both, backend 043.
     */
    val DIT_SCHEDULES: List<Pair<String, String>> = SCHEDULES + listOf(
        "_sgm_uniform" to "SGM Uniform",
        "_bong_tangent" to "Bong Tangent",
        "_simple" to "Simple",
        "_beta" to "Beta",
        // ⭐ GitHub #111 (a reader's wish; the user's call 2026-11-14: schedules, no new samplers), engine `dit/097`, backend
        // 057: ComfyUI's Linear Quadratic (made for Mochi: half its steps near full noise, murky at 4 to 8 steps), RES4LYF's
        // Beta57 (beta with alpha 0.5, beta 0.7) and Radiance Shift. Sigmas match ComfyUI's Python to 5e-7.
        "_linear_quadratic" to "Linear Quadratic",
        "_beta57" to "Beta57",
        "_radiance_shift" to "Radiance Shift",
    )

    /**
     * ⭐ The DiT engine's samplers by its own names (stable-diffusion.cpp), the ones backend 029
     * passes through; anything else renders with Euler. Heun runs the model twice a step.
     */
    val DIT_SAMPLERS: List<Pair<String, String>> = listOf(
        "euler" to "Euler",
        "euler_a" to "Euler A",
        "heun" to "Heun (2x slower)",
        "dpm++2m" to "DPM++ 2M",
        "dpm++2m_sde" to "DPM++ 2M SDE",
        "er_sde" to "ER-SDE",
        "res_multistep" to "Res Multistep",
        "ipndm" to "iPNDM",
        "lcm" to "LCM",
        H3_SPECTRAL to "Euler Spectral",
    )

    /** Every DiT sampler name the app knows (to recognise and label a stored value). */
    val DIT_SAMPLER_IDS: List<String> = DIT_SAMPLERS.map { it.first }

    /**
     * ⭐ H3 Image's spectral sampler (engine `dit/102` + `dit/104`, the user's call 2026-11-22: "ship as an option"):
     * Euler, with the noise above a frequency cutoff re-drawn on the middle steps. The request carries it, so a switch
     * needs no relaunch. An engine before `dit/104` does not know the name and falls back to the model's default sampler.
     * No schedule of its own ([schedulesSupported]). Offered by the mobile `schedulersFor` for H3 Image only.
     */
    const val H3_SPECTRAL = "h3_spectral"

    /** The samplers every DiT family takes: all but the one family's own. */
    val DIT_COMMON_SAMPLER_IDS: List<String> = DIT_SAMPLER_IDS - H3_SPECTRAL
    const val DIT_DEFAULT_SAMPLER = "euler"

    /**
     * ⚠ LCM has no schedule of its own to change -- the backend's LCM scheduler ignores both
     * (upstream guards the same: `karrasSupported = baseId != "lcm"`), and DiT samplers take none.
     */
    fun schedulesSupported(base: String) = base != "lcm" && base != H3_SPECTRAL && (SAMPLERS.any { it.first == base } || base in DIT_SAMPLER_IDS)

    /** ⚠ Kept for its callers: Karras is one of the schedules. */
    fun karrasSupported(base: String) = schedulesSupported(base)

    /** `"dpm_sde_karras"` -> `("dpm_sde", "_karras")`; a DiT id comes back whole with `""`. */
    fun splitScheduler(value: String): Pair<String, String> {
        if (value in DIT_SAMPLER_IDS && SAMPLERS.none { it.first == value }) return value to ""
        val sfx = DIT_SCHEDULES.map { it.first }.filter { it.isNotEmpty() }.firstOrNull { value.endsWith(it) } ?: ""
        val base = value.removeSuffix(sfx)
        // ⚠ An unrecognised base falls back to the default rather than being
        // passed through: the backend would silently treat it as `dpm`, and a
        // picker showing nothing selected is how that stays invisible.
        return if (SAMPLERS.any { it.first == base } || base in DIT_SAMPLER_IDS) base to sfx
        else DEFAULT_SCHEDULER to ""
    }

    /** The inverse. ⚠ Drops the schedule where the sampler has none. */
    fun joinScheduler(base: String, schedule: String): String =
        if (schedule.isNotEmpty() && schedulesSupported(base)) base + schedule else base

    /** ⚠ The old Karras-flag form, for its callers. */
    fun joinScheduler(base: String, karras: Boolean): String = joinScheduler(base, if (karras) "_karras" else "")

    /** ⭐ A sampler id's name, without its schedule (`dpm3` -> "DPM++ 3M"); the Results filter's sampler list (#117). */
    fun samplerName(base: String): String =
        SAMPLERS.firstOrNull { it.first == base }?.second ?: DIT_SAMPLERS.firstOrNull { it.first == base }?.second ?: base

    /** How a wire value should read on screen, e.g. `"DPM++ 2M SDE Karras"`. */
    fun schedulerLabel(value: String): String {
        if (value in DIT_SAMPLER_IDS) DIT_SAMPLERS.firstOrNull { it.first == value }?.let {
            if (SAMPLERS.none { s -> s.first == value }) return it.second
        }
        val (base, sfx) = splitScheduler(value)
        val name = SAMPLERS.firstOrNull { it.first == base }?.second
            ?: DIT_SAMPLERS.firstOrNull { it.first == base }?.second ?: base
        val sched = DIT_SCHEDULES.firstOrNull { it.first == sfx && sfx.isNotEmpty() }?.second
        return if (sched != null) "$name $sched" else name
    }

    /**
     * ⚠⚠ **The backend's own default** -- `RequestParser.hpp` reads
     * `json.value("scheduler", "dpm")`. Named here so the app's default and the
     * backend's cannot drift: before the `scheduler` field was sent at all,
     * every render in this app silently used this value, including checkpoints
     * whose author specified something else.
     */
    const val DEFAULT_SCHEDULER = "dpm"

    /**
     * ⚠ The sampler node's own defaults, named here because `ModelSpec` carries
     * per-checkpoint overrides of them and the two must agree. 20/7.5 is
     * DreamUI's default too.
     *
     * ⚠ The bounds are the widget's, and a `config.json` value outside them is
     * clamped rather than refused -- an author who writes 60 steps meant "a lot",
     * and the slider cannot express it.
     */
    const val DEFAULT_STEPS = 20
    const val DEFAULT_CFG = 7.5
    val STEPS_RANGE = 1..50
    val CFG_RANGE = 1.0..20.0

    /**
     * ⭐⭐ The samplers the backend implements, and the ONLY legal values of a
     * `scheduler` param.
     *
     * ⚠⚠ **Mirrors `backend-src/src/Pipeline.hpp`'s chain of comparisons, and
     * an unknown string there falls through to `dpm` in silence** -- it is not
     * rejected. So a free-text field would let a typo render with the wrong
     * sampler and report success, which is why the widget carries these as
     * chips rather than as text.
     *
     * ⚠ Order is deliberate: the default first, then the ancestral and
     * Karras variants, then `lcm`. It is the order the chips are drawn in.
     */
    val SCHEDULERS: List<String> by lazy {
        SAMPLERS.flatMap { (id, _) -> SCHEDULES.map { (sfx, _) -> joinScheduler(id, sfx) } }.distinct()
    }

    /** ⭐ The DiT samplers, each with the schedules ([DIT_SCHEDULES]) the engine takes since ABI 108. */
    val DIT_SCHEDULERS: List<String> by lazy {
        DIT_COMMON_SAMPLER_IDS.flatMap { id -> DIT_SCHEDULES.map { (sfx, _) -> joinScheduler(id, sfx) } }.distinct()
    }
}
