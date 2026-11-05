package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main

/**
 * Special-build scene debug logging. Each subsystem has its own boolean flag —
 * flip to `false` to silence it. [ENABLED] = `false`
 * silences everything for a release build.
 *
 * The scene system's high-value diagnostics (entry transitions, resets,
 * setDead) stay ON during development; the noisy per-tick traces are OFF.
 * New debug logging added during development should go behind a named flag so
 * it can be silenced without deleting the instrumentation.
 */
object SceneDebug {
    const val ENABLED = true

    // Off by default: verified machinery (spams heavily)
    const val ACTIONS = false      // every setCurrentAction call
    const val AI_TICK = false      // per-tick updateAITasks during scenes
    const val HEARTBEAT = false    // per-tick onUpdate during scenes
    const val PACKETS = false      // ChangeDataParameter/KoboldStatePacket/SendGirlToSex
    const val MOVEMENT = false     // movement lock transitions
    const val IN_HAND = false      // InHandMapRenderer reflection (per frame)

    // On by default: scene flow still under verification
    const val SCENE_ENTRY = true   // doAction, startActionFromHandState(), sound-listener transitions, handleAnalState
    const val SITTING = true       // kobold isSitting lerp
    const val RESET = true         // ResetGirlPacket + resetCameraAndPhysics
    const val SET_DEAD = true      // any girl setDead (vanishing safety net)
    const val RSHIFT = true        // leave-scene keybind
    const val CLOTHING = false     // clothing screen folder open

    @JvmStatic
    fun log(fmt: String, vararg args: Any?) {
        if (ENABLED) {
            Main.LOGGER.info("[sexmod-scene] " + String.format(fmt, *args))
        }
    }

    @JvmStatic
    fun log(flag: Boolean, fmt: String, vararg args: Any?) {
        if (ENABLED && flag) {
            Main.LOGGER.info("[sexmod-scene] " + String.format(fmt, *args))
        }
    }
}
