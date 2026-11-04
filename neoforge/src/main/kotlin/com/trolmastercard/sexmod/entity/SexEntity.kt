package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.registry.ModEntities
import java.util.Optional
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The "luna hook" — Luna's fishing-rod bobber entity
 * ([LunaEntity.hookEntity]). Vanilla fishing-fight simulation: flies from the
 * rod, hooks entities or the ground, bobs in water, rolls loot
 * (`BuiltInLootTables.FISHING`) and hands the catch to Luna.
 *
 * <b>State.</b> Data keys 111 (`CAUGHT_ENTITY_ID`, entity id + 1,
 * 0 = none) and 110 (`OWNER_UUID` = Luna's girl id). `hookState`
 * cycles FLYING -> HOOKED_IN_ENTITY/BOBBING; `caughtEntity` is
 * mirrored from the data key via [onSyncedDataUpdated].
 *
 * <b>Pitfalls.</b> [canCatch] always returns false — the catch check
 * runs from [checkCatch] in the FLYING state instead. The hook
 * despawns 1200 ticks after being hooked. NBT is intentionally empty
 * (save hooks are no-ops and [shouldBeSaved] is false) — the hook
 * never persists. [getCatchResult] resolves the catch outcome on the
 * SERVER (entity -> 5/3, lure -> 1, hooked -> 2) and is the link back
 * into Luna's catch UI.
 *
 * Port notes:
 * - 1.12 registered `OWNER_UUID` with `Optional.of(ownerLuna.getGirlId())` as its
 *   default (an NPE on a remote client, where the static is null). 1.21 only
 *   syncs non-default values, so the key is defined empty and set in the
 *   Luna constructor instead.
 * - The 1.12 `motionX/Y/Z` fields are mapped onto `deltaMovement` through the
 *   private [motionX]/[motionY]/[motionZ] properties so the simulation reads
 *   like the original.
 * - Water material checks use the fluid state (`FluidTags.WATER`);
 *   `BlockLiquid.getBlockLiquidHeight` is `FluidState.getHeight`.
 */
class SexEntity : Entity {
    private var isHooked: Boolean = false
    private var despawnTimer: Int = 0
    private var waterBobCounter: Int = 0

    @JvmField
    var lureTimer: Int = 0

    private var catchDelay: Int = 0
    private var bobMotion: Int = 0
    private var bobAngle: Float = 0f

    @JvmField
    var caughtEntity: Entity? = null

    private var hookState: SexEntityState = SexEntityState.FLYING
    private var phase: Int = 0
    private var fishingLevel: Int = 0

    private var motionX: Double
        get() = this.deltaMovement.x
        set(value) {
            this.setDeltaMovement(value, this.deltaMovement.y, this.deltaMovement.z)
        }

    private var motionY: Double
        get() = this.deltaMovement.y
        set(value) {
            this.setDeltaMovement(this.deltaMovement.x, value, this.deltaMovement.z)
        }

    private var motionZ: Double
        get() = this.deltaMovement.z
        set(value) {
            this.setDeltaMovement(this.deltaMovement.x, this.deltaMovement.y, value)
        }

    /** Registry factory (used by ModEntities; client spawn). 1.12 `SexEntity(World)`. */
    constructor(type: EntityType<out SexEntity>, level: Level) : super(type, level)

    constructor(world: Level, luna: LunaEntity, height: Double) : this(ModEntities.LUNA_HOOK.get(), world) {
        this.setOwnerLuna(luna)
        val owner = ownerLuna
        this.entityData.set(OWNER_UUID, if (owner == null) Optional.empty() else Optional.of(owner.getGirlId()))
        this.positionLunaAbove(height)
    }

    private fun setOwnerLuna(luna: LunaEntity) {
        this.noCulling = true
        luna.hookEntity = this
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(CAUGHT_ENTITY_ID, 0)
        builder.define(OWNER_UUID, Optional.empty())
    }

    /** 1.12 `getRenderBoundingBox()`. */
    override fun getBoundingBoxForCulling(): AABB = this.boundingBox.inflate(10.0)

    fun getOwnerLunaInternal(): LunaEntity? {
        val uuidOpt = this.entityData.get(OWNER_UUID)
        if (!uuidOpt.isPresent) {
            return null
        } else {
            val girl = BaseGirlEntity.getServerGirlEntity(uuidOpt.get()) ?: return null
            return if (girl !is LunaEntity) null else girl
        }
    }

    fun getOwnerLuna(): LunaEntity? {
        val uuidOpt = this.entityData.get(OWNER_UUID)
        if (!uuidOpt.isPresent) {
            return null
        }

        val girl = BaseGirlEntity.getClientGirlEntity(uuidOpt.get())
        return if (girl !is LunaEntity) null else girl
    }

    fun setFishingLevel(level: Int) {
        this.fishingLevel = level
    }

    fun setPhase(phase: Int) {
        this.phase = phase
    }

    /** 1.12 `onEntityUpdate()`. */
    override fun baseTick() {
        super.baseTick()
        if (!this.level().isClientSide) {
            if ((this.caughtEntity != null || this.onGround()) && this.lureTimer == 0) {
                // 1.12 dereferenced the owner unconditionally (NPE when Luna is gone)
                this.getOwnerLunaInternal()?.addCaughtItem()
            }
        }
    }

    fun positionLunaAbove(height: Double) {
        val luna = this.getOwnerLunaInternal()
        if (luna != null) {
            val anchorPos = luna.fishingSpot!! // java-npe: the cast is only issued with a fishing spot
            val dist = Math.sqrt(luna.position().distanceToSqr(anchorPos.x.toDouble(), anchorPos.y.toDouble(), anchorPos.z.toDouble())).toFloat()
            val angle = -22.5f + 45.0f * (dist / 7.0f)
            val yaw = luna.getYawRotation()
            val cosYaw = Mth.cos(-yaw * (Math.PI / 180.0).toFloat() - Math.PI.toFloat())
            val sinYaw = Mth.sin(-yaw * (Math.PI / 180.0).toFloat() - Math.PI.toFloat())
            val cosTilt = -Mth.cos(-angle * (Math.PI / 180.0).toFloat())
            val sinTilt = Mth.sin(-angle * (Math.PI / 180.0).toFloat())
            val interpX = luna.xo + (luna.x - luna.xo) - sinYaw * 0.3
            val interpY = luna.yo + (luna.y - luna.yo) + luna.eyeHeight
            val interpZ = luna.zo + (luna.z - luna.zo) - cosYaw * 0.3
            this.moveTo(interpX, interpY, interpZ, yaw, angle)
            this.motionX = height * -sinYaw
            this.motionY = height * Mth.clamp(-(sinTilt / cosTilt), -5.0f, 5.0f)
            this.motionZ = height * -cosYaw
            val speed = Math.sqrt(this.motionX * this.motionX + this.motionY * this.motionY + this.motionZ * this.motionZ).toFloat()
            this.motionX = this.motionX * (0.6 / speed + 0.5 + this.random.nextGaussian() * 0.0045)
            this.motionY = this.motionY * (0.6 / speed + 0.5 + this.random.nextGaussian() * 0.0045)
            this.motionZ = this.motionZ * (0.6 / speed + 0.5 + this.random.nextGaussian() * 0.0045)
            val horizontalSpeed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ).toFloat()
            this.yRot = (Mth.atan2(this.motionX, this.motionZ) * (180.0 / Math.PI)).toFloat()
            this.xRot = (Mth.atan2(this.motionY, horizontalSpeed.toDouble()) * (180.0 / Math.PI)).toFloat()
            this.yRotO = this.yRot
            this.xRotO = this.xRot
        }
    }

    /** 1.12 `notifyDataManagerChange`. */
    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        if (CAUGHT_ENTITY_ID == key) {
            val caughtId = this.entityData.get(CAUGHT_ENTITY_ID)
            this.caughtEntity = if (caughtId > 0) this.level().getEntity(caughtId - 1) else null
        }

        super.onSyncedDataUpdated(key)
    }

    /** 1.12 `isInRangeToRenderDist`. */
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < 4096.0

    /** 1.12 `setPositionAndRotationDirect` (client interpolation) is a no-op. */
    override fun lerpTo(x: Double, y: Double, z: Double, yRot: Float, xRot: Float, steps: Int) {
    }

    /**
     * SERVER: the fishing flight/bob simulation — flies while FLYING (catch
     * checks, water bob transition, hook despawn timer), rides the caught
     * entity when HOOKED_IN_ENTITY, and bobs + spawns loot when BOBBING.
     */
    override fun tick() {
        super.tick()
        if (this.getOwnerLunaInternal() == null) {
            this.discard()
        } else if (this.level().isClientSide || !this.canCatch()) {
            if (this.isHooked) {
                this.despawnTimer++
                if (this.despawnTimer >= 1200) {
                    this.discard()
                    return
                }
            }

            var liquidHeight = 0.0f
            val pos = this.blockPosition()
            val fluid = this.level().getFluidState(pos)
            val isWater = fluid.`is`(FluidTags.WATER)
            if (isWater) {
                liquidHeight = fluid.getHeight(this.level(), pos)
            }

            if (this.hookState == SexEntityState.FLYING) {
                if (this.caughtEntity != null) {
                    this.setDeltaMovement(0.0, 0.0, 0.0)
                    this.hookState = SexEntityState.HOOKED_IN_ENTITY
                    return
                }

                if (liquidHeight > 0.0f) {
                    this.motionX *= 0.3
                    this.motionY *= 0.2
                    this.motionZ *= 0.3
                    this.hookState = SexEntityState.BOBBING
                    return
                }

                if (!this.level().isClientSide) {
                    this.checkCatch()
                }

                if (!this.isHooked && !this.onGround() && !this.horizontalCollision) {
                    this.waterBobCounter++
                } else {
                    this.waterBobCounter = 0
                    this.setDeltaMovement(0.0, 0.0, 0.0)
                }
            } else {
                if (this.hookState == SexEntityState.HOOKED_IN_ENTITY) {
                    val caught = this.caughtEntity
                    if (caught != null) {
                        if (caught.isRemoved) {
                            this.caughtEntity = null
                            this.hookState = SexEntityState.FLYING
                        } else {
                            val height = caught.bbHeight.toDouble()
                            this.setPos(caught.x, caught.boundingBox.minY + height * 0.8, caught.z)
                        }
                    }

                    return
                }

                if (this.hookState == SexEntityState.BOBBING) {
                    this.motionX *= 0.9
                    this.motionZ *= 0.9
                    var deltaY = this.y + this.motionY - pos.y - liquidHeight
                    if (Math.abs(deltaY) < 0.01) {
                        deltaY += Math.signum(deltaY) * 0.1
                    }

                    this.motionY = this.motionY - deltaY * this.random.nextFloat() * 0.2
                    if (!this.level().isClientSide && liquidHeight > 0.0f) {
                        this.spawnLootBlocks(pos)
                    }
                }
            }

            if (!isWater) {
                this.motionY -= 0.03
            }

            this.move(MoverType.SELF, this.deltaMovement)
            this.updateVelocity()
            this.setDeltaMovement(this.deltaMovement.scale(0.92))
            this.setPos(this.x, this.y, this.z)
        }
    }

    private fun canCatch(): Boolean = false

    private fun updateVelocity() {
        val horizontalSpeed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ).toFloat()
        this.yRot = (Mth.atan2(this.motionX, this.motionZ) * (180.0 / Math.PI)).toFloat()
        this.xRot = (Mth.atan2(this.motionY, horizontalSpeed.toDouble()) * (180.0 / Math.PI)).toFloat()

        while (this.xRot - this.xRotO < -180.0f) {
            this.xRotO -= 360.0f
        }

        while (this.xRot - this.xRotO >= 180.0f) {
            this.xRotO += 360.0f
        }

        while (this.yRot - this.yRotO < -180.0f) {
            this.yRotO -= 360.0f
        }

        while (this.yRot - this.yRotO >= 180.0f) {
            this.yRotO += 360.0f
        }

        this.xRot = this.xRotO + (this.xRot - this.xRotO) * 0.2f
        this.yRot = this.yRotO + (this.yRot - this.yRotO) * 0.2f
    }

    /**
     * SERVER: ray-traces the hook's motion and locks onto the nearest
     * collidable entity (or the ground) ahead of it.
     */
    private fun checkCatch() {
        var start = Vec3(this.x, this.y, this.z)
        var end = Vec3(this.x + this.motionX, this.y + this.motionY, this.z + this.motionZ)
        // 1.12 rayTraceBlocks(start, end, stopOnLiquid=false, ignoreNoBox=true, returnLast=false)
        val blockHit = this.level().clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
        start = Vec3(this.x, this.y, this.z)
        end = Vec3(this.x + this.motionX, this.y + this.motionY, this.z + this.motionZ)
        if (blockHit.type != HitResult.Type.MISS) {
            end = Vec3(blockHit.location.x, blockHit.location.y, blockHit.location.z)
        }

        var target: Entity? = null
        val entities = this.level().getEntities(this, this.boundingBox.expandTowards(this.deltaMovement).inflate(1.0))
        var closestDist = 0.0

        for (entity in entities) {
            if (this.isCollidableEntity(entity) && (entity !== this.getOwnerLunaInternal() || this.waterBobCounter >= 5)) {
                val aabb = entity.boundingBox.inflate(0.3f.toDouble())
                val entityHit = aabb.clip(start, end)
                if (entityHit.isPresent) {
                    val distSq = start.distanceToSqr(entityHit.get())
                    if (distSq < closestDist || closestDist == 0.0) {
                        target = entity
                        closestDist = distSq
                    }
                }
            }
        }

        if (target != null) {
            this.caughtEntity = target
            this.bindTargetEntity()
        } else if (blockHit.type != HitResult.Type.MISS) {
            this.isHooked = true
        }
    }

    private fun bindTargetEntity() {
        this.entityData.set(CAUGHT_ENTITY_ID, this.caughtEntity!!.id + 1) // java-npe
    }

    /**
     * SERVER: the bobbing fight — lure timer, bubble/wake particles, splash
     * sounds and the randomized catch-delay chain (scaled by the fishing
     * level). Vanilla bobber mechanics, adapted for Luna.
     */
    private fun spawnLootBlocks(centerPos: BlockPos) {
        val worldServer = this.level() as ServerLevel
        var lootCount = 1
        val pos = centerPos.above()
        if (this.random.nextFloat() < 0.25f && this.level().isRainingAt(pos)) {
            lootCount++
        }

        if (this.random.nextFloat() < 0.5f && !this.level().canSeeSky(pos)) {
            lootCount--
        }

        if (this.lureTimer > 0) {
            this.lureTimer--
            if (this.lureTimer <= 0) {
                this.catchDelay = 0
                this.bobMotion = 0
            } else {
                this.motionY = this.motionY - 0.2 * this.random.nextFloat() * this.random.nextFloat()
            }
        } else if (this.bobMotion > 0) {
            this.bobMotion -= lootCount
            if (this.bobMotion > 0) {
                this.bobAngle = (this.bobAngle + this.random.nextGaussian() * 4.0).toFloat()
                val angle = this.bobAngle * (Math.PI / 180.0).toFloat()
                val sinAngle = Mth.sin(angle)
                val cosAngle = Mth.cos(angle)
                val x = this.x + sinAngle * this.bobMotion * 0.1f
                val y = (Mth.floor(this.boundingBox.minY) + 1.0f).toDouble()
                val z = this.z + cosAngle * this.bobMotion * 0.1f
                if (worldServer.getFluidState(BlockPos.containing(x, y - 1.0, z)).`is`(FluidTags.WATER)) {
                    if (this.random.nextFloat() < 0.15f) {
                        worldServer.sendParticles(ParticleTypes.BUBBLE, x, y - 0.1f, z, 1, sinAngle.toDouble(), 0.1, cosAngle.toDouble(), 0.0)
                    }

                    val xVel = sinAngle * 0.04f
                    val zVel = cosAngle * 0.04f
                    worldServer.sendParticles(ParticleTypes.FISHING, x, y, z, 0, zVel.toDouble(), 0.01, -xVel.toDouble(), 1.0)
                    worldServer.sendParticles(ParticleTypes.FISHING, x, y, z, 0, -zVel.toDouble(), 0.01, xVel.toDouble(), 1.0)
                }
            } else {
                this.motionY = (-0.4f * Mth.nextFloat(this.random, 0.6f, 1.0f)).toDouble()
                this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25f, 1.0f + (this.random.nextFloat() - this.random.nextFloat()) * 0.4f)
                val y = this.boundingBox.minY + 0.5
                worldServer.sendParticles(
                    ParticleTypes.BUBBLE,
                    this.x,
                    y,
                    this.z,
                    (1.0f + this.bbWidth * 20.0f).toInt(),
                    this.bbWidth.toDouble(),
                    0.0,
                    this.bbWidth.toDouble(),
                    0.2f.toDouble(),
                )
                worldServer.sendParticles(
                    ParticleTypes.FISHING,
                    this.x,
                    y,
                    this.z,
                    (1.0f + this.bbWidth * 20.0f).toInt(),
                    this.bbWidth.toDouble(),
                    0.0,
                    this.bbWidth.toDouble(),
                    0.2f.toDouble(),
                )
                this.lureTimer = Mth.nextInt(this.random, 20, 40)
            }
        } else if (this.catchDelay > 0) {
            this.catchDelay -= lootCount
            var radius = 0.15f
            if (this.catchDelay < 20) {
                radius = (0.15f + (20 - this.catchDelay) * 0.05).toFloat()
            } else if (this.catchDelay < 40) {
                radius = (0.15f + (40 - this.catchDelay) * 0.02).toFloat()
            } else if (this.catchDelay < 60) {
                radius = (0.15f + (60 - this.catchDelay) * 0.01).toFloat()
            }

            if (this.random.nextFloat() < radius) {
                val angle = Mth.nextFloat(this.random, 0.0f, 360.0f) * (Math.PI / 180.0).toFloat()
                val spread = Mth.nextFloat(this.random, 25.0f, 60.0f)
                val x = this.x + Mth.sin(angle) * spread * 0.1f
                val y = (Mth.floor(this.boundingBox.minY) + 1.0f).toDouble()
                val z = this.z + Mth.cos(angle) * spread * 0.1f
                if (worldServer.getFluidState(BlockPos(x.toInt(), y.toInt() - 1, z.toInt())).`is`(FluidTags.WATER)) {
                    worldServer.sendParticles(ParticleTypes.SPLASH, x, y, z, 2 + this.random.nextInt(2), 0.1f.toDouble(), 0.0, 0.1f.toDouble(), 0.0)
                }
            }

            if (this.catchDelay <= 0) {
                this.bobAngle = Mth.nextFloat(this.random, 0.0f, 360.0f)
                this.bobMotion = Mth.nextInt(this.random, 20, 80)
            }
        } else {
            this.catchDelay = Mth.nextInt(this.random, 100, 600)
            this.catchDelay = this.catchDelay - this.fishingLevel * 20 * 5
        }
    }

    fun isCollidableEntity(entity: Entity): Boolean = entity.isPickable || entity is ItemEntity

    override fun addAdditionalSaveData(tag: CompoundTag) {
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
    }

    /** 1.12 `writeToNBT` returned null / wrote nothing: the hook never persists. */
    override fun shouldBeSaved(): Boolean = false

    /**
     * SERVER: resolves the catch — converts the caught entity into its result
     * code (entity item 3 / entity 5 via [handleCatch]), rolls a
     * fishing loot table into Luna's held stack (`lureTimer = 9999`) or
     * reports the hooked state (2). 0 = nothing.
     */
    fun getCatchResult(): Int {
        if (!this.level().isClientSide && this.getOwnerLunaInternal() != null) {
            var result = 0
            val caught = this.caughtEntity
            if (caught != null) {
                this.handleCatch()
                this.level().broadcastEntityEvent(this, 31.toByte())
                result = if (caught is ItemEntity) 3 else 5
            } else if (this.lureTimer > 0) {
                val serverLevel = this.level() as ServerLevel
                val params = LootParams.Builder(serverLevel).create(LootContextParamSets.EMPTY)
                val table = serverLevel.server.reloadableRegistries().getLootTable(BuiltInLootTables.FISHING)

                for (stack in table.getRandomItems(params)) {
                    val luna = this.getOwnerLunaInternal()!! // java-npe: checked above
                    luna.setHeldItemStack(stack)
                }

                this.lureTimer = 9999
                result = 1
            }

            if (this.isHooked) {
                result = 2
            }

            return result
        } else {
            return 0
        }
    }

    fun handleCatch() {
        val luna = this.getOwnerLunaInternal()
        if (luna != null) {
            val dx = luna.x - this.x
            val dy = luna.y - this.y
            val dz = luna.z - this.z
            val caught = this.caughtEntity!! // java-npe: only called with a caught entity
            caught.setDeltaMovement(caught.deltaMovement.add(dx * 0.1, dy * 0.1, dz * 0.1))
        }
    }

    /** 1.12 `canTriggerWalking()` = false. */
    override fun getMovementEmission(): MovementEmission = MovementEmission.NONE

    enum class SexEntityState {
        FLYING,
        HOOKED_IN_ENTITY,
        BOBBING,
    }

    companion object {
        const val MAX_HOOK_RANGE: Int = 15

        private val CAUGHT_ENTITY_ID: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(SexEntity::class.java, EntityDataSerializers.INT)

        private val OWNER_UUID: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(SexEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)

        @JvmField
        var ownerLuna: LunaEntity? = null
    }
}
