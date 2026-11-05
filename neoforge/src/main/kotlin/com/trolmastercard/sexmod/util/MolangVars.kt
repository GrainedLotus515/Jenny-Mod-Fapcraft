package com.trolmastercard.sexmod.util

import software.bernie.geckolib.loading.math.MathParser
import software.bernie.geckolib.loading.math.MolangQueries
import software.bernie.geckolib.loading.math.value.Variable

/**
 * Custom Molang variables used by the girl animations (`size`, `pitch`,
 * `armpitch`, `armyaw`, `yaw`).
 *
 * Replaces GeckoLib 3's `GeckoLibCache.getInstance().parser.setValue(name, value)`:
 * GeckoLib 4 resolves variables through [MathParser.setVariable], which stores
 * a supplier; the value is captured at call time, so callers set it right
 * before the model is animated, exactly as they did in GL3.
 */
object MolangVars {
    /**
     * Every free identifier the animation files use. GeckoLib 4 only accepts variables that
     * are registered before the animations are parsed (GL3 auto-registered unknown names as
     * 0). Besides the real variables, Blockbench exports leaked easing names and `NaN` into
     * some keyframe expressions; GL3 evaluated those as 0, so they are registered as 0 too.
     */
    private val ANIMATION_VARIABLES = listOf(
        "size", "scale", "pitch", "yaw", "armpitch", "armyaw",
        "nan", "linear", "easeinsine", "easeoutsine", "easeinoutsine", "easeinoutquad",
        "easeinoutcubic", "easeinoutback", "easeoutelastic",
    )

    /** Registers [ANIMATION_VARIABLES]; must run before GeckoLib's first resource reload. */
    @JvmStatic
    fun registerAll() {
        for (name in ANIMATION_VARIABLES) {
            if (!MolangQueries.isExistingVariable(name)) {
                MathParser.registerVariable(Variable(name, 0.0))
            }
        }
    }

    @JvmStatic
    fun set(name: String, value: Double) {
        MathParser.setVariable(name) { value }
    }
}
