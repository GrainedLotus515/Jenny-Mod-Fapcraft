package com.trolmastercard.sexmod

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.Guardian
import net.minecraft.world.entity.monster.ZombifiedPiglin
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Static predicate helpers used by the mod's mob AI (e.g. targeting and
 * daylight checks for entities like the slime/goblin NPCs). Pure functions —
 * no state, no side effects; safe to call from any thread.
 */
object MobPredicates {
    /**
     * Whether `target` is an acceptable combat target. Creepers, zombie
     * pigmen (zombified piglins), guardians and endermen are excluded
     * (explosive/reflection hazards).
     *
     * @return `false` for the excluded vanilla mobs, `true` otherwise
     */
    @JvmStatic
    fun isValidTarget(target: Entity?): Boolean {
        return if (target is Creeper) {
            false
        } else if (target is ZombifiedPiglin) {
            false
        } else {
            if (target is Guardian) false else target !is EnderMan
        }
    }

    /**
     * Checks whether the line of sight from `from` to the entity's eye
     * position is unobstructed (no block hit), i.e. the entity is "in daylight".
     * 1.12 `rayTraceBlocks(from, to, stopOnLiquid=true, ignoreNoBox=true, false)`
     * maps to a COLLIDER / any-fluid clip.
     *
     * @return `true` if no block occludes the ray, `false` otherwise
     */
    @JvmStatic
    fun isDaylight(world: Level, from: Vec3, entity: Entity): Boolean {
        val result = world.clip(
            ClipContext(from, entity.position().add(0.0, entity.eyeHeight.toDouble(), 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, entity)
        )
        return result.type != HitResult.Type.BLOCK
    }
}
