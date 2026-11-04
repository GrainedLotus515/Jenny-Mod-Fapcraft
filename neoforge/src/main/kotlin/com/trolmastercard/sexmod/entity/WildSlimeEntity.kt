package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.Reference
import java.util.EnumSet
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The wild slime — the mob a pregnant [SlimeEntity] births
 * after its horny countdown. It hops around with vanilla slime AI (jump/
 * wander/float helpers), grows over 8400 ticks, and then matures back into a
 * Slime girl at its position. Tracked in the static [ALL_SLIMES] list
 * used by [findSlimesNear] (e.g. for egg-laying checks).
 *
 * <b>Pitfalls.</b> `SIZE` (110) and `AGE_IN_TICKS` (111) are the
 * only data keys. Dying splits it into two smaller slimes (vanilla behavior,
 * see [remove]). Keep the maturation timing (8400) in sync with the
 * Slime pregnancy lifecycle.
 *
 * Port notes: the 1.12 `setSize(0.51 * size)` is [getDefaultDimensions] +
 * `refreshDimensions()`; `registerFixes` (DataFixer) is dropped; `getDropItem`
 * is kept as a plain method (1.21 drops come from the loot table only);
 * `onInitialSpawn` is [finalizeSpawn]; `jump` is [jumpFromGround];
 * `getVerticalFaceSpeed` is [getMaxHeadXRot]; `setDead` is [remove].
 */
class WildSlimeEntity : Mob {
    @JvmField
    var squishAmount: Float = 0f

    @JvmField
    var squishFactor: Float = 0f

    @JvmField
    var prevSquishFactor: Float = 0f

