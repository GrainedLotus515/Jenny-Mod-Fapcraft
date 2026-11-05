package com.trolmastercard.sexmod.entity.ai

import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.ManglelieEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The Manglelie player-avoidance goal — flees from nearby
 * players, but only while no Galath "mommy" with a master is within
 * `distance` blocks (a mommied Mang never runs away).
 *
 * <b>Pitfalls.</b> Also sets the synced "scared" flag
 * ([ManglelieEntity.SCARED], 1.12 key 115) while executing so the movement
 * animation switches to the scared run.
 */
open class AvoidPlayerGoal(manglelie: ManglelieEntity, dist: Float, farSpeed: Double, nearSpeed: Double) :
    AvoidEntityGoal<Player>(manglelie, Player::class.java, dist, farSpeed, nearSpeed) {
    @JvmField
    val manglelieEntity: ManglelieEntity = manglelie

    @JvmField
    val distance: Float = dist

    /**
     * True while the Mang is bound to a corrupting Galath (never flees then)
     * or no unbound master-Galath is near enough to protect her.
     */
    open fun shouldAvoid(): Boolean {
        if (this.manglelieEntity.getCorruptPlayerUUID() != null) {
            return true
        }

        val pos = this.manglelieEntity.blockPosition()
        val offset = BlockPos.containing(this.distance.toDouble(), this.distance.toDouble(), this.distance.toDouble())

        for (galath in this.manglelieEntity.level().getEntitiesOfClass(
            GalathEntity::class.java,
            AABB(Vec3.atLowerCornerOf(pos.subtract(offset)), Vec3.atLowerCornerOf(pos.offset(offset))),
        )) {
            if (!galath.level().isClientSide && !galath.isRemoved && galath.hasMaster()) {
                return true
            }
        }

        return false
    }

    override fun canUse(): Boolean {
        return if (this.shouldAvoid()) false else super.canUse()
    }

    override fun canContinueToUse(): Boolean {
        return if (this.shouldAvoid()) false else super.canContinueToUse()
    }

    override fun start() {
        this.manglelieEntity.getEntityData().set(ManglelieEntity.SCARED, true)
        super.start()
    }

    override fun stop() {
        this.manglelieEntity.getEntityData().set(ManglelieEntity.SCARED, false)
        super.stop()
    }
}
