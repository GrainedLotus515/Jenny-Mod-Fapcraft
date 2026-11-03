package com.trolmastercard.sexmod.util

import software.bernie.geckolib.loading.math.MathParser

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
    @JvmStatic
    fun set(name: String, value: Double) {
        MathParser.setVariable(name) { value }
    }
}
