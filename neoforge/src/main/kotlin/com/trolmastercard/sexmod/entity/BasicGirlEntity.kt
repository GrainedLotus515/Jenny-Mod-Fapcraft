package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The "pyrocinical" ambient entity — a generic girl-shaped
 * filler mob (NOT a [BaseGirlEntity]) that spawns rarely in the Nether,
 * wanders/follows players and despawns 60 seconds after being hit by a
 * player. Minimal implementation: procedural wander AI, no inventory, no
 * scenes.
 *
 * <b>Pitfalls.</b> [hurt] never deals
 * damage — player hits only stop it and schedule the 6.25 s removal;
 * out-of-world damage removes it instantly.
 *
 * Port notes: the 1.12 `updateAITasks()` override (server AI step) is
 * [customServerAiStep]; `getCanSpawnHere()` is [checkSpawnRules].
 */
open class BasicGirlEntity : Mob {
    @JvmField
    var wanderTarget: BlockPos? = null

    @JvmField
    var wanderTicks: Int = 0

    @JvmField
    var shouldStopMoving: Boolean = false

    @JvmField
    var lastSoundTick: Int = -1

    /** SERVER: [tickCount] at which the girl despawns after being hit, or -1. */
    @JvmField
    var removalTick: Int = -1

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out BasicGirlEntity>, level: Level) : super(type, level)

    constructor(world: Level) : this(ModEntities.PYROCINICAL.get(), world)

    /** 1.12 `updateAITasks()`. */
    override fun customServerAiStep() {
        super.customServerAiStep()
        this.tickScheduledRemoval()
        this.updateWanderAndFollowAI()
    }

    /** SERVER: removes the entity once the post-hit delay scheduled by [hurt] has elapsed. */
    fun tickScheduledRemoval() {
        if (this.removalTick >= 0 && this.tickCount >= this.removalTick) {
            this.removalTick = -1
            this.discard()
        }
    }

    /**
     * Wander/follow — picks a random ground point (respecting the
     * Nether ceiling) within 30 blocks and paths there; stays put when a
     * player is within 3 blocks or when stopped by a player hit.
     */
    fun updateWanderAndFollowAI() {
        if (this.shouldStopMoving) {
            this.getNavigation().stop()
        } else {
            val player = this.level().getNearestPlayer(this, 15.0)
            if (player != null && player.distanceTo(this) < 3.0f) {
                this.getNavigation().stop()
            } else {
                var target = this.wanderTarget
                if (target == null ||
                    Math.sqrt(this.distanceToSqr(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())) > this.getDespawnDistance() ||
                    this.wanderTicks > 175
                ) {
                    val xOffset = (if (this.random.nextBoolean()) 1 else -1) * this.random.nextInt(10)
                    val zOffset = (if (this.random.nextBoolean()) 1 else -1) * this.random.nextInt(10)
                    val height = if (this.level().dimension() == Level.NETHER) {
                        Math.ceil(this.y).toInt()
                    } else {
                        WorldUtils.getHeightAt(this.level(), this.blockPosition().x + xOffset, this.blockPosition().z + zOffset)
                    }
                    target = BlockPos(this.blockPosition().x + xOffset, height, this.blockPosition().z + zOffset)
                    this.wanderTarget = target
                    this.wanderTicks = 0
                }

                if (Math.sqrt(target.distSqr(this.blockPosition())) > 2.0) {
                    this.getNavigation().moveTo(target.x.toDouble(), target.y.toDouble(), target.z.toDouble(), 0.35f.toDouble())
                    this.updateWanderAI()
                } else {
                    this.wanderTicks++
                }
            }
        }
    }

    open fun updateWanderAI() {
        val path = this.getNavigation().path
        if (path != null) {
            if (!this.onGround() && !this.isInWater) {
                val currentIndex = path.nextNodeIndex
                val length = path.nodeCount
                if (length != currentIndex && length - 1 != currentIndex) {
                    val currentPoint = path.getNode(currentIndex)
                    val nextPoint = path.getNode(currentIndex + 1)
                    val delta = Vec3(
                        (nextPoint.x - currentPoint.x).toDouble(),
                        (nextPoint.y - currentPoint.y).toDouble(),
                        (nextPoint.z - currentPoint.z).toDouble(),
                    )
                    this.setDeltaMovement(delta.x / 7.0, this.deltaMovement.y, delta.z / 7.0)
                }
            }
        }
    }

    /**
     * Player hits never damage: they stop the mob, play the spawn sound
     * (CLIENT) and schedule its removal 125 ticks later; out-of-world damage
     * removes it immediately.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            this.discard()
            return true
        }

        if (source.entity !is Player) {
            return false
        }

        if (this.level().isClientSide) {
            this.playSpawnSound()
        }

        this.shouldStopMoving = true
        if (this.removalTick < 0) {
            this.removalTick = this.tickCount + 125
        }

        return false
    }

    /** CLIENT. */
    fun playSpawnSound() {
        val player = ClientHooks.localPlayer()!! // java-npe
        this.lastSoundTick = player.tickCount
        player.playSound(SoundHandler.MISC_WEOWEO[3], 1.0f, 1.0f)
    }

    fun getDespawnDistance(): Double = Math.sqrt(1800.0)

    /** 1.12 `getCanSpawnHere()`. */
    override fun checkSpawnRules(level: LevelAccessor, reason: MobSpawnType): Boolean {
        if (this.random.nextInt(100) < 1 && this.random.nextInt(100) < 10) {
            return true
        }

        this.discard()
        return false
    }

    companion object {
        const val LIFETIME_MS: Long = 60000L
        const val FOLLOW_DISTANCE: Float = 3.0f
        const val WANDER_DISTANCE: Float = 30.0f
        const val SOUND_COOLDOWN: Int = 175
        const val TICK_COUNT: Int = 10

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
    }
}
