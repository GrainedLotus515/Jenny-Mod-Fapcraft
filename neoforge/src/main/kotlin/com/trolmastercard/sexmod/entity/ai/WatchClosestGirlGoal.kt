package com.trolmastercard.sexmod.entity.ai

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.InteractGoal

/**
 * <b>Role.</b> Vanilla "watch closest player" goal (1.12 `EntityAIWatchClosest2`,
 * 1.21 [InteractGoal]) with a software kill switch: [isWatching] is set false
 * by the follow goals while the girl is actively following/attacking, so she
 * stops staring at the player.
 */
open class WatchClosestGirlGoal(entity: Mob, watchedClass: Class<out LivingEntity>, radius: Float, maxDist: Float) :
    InteractGoal(entity, watchedClass, radius, maxDist) {
    @JvmField
    var isWatching: Boolean = true

    override fun tick() {
        if (this.isWatching) {
            super.tick()
        }
    }
}
