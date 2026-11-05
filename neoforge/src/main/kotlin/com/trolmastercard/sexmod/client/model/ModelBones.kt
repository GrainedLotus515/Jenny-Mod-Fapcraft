package com.trolmastercard.sexmod.client.model

import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * **Role.** Null-safe bone access for the GeckoLib models.
 *
 * **Pitfall.** [AnimationProcessor.getBone] returns `null`
 * for a bone the model does not have - a model that was never bound to a
 * renderer, an older geo file without a newer bone, or (the case that crashed
 * the game) `GalathNpcModel` passing its own processor into the
 * Manglelie pose pass, which looks up Manglelie-only bones. Every lookup in
 * this package goes through here so a missing bone degrades to a no-op instead
 * of a render-thread crash.
 */
object ModelBones {
    /** @return the bone, or `null` when the processor/model does not have it */
    @JvmStatic
    fun get(processor: AnimationProcessor<*>?, name: String): GeoBone? {
        return processor?.getBone(name)
    }

    /** Hides/unhides `name`; does nothing when the bone is absent. */
    @JvmStatic
    fun setHidden(processor: AnimationProcessor<*>?, name: String, hidden: Boolean) {
        val bone = get(processor, name)
        bone?.setHidden(hidden)
    }
}