    private var wasOnGround: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out WildSlimeEntity>, level: Level) : super(type, level) {
        this.moveControl = SlimeMoveHelper(this)
    }

    constructor(level: Level) : this(ModEntities.FRIENDLY_SLIME.get(), level)

    override fun registerGoals() {
        this.goalSelector.addGoal(1, SlimeWanderAI(this))
        this.goalSelector.addGoal(5, SlimeJumpAI(this))
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(SIZE, 1)
        builder.define(AGE_IN_TICKS, 0)
    }

    /** 1.12 `fall()` was empty: no fall damage. */
    override fun causeFallDamage(fallDistance: Float, multiplier: Float, source: DamageSource): Boolean = false

    /** 1.12 `canDespawn()` = false. */
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    /** 1.12 `setSize(0.51000005F * size, ...)` with `getEyeHeight() = 0.625F * height`. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions {
        val size = 0.51000005f * this.getSquishFactor()
        return EntityDimensions.scalable(size, size).withEyeHeight(0.625f * size)
    }

    fun setSlimeSize(size: Int, flag: Boolean) {
        this.entityData.set(SIZE, size)
        this.refreshDimensions()
        this.setPos(this.x, this.y, this.z)
        this.getAttribute(Attributes.MAX_HEALTH)?.baseValue = (size * size).toDouble()
        this.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = (0.2f + 0.1f * size).toDouble()
        if (flag) {
            this.health = this.maxHealth
        }

        this.xpReward = size
    }

    fun getSquishFactor(): Int = this.entityData.get(SIZE)

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt("Size", this.getSquishFactor() - 1)
        tag.putBoolean("wasOnGround", this.wasOnGround)
        tag.putInt("ageInTicks", this.entityData.get(AGE_IN_TICKS))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        var size = tag.getInt("Size")
        if (size < 0) {
            size = 0
        }

        this.setSlimeSize(size + 1, false)
        this.wasOnGround = tag.getBoolean("wasOnGround")
        this.entityData.set(AGE_IN_TICKS, tag.getInt("ageInTicks"))
    }

    fun isSmallSlime(): Boolean = this.getSquishFactor() <= 1

    fun getParticleType(): ParticleOptions = ParticleTypes.ITEM_SLIME

    fun getPrevPosition(): Vec3 = Vec3(this.xo, this.yo, this.zo)

    fun spawnParticle(particleType: ParticleOptions) {
        val vx = Reference.RANDOM.nextGaussian() * 0.02
        val vy = Reference.RANDOM.nextGaussian() * 0.02
        val vz = Reference.RANDOM.nextGaussian() * 0.02
        this.level().addParticle(
            particleType,
            this.x + Reference.RANDOM.nextFloat() * this.bbWidth * 2.0f - this.bbWidth,
            this.y + 0.15 + Reference.RANDOM.nextFloat() * this.bbHeight,
            this.z + Reference.RANDOM.nextFloat() * this.bbWidth * 2.0f - this.bbWidth,
            vx,
            vy,
            vz,
        )
    }

    /**
     * SERVER/CLIENT: the maturation lifecycle — ages every tick; CLIENT spawns
     * cloud (7980+) and happy (5880+) particles, SERVER converts to a
     * [SlimeEntity] at 8400 ticks. Also runs the vanilla squish
     * animation states.
     */
    override fun tick() {
        this.entityData.set(AGE_IN_TICKS, this.entityData.get(AGE_IN_TICKS) + 1)
        if (this.level().isClientSide) {
            if (this.entityData.get(AGE_IN_TICKS).toDouble() > 7980.0) {
                this.spawnParticle(ParticleTypes.CLOUD)
            } else if (this.entityData.get(AGE_IN_TICKS).toDouble() > 5880.0 && this.tickCount % 10 == 0) {
                this.spawnParticle(ParticleTypes.HAPPY_VILLAGER)
            }
        } else if (this.entityData.get(AGE_IN_TICKS) > 8400) {
            val slime = SlimeEntity(this.level())
            slime.absMoveTo(this.x, this.y, this.z, this.yRot, this.xRot)
            this.level().addFreshEntity(slime)
            slime.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP)
            this.discard()
        }

        this.squishFactor = this.squishFactor + (this.squishAmount - this.squishFactor) * 0.5f
        this.prevSquishFactor = this.squishFactor
        super.tick()
        if (this.onGround() && !this.wasOnGround) {
            var squish = this.getSquishFactor()
            if (this.canDrop()) {
                squish = 0
            }

            for (i in 0 until squish * 8) {
                val theta = this.random.nextFloat() * (Math.PI * 2).toFloat()
                val scale = this.random.nextFloat() * 0.5f + 0.5f
                val xOffset = Mth.sin(theta) * squish * 0.5f * scale
                val zOffset = Mth.cos(theta) * squish * 0.5f * scale
                val world = this.level()
                val particleType = this.getParticleType()
                val x = this.x + xOffset
                val z = this.z + zOffset
                world.addParticle(particleType, x, this.boundingBox.minY, z, 0.0, 0.0, 0.0)
            }

            this.playSound(this.getSquishSound(), this.getSoundVolume(), ((this.random.nextFloat() - this.random.nextFloat()) * 0.2f + 1.0f) / 0.8f)
            this.squishAmount = -0.5f
        } else if (!this.onGround() && this.wasOnGround) {
            this.squishAmount = 1.0f
        }

        this.wasOnGround = this.onGround()
        this.decaySquish()
    }

    fun decaySquish() {
        this.squishAmount *= 0.6f
    }

    fun getRandomDespawnDelay(): Int = this.random.nextInt(100) + 50

    fun createChild(): WildSlimeEntity = WildSlimeEntity(this.level())

    /** 1.12 `notifyDataManagerChange`. */
    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        if (SIZE == key) {
            this.refreshDimensions()
            this.yRot = this.yHeadRot
            this.yBodyRot = this.yHeadRot
            if (this.isInWater && this.random.nextInt(20) == 0) {
                this.doWaterSplashEffect()
            }
        }

        super.onSyncedDataUpdated(key)
    }

    /** 1.12 `setDead()`: a dying slime larger than 1 splits into 2..4 children. */
    override fun remove(reason: RemovalReason) {
        val squish = this.getSquishFactor()
        if (!this.level().isClientSide && squish > 1 && this.health <= 0.0f) {
            val count = 2 + this.random.nextInt(3)

            for (i in 0 until count) {
                val xOffset = (i % 2 - 0.5f) * squish / 4.0f
                val zOffset = (i / 2 - 0.5f) * squish / 4.0f
                val child = this.createChild()
                if (this.hasCustomName()) {
                    child.customName = this.customName
                }

                if (this.isPersistenceRequired) {
                    child.setPersistenceRequired()
                }

                child.setSlimeSize(squish / 2, true)
                child.moveTo(this.x + xOffset, this.y + 0.5, this.z + zOffset, this.random.nextFloat() * 360.0f, 0.0f)
                this.level().addFreshEntity(child)
            }
        }

        super.remove(reason)
    }

    override fun getHurtSound(source: DamageSource): SoundEvent? =
        if (this.isSmallSlime()) SoundEvents.SLIME_HURT_SMALL else SoundEvents.SLIME_HURT

    override fun getDeathSound(): SoundEvent? =
        if (this.isSmallSlime()) SoundEvents.SLIME_DEATH_SMALL else SoundEvents.SLIME_DEATH

    fun getSquishSound(): SoundEvent = if (this.isSmallSlime()) SoundEvents.SLIME_SQUISH_SMALL else SoundEvents.SLIME_SQUISH

    /** 1.12 `getDropItem()` (no 1.21 override; drops come from [getDefaultLootTable]). */
    fun getDropItem(): Item? = if (this.getSquishFactor() == 1) Items.SLIME_BALL else null

    /** 1.12 `getLootTable()`. */
    override fun getDefaultLootTable(): ResourceKey<LootTable> =
        if (this.getSquishFactor() == 1) EntityType.SLIME.defaultLootTable else BuiltInLootTables.EMPTY

    override fun getSoundVolume(): Float = 0.4f * this.getSquishFactor()

    /** 1.12 `getVerticalFaceSpeed()` = 0. */
    override fun getMaxHeadXRot(): Int = 0

    fun canSquish(): Boolean = this.getSquishFactor() > 0

    /** 1.12 `jump()`. */
    override fun jumpFromGround() {
        this.setDeltaMovement(this.deltaMovement.x, 0.42f.toDouble(), this.deltaMovement.z)
        this.hasImpulse = true
    }

    /** 1.12 `onInitialSpawn`. */
    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?,
    ): SpawnGroupData? {
        this.setSlimeSize(1, true)
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData)
    }

    fun getJumpSound(): SoundEvent = if (this.isSmallSlime()) SoundEvents.SLIME_JUMP_SMALL else SoundEvents.SLIME_JUMP

    fun canDrop(): Boolean = false

    class SlimeFloatAI(private val ownerSlime: WildSlimeEntity) : Goal() {
        private var squishAngle: Float = 0f
        private var floatDelay: Int = 0

        init {
            this.setFlags(EnumSet.of(Flag.LOOK))
        }

        override fun canUse(): Boolean =
            this.ownerSlime.target == null &&
                (this.ownerSlime.onGround() || this.ownerSlime.isInWater || this.ownerSlime.isInLava || this.ownerSlime.hasEffect(MobEffects.LEVITATION))

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            if (--this.floatDelay <= 0) {
                this.floatDelay = 40 + this.ownerSlime.random.nextInt(60)
                this.squishAngle = this.ownerSlime.random.nextInt(360).toFloat()
            }

            (this.ownerSlime.moveControl as SlimeMoveHelper).setMoveHelperTarget(this.squishAngle, false)
        }
    }

    class SlimeMoveHelper(private val slime: WildSlimeEntity) : MoveControl(slime) {
        private var rotationYaw: Float = 180.0f * slime.yRot / Math.PI.toFloat()
        private var squishDelay: Int = 0
        private var wasOnGround: Boolean = false

        fun setMoveHelperTarget(yaw: Float, onGround: Boolean) {
            this.rotationYaw = yaw
            this.wasOnGround = onGround
        }

        fun setMoveHelperSpeed(speed: Double) {
            this.speedModifier = speed
            this.operation = Operation.MOVE_TO
        }

        /** 1.12 `onUpdateMoveHelper()`. */
        override fun tick() {
            this.mob.yRot = this.rotlerp(this.mob.yRot, this.rotationYaw, 90.0f)
            this.mob.yHeadRot = this.mob.yRot
            this.mob.yBodyRot = this.mob.yRot
            if (this.operation != Operation.MOVE_TO) {
                this.mob.setZza(0.0f)
            } else {
                this.operation = Operation.WAIT
                if (this.mob.onGround()) {
                    this.mob.speed = (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat()
                    if (this.squishDelay-- <= 0) {
                        this.squishDelay = this.slime.getRandomDespawnDelay()
                        if (this.wasOnGround) {
                            this.squishDelay /= 3
                        }

                        val yaw = Reference.RANDOM.nextInt(360).toFloat()
                        (this.slime.moveControl as SlimeMoveHelper).setMoveHelperTarget(yaw, false)
                        this.slime.jumpControl.jump()
                        if (this.slime.canSquish()) {
                            this.slime.playSound(
                                this.slime.getJumpSound(),
                                this.slime.getSoundVolume(),
                                ((this.slime.random.nextFloat() - this.slime.random.nextFloat()) * 0.2f + 1.0f) * 0.8f,
                            )
                        }
                    } else {
                        this.slime.xxa = 0.0f
                        this.slime.zza = 0.0f
                        this.mob.speed = 0.0f
                    }
                } else {
                    this.mob.speed = (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat()
                }
            }
        }
    }

    class SlimeJumpAI(private val squishAmount: WildSlimeEntity) : Goal() {
        init {
            // 1.12 mutex bits 5 = MOVE | JUMP
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP))
        }

        override fun canUse(): Boolean = true

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            (this.squishAmount.moveControl as SlimeMoveHelper).setMoveHelperSpeed(1.0)
        }
    }

    class SlimeWanderAI(private val squishAmount: WildSlimeEntity) : Goal() {
        init {
            // 1.12 mutex bits 5 = MOVE | JUMP
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP))
            (squishAmount.navigation as GroundPathNavigation).setCanFloat(true)
        }

        override fun canUse(): Boolean = this.squishAmount.isInWater || this.squishAmount.isInLava

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            if (this.squishAmount.random.nextFloat() < 0.8f) {
                this.squishAmount.jumpControl.jump()
            }

            (this.squishAmount.moveControl as SlimeMoveHelper).setMoveHelperSpeed(1.2)
        }
    }

    companion object {
        @JvmField
        var ALL_SLIMES: MutableList<WildSlimeEntity?> = ArrayList()

        private val AGE_IN_TICKS: EntityDataAccessor<Int> = SynchedEntityData.defineId(WildSlimeEntity::class.java, EntityDataSerializers.INT)

        private val SIZE: EntityDataAccessor<Int> = SynchedEntityData.defineId(WildSlimeEntity::class.java, EntityDataSerializers.INT)

        /** 1.12 `EntityLiving` default attributes. */
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()

        @JvmStatic
        fun findSlimesNear(pos: Vec3): ArrayList<WildSlimeEntity> {
            var slimes = findSlimesNearRadius(pos, 0.1)
            if (slimes.isEmpty()) {
                slimes = findSlimesNearRadius(pos, 0.5)
            }

            return slimes
        }

        @JvmStatic
        private fun findSlimesNearRadius(pos: Vec3, radius: Double): ArrayList<WildSlimeEntity> {
            val found = ArrayList<WildSlimeEntity>()

            try {
                for (slime in ALL_SLIMES) {
                    if (slime != null) {
                        val dist = Math.abs(slime.xo - pos.x) + Math.abs(slime.yo - pos.y) + Math.abs(slime.zo - pos.z)
                        if (dist < radius) {
                            found.add(slime)
                        }
                    }
                }
            } catch (ex: Exception) {
                Main.LOGGER.info("couldnt find slimes at distance $radius")
            }

            return found
        }
    }
}
