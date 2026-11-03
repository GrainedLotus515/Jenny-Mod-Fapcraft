package com.trolmastercard.sexmod.entity.ai

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * <b>Role.</b> Simplified follow goal for the Bee NPC — follows the master
 * (walking, no combat/ride states) and raises her air-jump factor with
 * distance so she can hop terrain. Only FOLLOW/IDLE states are used.
 *
 * Port note: 1.12 `jumpMovementFactor` is [BaseGirlEntity.jumpMovementFactor]
 * (returned by the girl's `getFlyingSpeed()`).
 */
open class GirlGotoGoal(girl: BaseGirlEntity) : GirlFollowAiBase(girl) {
    @JvmField
    var gotoTicks: Int = 0

    @JvmField
    var retryTicks: Int = 0

    override fun stop() {
        super.stop()
        this.girl.jumpMovementFactor = 0.02f
    }

    override fun getCurrentState(): GirlFollowAiBaseState {
        val dist = this.girl.distanceTo(this.master!!) // java-npe
        var tooFar = dist > 5.0f
        if (this.girl.getInteractionPlayerUUID() == null && !tooFar && this.state == GirlFollowAiBaseState.FOLLOW) {
            if (++this.gotoTicks > 60) {
                tooFar = false
                this.gotoTicks = 0
            } else {
                tooFar = true
            }
        }

        return if (tooFar) GirlFollowAiBaseState.FOLLOW else GirlFollowAiBaseState.IDLE
    }

    override fun setState(state: GirlFollowAiBaseState) {
        when (state) {
            GirlFollowAiBaseState.FOLLOW -> {
                val master = this.master!! // java-npe
                val navigator = this.navigator!! // java-npe
                val dist = this.girl.distanceTo(master).toDouble()
                // 1.12 PathNavigate.getPathSearchRange() = (float) FOLLOW_RANGE
                if (this.girl.getAttributeValue(Attributes.FOLLOW_RANGE).toFloat() > dist) {
                    navigator.stop()
                    navigator.moveTo(master, 0.5)
                } else {
                    this.updateNavigation()
                }

                this.retryTicks = 300
                this.getFollowDistance()
            }
            GirlFollowAiBaseState.IDLE -> this.getFollowDistance()
            else -> {}
        }
    }

    /**
     * Scales the girl's jump-movement factor with the distance to the master
     * (0.02 + up to 0.07) so closer follows are more stable.
     */
    override fun getFollowDistance(): Double {
        val dist = this.girl.distanceTo(this.master!!) // java-npe
        val speedMod = Math.min(0.7, Math.floor((dist / 3.0f).toDouble()) * 0.05)
        val jumpFactor = (0.02f + speedMod).toFloat()
        this.girl.jumpMovementFactor = jumpFactor
        return jumpFactor.toDouble()
    }
}
