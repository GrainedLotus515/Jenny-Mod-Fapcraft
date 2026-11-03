package com.trolmastercard.sexmod.client.model

import it.unimi.dsi.fastutil.doubles.Double2DoubleFunction
import java.util.function.Function
import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.EasingType
import software.bernie.geckolib.animation.state.BoneSnapshot
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib [AnimationController] subclass used by all girl models. The
 * animation predicates registered through these controllers are where scene
 * transitions happen (see `registerControllers` in the girl entities and
 * the `animationPredicate` switch on `Action`).
 *
 * COMMON class (PORTING.md §2.3): it only mentions GeckoLib types, which load
 * on both sides. Besides the GL3-style constructors it re-exposes the GL3
 * controller fields the mod mutated directly (`transitionLengthTicks`,
 * `tickOffset`, `getCurrentAnimation().animationName/animationLength`) and
 * records the seek time GeckoLib hands to [process] in [lastSeekTime]; that
 * replaces GL3's `AnimationData.tick` (`Action.getActionTick`).
 */
open class GirlAnimationController<T : GeoAnimatable>(
    animatable: T,
    name: String,
    transitionTicks: Int,
    handler: AnimationStateHandler<T>,
) : AnimationController<T>(animatable, name, transitionTicks, handler) {
    /** Seek time (ticks incl. partial tick) of the last [process] call; GL3 `AnimationData.tick`. */
    @JvmField
    var lastSeekTime = 0.0

    /** GL3 `GirlAnimationController(animatable, name, ticks, EasingType, predicate)`. */
    constructor(
        animatable: T,
        name: String,
        transitionTicks: Int,
        easingType: EasingType,
        handler: AnimationStateHandler<T>,
    ) : this(animatable, name, transitionTicks, handler) {
        this.setOverrideEasingType(easingType)
    }

    /**
     * GL3 `GirlAnimationController(animatable, name, ticks, Function<Double, Double> easing, predicate)`.
     * The easing function maps transition progress (0..1) to eased progress,
     * which is what a GL4 [EasingType] transformer does.
     */
    constructor(
        animatable: T,
        name: String,
        transitionTicks: Int,
        easingFunction: Function<Double, Double>,
        handler: AnimationStateHandler<T>,
    ) : this(animatable, name, transitionTicks, handler) {
        this.setOverrideEasingType(EasingType { _ -> Double2DoubleFunction { t -> easingFunction.apply(t) } })
    }

    fun getTransitionLengthTicks(): Double = transitionLength

    fun setTransitionLengthTicks(ticks: Double) {
        transitionLength = ticks
    }

    fun getTickOffset(): Double = tickOffset

    fun setTickOffset(v: Double) {
        tickOffset = v
    }

    /** GL3 `registerSoundListener`; alias of [setSoundKeyframeHandler]. */
    fun registerSoundListener(h: SoundKeyframeHandler<T>) {
        setSoundKeyframeHandler(h)
    }

    /** GL3 `clearAnimationCache`; alias of [forceAnimationReset]. */
    fun clearAnimationCache() = forceAnimationReset()

    /** GL3 `getCurrentAnimation().animationName`. */
    fun getCurrentAnimationName(): String? = getCurrentAnimation()?.animation()?.name()

    /** GL3 `getCurrentAnimation().animationLength` (ticks). */
    fun getCurrentAnimationLength(): Double = getCurrentAnimation()?.animation()?.length() ?: 0.0

    override fun process(
        model: GeoModel<T>,
        state: AnimationState<T>,
        bones: Map<String, GeoBone>,
        snapshots: Map<String, BoneSnapshot>,
        seekTime: Double,
        crash: Boolean,
    ) {
        lastSeekTime = seekTime
        super.process(model, state, bones, snapshots, seekTime, crash)
    }
}
