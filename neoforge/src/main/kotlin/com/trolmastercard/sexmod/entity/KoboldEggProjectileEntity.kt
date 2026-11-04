package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.registry.ModEntities
import net.minecraft.advancements.CriteriaTriggers
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ThrownEnderpearl
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EndGatewayBlock
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityTeleportEvent

/**
 * <b>Role.</b> The ender-pearl projectile thrown by kobold girls
 * ([Action.THROW_PEARL]). Like a vanilla pearl it teleports the thrower
 * to the impact point — but only if the impact is within 5 blocks of the
 * girl's home position (pearls near home), with end-gateway support.
 *
 * <b>Pitfalls.</b> The thrower is expected to be a [BaseGirlEntity]
 * (cast directly). The inner class [EnderTeleportHandler] clears the girl's active pearl
 * and resets her scene state on every ender-teleport event.
 *
 * 1.21: registered as `kobold_egg_projectile` (spawned by
 * `SendCompanionHomePacket`). The 1.12 `EnderTeleportEvent` for a non-player
 * thrower is `EntityTeleportEvent.EnderEntity` (the player-only
 * `EnderPearl` event cannot carry a girl). End gateways teleport through the
 * 1.21 portal system ([LivingEntity.setAsInsidePortal]) instead of the removed
 * `TileEntityEndGateway.teleportEntity`.
 */
class KoboldEggProjectileEntity : ThrownEnderpearl {
    /** Registry factory. */
    constructor(type: EntityType<out KoboldEggProjectileEntity>, level: Level) : super(type, level)

    constructor(level: Level) : this(ModEntities.KOBOLD_EGG_PROJECTILE.get(), level)

    /** 1.12 `EntityThrowable(World, EntityLivingBase)`: spawn at the thrower's eyes, owned by it. */
    constructor(level: Level, thrower: LivingEntity) : this(level) {
        this.setPos(thrower.x, thrower.eyeY - 0.1, thrower.z)
        this.owner = thrower
    }

    private fun spawnPortalParticles() {
        for (i in 0 until 32) {
            this.level().addParticle(
                ParticleTypes.PORTAL,
                this.x,
                this.y + this.random.nextDouble() * 2.0,
                this.z,
                this.random.nextGaussian(),
                0.0,
                this.random.nextGaussian(),
            )
        }
    }

    /**
     * SERVER/CLIENT: impact handling — end gateways teleport the thrower
     * directly; otherwise portal particles spawn and (SERVER, only within
     * 5 blocks of the girl's home) the thrower is teleported via the
     * ender-teleport event and dismounted.
     */
    override fun onHit(rayTrace: HitResult) {
        val thrower = this.owner as? LivingEntity
        if (thrower != null) {
            if (rayTrace.type == HitResult.Type.BLOCK) {
                val blockPos = (rayTrace as BlockHitResult).blockPos
                val tileEntity = this.level().getBlockEntity(blockPos)
                if (tileEntity is TheEndGatewayBlockEntity) {
                    if (thrower is ServerPlayer) {
                        CriteriaTriggers.ENTER_BLOCK.trigger(thrower, this.level().getBlockState(blockPos))
                    }

                    val gateway = this.level().getBlockState(blockPos).block
                    if (gateway is EndGatewayBlock) {
                        thrower.setAsInsidePortal(gateway, blockPos)
                    }
                    this.discard()
                    return
                }
            }

            this.spawnPortalParticles()

            if (!this.level().isClientSide) {
                val girl = thrower as BaseGirlEntity
                if (girl.homePos.distanceTo(this.position()) < 5.0) {
                    val teleportEvent = EntityTeleportEvent.EnderEntity(thrower, this.x, this.y, this.z)
                    if (!NeoForge.EVENT_BUS.post(teleportEvent).isCanceled) {
                        if (thrower.isPassenger) {
                            thrower.stopRiding()
                        }

                        thrower.teleportTo(this.x, this.y, this.z)
                        thrower.fallDistance = 0.0f
                    }
                }

                this.discard()
            }
        } else {
            if (rayTrace.type == HitResult.Type.BLOCK) {
                val blockPos = (rayTrace as BlockHitResult).blockPos
                val tileEntity = this.level().getBlockEntity(blockPos)
                if (tileEntity is TheEndGatewayBlockEntity) {
                    val gateway = this.level().getBlockState(blockPos).block
                    if (gateway is EndGatewayBlock) {
                        this.setAsInsidePortal(gateway, blockPos)
                    }
                    return
                }
            }

            this.spawnPortalParticles()

            if (!this.level().isClientSide) {
                this.discard()
            }
        }
    }

    /** Registered by `ForgeEventHandler` (common). */
    class EnderTeleportHandler {
        @SubscribeEvent
        fun onEnderTeleport(event: EntityTeleportEvent.EnderEntity) {
            val girl = event.entityLiving as? BaseGirlEntity ?: return
            girl.activeEnderPearl = null
            girl.setCurrentAction(Action.NULL)
            girl.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
            girl.goHome()
        }
    }
}
