package com.trolmastercard.sexmod.entity

import com.mojang.datafixers.util.Pair
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.animation.AnimationState

/**
 * Randomised animation-variant bookkeeping for the client animation
 * controllers (picks a numbered variant of `animName` and remembers it in
 * the girl's `animationVariantMap`).
 *
 * Extracted verbatim from [BaseGirlEntity]; the entity keeps its
 * instance methods as delegates. Movement/action checks are called
 * back through the girl so subclass overrides still dispatch.
 *
 * Port notes: GL3 `AnimationBuilder.addAnimation(n, LOOP)` is
 * `RawAnimation.begin().thenLoop(n)`; `transitionLengthTicks = 0` is
 * `transitionLength(0)`. The client-only realms `Pair` is replaced by
 * DataFixerUpper's common `Pair` (`getFirst()`/`getSecond()`).
 */
object GirlAnimationVariants {
    @JvmStatic
    fun playRandomizedAnimation(girl: BaseGirlEntity, animName: String, maxVariants: Int, chance: Float, event: AnimationState<*>, force: Boolean) {
        if (force ||
            !Action.isActionComplete(girl, event.partialTick) ||
            !girl.handleActionAnimationOverrides(girl.getCurrentAction(), animName, HandlePlayerMovement.isJumping, event)
        ) {
            val controller = event.controller
            var variants: Pair<Int, Int>? = girl.animationVariantMap[animName]
            if (variants == null) {
                variants = Pair.of(0, 0)
            }

            val baseVariant: Int = variants!!.first
            val maxVariant: Int = variants.second
            if (!Action.isActionComplete(girl, event.partialTick)) {
                event.controller.setAnimation(RawAnimation.begin().thenLoop(if (baseVariant == 0) animName else animName + baseVariant))
                event.controller.transitionLength(0)
            } else {
                val variant = pickRandomVariant(girl, baseVariant, maxVariant, maxVariants, chance)
                val current = controller
                val selectedController = current
                @Suppress("UNUSED_VARIABLE")
                val tempAnimName: String = if (variant == 0) animName else animName + variant

                // jar-faithful: the picked variant name is computed but the base name is played
                selectedController.setAnimation(RawAnimation.begin().thenLoop(animName))
                controller.transitionLength(0)
                val variantsMap = girl.animationVariantMap
                val newValue: Int = variant
                val newName = animName
                val maxVar: Int = if (variant == 0) maxVariant else variant

                variantsMap[newName] = Pair.of(newValue, maxVar)
            }
        }
    }

    @JvmStatic
    fun pickRandomVariant(girl: BaseGirlEntity, baseVariant: Int, exclude: Int, max: Int, chance: Float): Int {
        if (baseVariant != 0) {
            return 0
        }

        val random = girl.random
        if (random.nextFloat() > chance) {
            return 0
        }

        var variant: Int
        do {
            variant = random.nextInt(max)
        } while ((variant == exclude || variant == 0) && max > 2)

        return variant
    }
}
