package com.trolmastercard.sexmod.entity.ai

import com.trolmastercard.sexmod.entity.KoboldEntity
import java.util.function.Predicate
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal

/**
 * <b>Role.</b> Nearest-target goal that only attacks tamed kobolds
 * (`hasMaster()`), with an optional darkness requirement
 * (`onlyAtNight`) and a 1-in-`targetChance` activation gate. Used
 * for hostile mobs that hunt kobold tribe members.
 *
 * Port notes: 1.12 `taskOwner.getBrightness()` is
 * `getLightLevelDependentMagicValue()`; the 1.12 entity selector + distance
 * sorter are the goal's `targetConditions` and a distance sort.
 */
open class NearestAttackableGirlGoal(
    creature: Mob,
    private val targetChance: Int,
    checkSight: Boolean,
    onlyNearby: Boolean,
    targetSelector: Predicate<LivingEntity>?,
    private val onlyAtNight: Boolean,
) : NearestAttackableTargetGoal<KoboldEntity>(creature, KoboldEntity::class.java, targetChance, checkSight, onlyNearby, targetSelector) {
    constructor(creature: Mob, mustSee: Boolean, nearbyOnly: Boolean) :
        this(creature, 10, mustSee, nearbyOnly, null, false)

    constructor(creature: Mob, mustSee: Boolean, nearbyOnly: Boolean, onlyAtNight: Boolean) :
        this(creature, 10, mustSee, nearbyOnly, null, onlyAtNight)

    /**
     * Filters candidates: skips daylight when `onlyAtNight` is set, applies
     * the 1-in-`targetChance` random gate, then picks the nearest kobold
     * with a master.
     */
    override fun canUse(): Boolean {
        if (this.onlyAtNight) {
            val brightness = this.mob.lightLevelDependentMagicValue
            if (brightness >= 0.5f) {
                return false
            }
        }

        if (this.targetChance > 0 && this.mob.random.nextInt(this.targetChance) != 0) {
            return false
        }

        val candidates = this.mob.level().getEntitiesOfClass(this.targetType, this.getTargetSearchArea(this.followDistance)) { entity ->
            this.targetConditions.test(this.mob, entity)
        }
        if (candidates.isEmpty()) {
            return false
        }

        val masters = ArrayList<KoboldEntity>()

        for (kobold in candidates) {
            if (kobold.hasMaster()) {
                masters.add(kobold)
            }
        }

        if (masters.isEmpty()) {
            return false
        }

        masters.sortWith(Comparator.comparingDouble { kobold -> this.mob.distanceToSqr(kobold) })
        this.target = masters[0]
        return true
    }
}
