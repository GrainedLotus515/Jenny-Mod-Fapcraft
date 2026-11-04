package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket2
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.TrigMath
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.monster.WitherSkeleton
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The dragon charge projectile fired by Galath's energy-ball
 * attack ([GalathFlightData.SUMMON_SKELETON]). A no-clip projectile that
 * flies in a fixed direction; on CLIENT it spawns dragon-breath particles
 * along its path. When it hits a block it converts into a wither skeleton
 * guard (once) or, if charging, explodes on contact with a
 * [GalathEntity] and flings her into the knockout state
 * ([GalathEntity.setFlightVelocity]).
 *
 * <b>Pitfalls.</b> [hurt] by a player turns the charge back
 * onto the attacker (`isCharging` + direction = attacker's look);
 * arrows destroy it. Fall/water NBT is intentionally empty
 * ([readAdditionalSaveData] despawns it).
 *
 * Port notes: registered as `energy_ball` (MISC, 0.5×0.5; the 1.12
 * `setSize(0.5, 0.5)` lives in ModEntities). The [direction] is not synced to
 * clients (as in 1.12); clients follow the server position updates. The
 * client-only particle helpers only use common types (`ClientHooks`).
 */
open class DragonEntity : Mob {
    @JvmField
    var SCALE_1_0: Double = 1.0

    @JvmField
    var direction: Vec3 = Vec3.ZERO

    @JvmField
    var isCharging: Boolean = false

    @JvmField
    var shouldSpawnSkeleton: Boolean = true

    @JvmField
    var ownerGalath: GalathEntity? = null

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out DragonEntity>, level: Level) : super(type, level)

    constructor(world: Level) : this(ModEntities.ENERGY_BALL.get(), world)

    constructor(world: Level, galath: GalathEntity?) : this(world) {
        this.ownerGalath = galath
    }

    constructor(world: Level, galath: GalathEntity?, direction: Vec3) : this(world) {
        this.direction = direction
        this.ownerGalath = galath
    }

    /** 1.12 `canTriggerWalking()` = false. */
    override fun getMovementEmission(): Entity.MovementEmission = Entity.MovementEmission.NONE

    /** 1.12 `collideWithEntity(Entity)`. */
    override fun doPush(entity: Entity) {
    }

    /**
     * BOTH sides: no-clip flight along [direction] with breath
     * particles; the SERVER charges explode against Galaths
     * ([tickChargeState]) and block hits trigger
     * [tickDragonLife] (explode or spawn the skeleton guard).
     */
    override fun tick() {
        if (!this.isRemoved) {
            this.noPhysics = true
            this.setNoGravity(true)
            this.setDeltaMovement(this.direction.x, this.direction.y, this.direction.z)
            super.tick()
            if (this.level().isClientSide) {
                this.spawnChargedBreath()
            }

            this.tickChargeState()
            if (!this.level().isEmptyBlock(this.blockPosition())) {
                this.tickDragonLife()
                this.discard()
            }
        }
    }

    /**
     * SERVER: the charging hit — when a charged dragon overlaps a Galath it
     * explodes, flings her away ([GalathEntity.setFlightVelocity])
     * and despawns.
     */
    fun tickChargeState() {
        if (!this.level().isClientSide) {
            if (this.isCharging) {
                val pos = this.position()
                val min = pos.subtract(0.75, 0.75, 0.75)
                val max = pos.add(0.75, 0.75, 0.75)
                val aabb = AABB(min.x, min.y, min.z, max.x, max.y, max.z)
                val entities = this.level().getEntitiesOfClass(GalathEntity::class.java, aabb)
                if (entities.isNotEmpty()) {
                    // 1.12 createExplosion(..., damagesTerrain = true): always breaks blocks
                    this.level().explode(this, this.x, this.y, this.z, 1.0f, Level.ExplosionInteraction.TNT)

                    for (galath in entities) {
                        galath.setFlightVelocity(this.position())
                    }

                    this.discard()
                }
            }
        }
    }

    fun spawnChargedBreath() {
        this.spawnBreathParticles(
            RotationHelper.lerpDouble(this.xOld, this.x, 0.5),
            RotationHelper.lerpDouble(this.yOld, this.y, 0.5),
            RotationHelper.lerpDouble(this.zOld, this.z, 0.5),
        )
        this.spawnBreathParticles(this.x, this.y, this.z)
    }

    fun spawnBreathParticles(x: Double, y: Double, z: Double) {
        val random = this.random
        this.level().addParticle(
            ParticleTypes.DRAGON_BREATH,
            x + random.nextDouble() * 0.3f,
            y + 0.25 + random.nextDouble() * 0.3f,
            z + random.nextDouble() * 0.3f,
            0.0, 0.0, 0.0,
        )
    }

    /**
     * SERVER: the block-impact outcome — near the Galath's target she spawns
     * a wither-skeleton guard (sword-wielding, tracked in
     * [GalathEntity.summonedSkeletons]), otherwise she explodes.
     */
    fun tickDragonLife() {
        if (!this.level().isClientSide) {
            if (!this.isRemoved) {
                if (this.shouldSpawnSkeleton) {
                    val pos = Vec3(this.x, (this.blockPosition().y + 1).toDouble(), this.z)
                    if (!this.isInRangeOfTarget(pos)) {
                        this.level().explode(this, this.x, this.y, this.z, 2.0f, Level.ExplosionInteraction.TNT)
                        this.shouldSpawnSkeleton = false
                    } else {
                        val skeleton = WitherSkeleton(EntityType.WITHER_SKELETON, this.level())
                        skeleton.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.STONE_SWORD))
                        skeleton.moveTo(pos.x, pos.y, pos.z)
                        this.level().addFreshEntity(skeleton)
                        PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(pos, true), this)
                        this.ownerGalath?.summonedSkeletons?.add(skeleton)
                    }
                }
            }
        }
    }

    fun isInRangeOfTarget(pos: Vec3): Boolean {
        val owner = this.ownerGalath ?: return true

        val target = owner.getTargetEntity()
        return if (target == null) true else Math.sqrt(target.distanceToSqr(pos.x, pos.y, pos.z)) < 15.0
    }

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if ((source.`is`(DamageTypes.FELL_OUT_OF_WORLD) || source.`is`(DamageTypes.GENERIC_KILL))) {
            this.health = 0.0f
            this.shouldSpawnSkeleton = false
            this.discard()
            return true
        }

        if (!this.level().isClientSide && source.`is`(DamageTypes.ARROW)) {
            this.health = 0.0f
            this.shouldSpawnSkeleton = false
            PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(this.position(), false), this)
            val immediateSource = source.directEntity
            immediateSource?.discard()

            this.discard()
            return true
        } else {
            val trueSource = source.entity
            if (trueSource !is Player) {
                return false
            }

            this.direction = trueSource.lookAngle
            this.isCharging = true
            return true
        }
    }

    /** 1.12: loading a saved charge despawns it. */
    override fun readAdditionalSaveData(tag: CompoundTag) {
        this.discard()
    }

    /**
     * Port addition: charges are never written to disk, so the 1.12 "despawn on
     * load" path above cannot leave a removed entity inside a 1.21 entity section.
     */
    override fun shouldBeSaved(): Boolean = false

    companion object {
        const val SCALE_0_4: Float = 0.4f
        const val SCALE_0_3: Float = 0.3f
        const val MAX_TICKS_200: Int = 200
        const val MAX_TICKS_100: Int = 100
        const val SIZE_0_5: Float = 0.5f
        const val SCALE_0_15: Float = 0.15f
        const val SCALE_0_75: Float = 0.75f

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()

        /**
         * CLIENT: renders a smoke ring burst at the given position (used for the
         * energy-ball despawn effects).
         */
        @JvmStatic
        fun spawnDragonBreath(pos: Vec3) {
            val world = ClientHooks.clientLevel() ?: return
            val step = TrigMath.wrapDegrees(1.8f)
            val random = Reference.RANDOM

            var angle = 0.0f
            while (angle < Math.PI * 2) {
                val sin = Math.sin(angle.toDouble())
                val cos = Math.cos(angle.toDouble())
                val x = pos.x + sin * 0.5
                val vx = sin * 0.15f
                val z = pos.z + cos * 0.5
                val vz = cos * 0.15f
                val y = pos.y
                val vy = random.nextDouble() * 0.15f
                world.addParticle(ParticleTypes.SMOKE, x, y, z, vx, vy, vz)
                angle += step
            }
        }

        /** CLIENT. */
        @JvmStatic
        fun spawnDragonBreathRandom(pos: Vec3) {
            val world = ClientHooks.clientLevel() ?: return
            val random = Reference.RANDOM

            for (i in 0 until 100) {
                world.addParticle(
                    ParticleTypes.DRAGON_BREATH,
                    pos.x, pos.y, pos.z,
                    random.nextDouble() * 0.15f,
                    random.nextDouble() * 0.15f,
                    random.nextDouble() * 0.15f,
                )
            }

            world.playLocalSound(pos.x, pos.y, pos.z, SoundHandler.MISC_SHATTER[0], SoundSource.AMBIENT, 0.7f, 1.0f, false)
        }
    }
}
