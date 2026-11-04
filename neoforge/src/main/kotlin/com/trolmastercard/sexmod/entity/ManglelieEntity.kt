package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.MobPredicates
import com.trolmastercard.sexmod.api.IPositionProvider
import com.trolmastercard.sexmod.api.ITargetProvider
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.renderer.ManglelieRenderer
import com.trolmastercard.sexmod.entity.ai.AvoidPlayerGoal
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.BeeWorldData
import com.trolmastercard.sexmod.util.DynamicTrailRenderer
import com.trolmastercard.sexmod.util.EntityLookVectorHelper
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.GirlWorldData
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Manglelie ("Mang") — the small imp girl bound to a Galath as
 * her "daughter". Wild Mangles wander near hives; when a Galath finds one
 * (see [GalathTamedAi.tickSeekMangleliePartner]) she adopts it and it rides her head
 * ([Action.RIDE_MOMMY_HEAD]). The corrupting mechanic: while the tamed
 * Galath is in the corrupt state, Mang picks a nearby mob, holds it in a
 * magical beam (arrow shot from the Galath at 28 ticks), and after ~60 ticks
 * the mob converts into a threesome scene (slow/fast/cum, shared animations
 * with the Galath).
 *
 * <b>State.</b> Own data keys (1.12 ids 111..115): [GALATH_PARTNER_UUID] = Galath
 * partner UUID, [CORRUPTING] = corrupting flag, [CORRUPT_TARGET_ID] = corrupt
 * target entity id (-1 none), [CORRUPT_START_TIME] = corrupt start world time,
 * [SCARED] = scared-flag (set by [AvoidPlayerGoal]).
 * [removeOnNextTick] = despawned flag, [wild] = wild flag.
 *
 * <b>Flow.</b> [handleCorruptInit] seeks an unbound Galath within 15
 * blocks and runs to her; [handlePartnerLook] binds the pair
 * (Galath gets `setMangleliePartnerUUID`); the corrupt cycle is
 * [handleCorruptStart] (pick a valid target mob) ->
 * [handleCorruptTimer] (28 ticks, then the arrow shot) ->
 * [handleCorruptTick] (60 ticks total, then release) with the
 * threesome actions handled by [handleThreesomeState] and the
 * animation overrides in [handleActionAnimationOverrides].
 *
 * <b>Pitfalls.</b> [setCurrentAction] persists the cum time on
 * [Action.THREESOME_CUM] (server) and guards the threesome loops.
 * [updateAITasks] despawns her when the [removeOnNextTick] flag is set or
 * her mommy disowns her. Damage is forwarded to the Galath
 * ([hurt]). The eyes controller only
 * animates while a corrupt target exists. [getYawRotation] flips
 * 180 degrees during the threesome render.
 */
open class ManglelieEntity : BaseGirlEntity {
    private var galathPartnerUUID: UUID? = null

    @JvmField
    var wasNoCorruptEntity: Boolean = true

    @JvmField
    var ZERO_VECTOR: Vec3 = Vec3.ZERO

    @JvmField
    var VELOCITY_0: Float = 0.0f

    @JvmField
    var wild: Boolean = true

    @JvmField
    var despawned: Boolean = false

    @JvmField
    var corrupting: Boolean = false

    @JvmField
    var yawOffset: Float = 0.0f

    @JvmField
    var rotationLerp: Float = 0.0f

    @JvmField
    var TICK_0: Float = 0.0f

    @JvmField
    var headPitch: Float = 0.0f

    @JvmField
    var removeOnNextTick: Boolean = false

    @JvmField
    var modelCodeLoaded: Boolean = false

    @JvmField
    var threesomeSlowStarted: Boolean = false

    @JvmField
    var threesomeFastStarted: Boolean = false

    @JvmField
    var threesomeCumDone: Boolean = false

    @JvmField
    var corruptionStage: Int = 2

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out ManglelieEntity>, level: Level) : super(type, level)

    /** Kept for Java call sites and reflection (NpcType uses getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.MANGLELIE.get(), level)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(GALATH_PARTNER_UUID, "")
        builder.define(CORRUPTING, false)
        builder.define(CORRUPT_TARGET_ID, -1)
        builder.define(CORRUPT_START_TIME, "")
        builder.define(SCARED, false)
    }

    override fun getDisplayNameText(): String = "Manglelie"

    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(1, AvoidPlayerGoal(this, 20.0f, 1.0, 1.2))
    }

    override fun getScaleFactor(): Float = 0.0f

    fun setCorrupting(corrupting: Boolean) {
        this.entityData.set(CORRUPTING, corrupting)
    }

    fun isCorrupting(): Boolean = this.entityData.get(CORRUPTING)

    fun getCorruptPlayerUUID(): UUID? {
        val corruptPlayerStr = this.entityData.get(GALATH_PARTNER_UUID)
        if ("" == corruptPlayerStr) {
            return null
        }

        return try {
            UUID.fromString(corruptPlayerStr)
        } catch (ex: Exception) {
            Main.LOGGER.error("could not parse the corrupt player UUID", ex)
            null
        }
    }

    override fun shouldRenderNameTag(): Boolean = !this.isCorrupting()

    fun getGalathPartner(server: Boolean): GalathEntity? {
        val uuid = this.getCorruptPlayerUUID() ?: return null

        val girl = if (server) getServerGirlEntity(uuid) else getClientGirlEntity(uuid)
        return girl as? GalathEntity
    }

    fun setGalathPartnerUUID(uuid: UUID?) {
        if (uuid == null) {
            this.entityData.set(GALATH_PARTNER_UUID, "")
        } else {
            this.entityData.set(GALATH_PARTNER_UUID, uuid.toString())
        }
    }

    override fun getYawRotation(): Float {
        var yaw = super.getYawRotation()
        if (isInThreesome(this)) {
            yaw += 180.0f
        }

        return yaw
    }

    fun markDespawned() {
        this.despawned = true
    }

    /**
     * SERVER, every AI tick: dispatches the whole Mang state machine —
     * model-code load, threesome positioning, partner binding, the corrupt
     * seek/start/tick/timer/finish cycle and gravity control; removes herself
     * when the despawn flag is set.
     */
    override fun updateAITasks() {
        if (this.removeOnNextTick) {
            this.discard()
        } else {
            this.loadModelCode()
            this.handleThreesomeState()
            super.updateAITasks()
            this.handlePartnerLook()
            this.handleCorruptInit()
            this.handleCorruptEntity()
            this.handleCorruptTick()
            this.handleCorruptStart()
            this.updateCorruptGravity()
            this.handleCorruptTimer()
            this.handleGalathPartner()
            this.handleCorruptFinish()
        }
    }

    fun handleCorruptFinish() {
        if (this.getCorruptPlayerUUID() != null) {
            this.wild = false
        }

        if (!this.wild) {
            if (this.getGalathPartner(true) == null) {
                Main.LOGGER.info("removed non-wild mang for lack of mommy")
                this.discard()
            }
        }
    }

    fun handleGalathPartner() {
        val galath = this.getGalathPartner(true)
        if (galath != null) {
            val partnerUuid = galath.getMangleliePartnerUUID()
            if (partnerUuid != null) {
                if (this.getGirlId() != partnerUuid) {
                    Main.LOGGER.info("removed non-wild mang cuz her mommy disowned her and got another mang")
                    this.discard()
                }
            }
        }
    }

    fun getCorruptStartTime(): Long {
        val timeStr = this.entityData.get(CORRUPT_START_TIME)
        if ("" == timeStr) {
            return -1L
        }

        return try {
            timeStr.toLong()
        } catch (ex: Exception) {
            -1L
        }
    }

    fun setCorruptStartTime(time: Long) {
        this.entityData.set(CORRUPT_START_TIME, time.toString())
        this.corrupting = false
    }

    /**
     * SERVER: the corrupt timer — 28 ticks after the corrupt start she fires
     * an arrow from the Galath's position (3.5 above) at the corrupt target
     * and marks the shot as done.
     */
    fun handleCorruptTimer() {
        val corruptStart = this.getCorruptStartTime()
        if (corruptStart != -1L) {
            val worldTime = this.level().gameTime
            if (!(worldTime.toFloat() < 28.0f + corruptStart.toFloat())) {
                if (!this.corrupting) {
                    val corruptEntity = this.getCorruptEntity()
                    if (corruptEntity != null) {
                        val galath = this.getGalathPartner(true)
                        if (galath != null) {
                            // 1.12 EntityTippedArrow(world, shooter)
                            val arrow = Arrow(this.level(), this, ItemStack(Items.ARROW), null)
                            val startPos = galath.position().add(0.0, 3.5, 0.0)
                            arrow.moveTo(startPos.x, startPos.y, startPos.z)
                            val targetPos = corruptEntity.position()
                            val dir = targetPos.subtract(startPos).normalize()
                            arrow.setDeltaMovement(dir.x * 4.0, dir.y * 4.0, dir.z * 4.0)
                            girlPlaySound(galath, SoundEvents.ARROW_SHOOT, true)
                            this.level().addFreshEntity(arrow)
                            this.corrupting = true
                        }
                    }
                }
            }
        }
    }

    /** 1.12 `addPotionEffect(PotionEffect)` was a no-op. */
    override fun addEffect(effectInstance: MobEffectInstance, entity: Entity?): Boolean = false

    fun updateCorruptGravity() {
        val corrupting = this.getCorruptPlayerUUID() != null
        this.setNoGravity(corrupting)
        this.noPhysics = corrupting
    }

    /** 1.12 `canBeCollidedWith()`. */
    override fun isPickable(): Boolean = this.getCorruptPlayerUUID() == null

    /** CLIENT (1.12 took a leading `Minecraft mc`, dropped like the base method). */
    override fun renderCustomModelTransform(scene: SexSceneEntity, entity: LivingEntity, partialTicks: Float): Vec3 {
        if (this.isLocallyRegistered()) {
            return super.renderCustomModelTransform(scene, entity, partialTicks)
        }

        if (!this.isCorrupting()) {
            return super.renderCustomModelTransform(scene, entity, partialTicks)
        }

        val galath = this.getGalathPartner(false) ?: return super.renderCustomModelTransform(scene, entity, partialTicks)

        return Client.renderGalathInteract(galath, partialTicks, scene)
    }

    fun getCorruptProgress(partialTicks: Float): Float {
        val corruptStart = this.getCorruptStartTime()
        if (corruptStart == -1L) {
            return 0.0f
        }

        val worldTime = this.level().gameTime
        val elapsed = (worldTime - corruptStart).toFloat()
        return (elapsed + partialTicks) / 28.0f
    }

    fun getCorruptEntity(): Entity? {
        val corruptId = this.entityData.get(CORRUPT_TARGET_ID)
        return if (corruptId == -1) null else this.level().getEntity(corruptId)
    }

    fun setCorruptEntity(entityId: Int) {
        this.entityData.set(CORRUPT_TARGET_ID, entityId)
        this.setCorruptStartTime(if (entityId == -1) -1L else this.level().gameTime)
    }

    fun handleCorruptEntity() {
        val corruptEntity = this.getCorruptEntity()
        if (corruptEntity != null) {
            val galath = this.getGalathPartner(true)
            if (galath == null) {
                this.setCorruptEntity(-1)
            } else if (!this.isCorrupting()) {
                this.setCorruptEntity(-1)
            } else {
                if (isGalathBlocked(corruptEntity, galath)) {
                    this.setCorruptEntity(-1)
                }
            }
        }
    }

    /**
     * SERVER: the corrupt target selection — while corrupting with no bound
     * entity, scans for a valid mob (per [isGalathBlocked])
     * within 15 blocks of the Galath and binds its entity id.
     */
    fun handleCorruptStart() {
        if (this.getCorruptEntity() == null) {
            if (this.isCorrupting()) {
                val galath = this.getGalathPartner(true)
                if (galath != null) {
                    if (galath.getInteractionPlayerUUID() == null) {
                        if (galath.getCurrentAction() != Action.MASTERBATE) {
                            val center = galath.blockPosition()
                            val range = BlockPos(15, 15, 15)

                            for (mob in this.level().getEntitiesOfClass(
                                Monster::class.java,
                                AABB(Vec3.atLowerCornerOf(center.subtract(range)), Vec3.atLowerCornerOf(center.offset(range))),
                            )) {
                                if (!isGalathBlocked(mob, galath)) {
                                    this.setCorruptEntity(mob.id)
                                    return
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * SERVER: releases the corrupt target 60 ticks after the start and
     * unbinds it.
     */
    fun handleCorruptTick() {
        val corruptEntity = this.getCorruptEntity()
        if (corruptEntity != null) {
            val galath = this.getGalathPartner(true)
            if (galath != null) {
                val corruptStart = this.getCorruptStartTime()
                if (corruptStart != -1L) {
                    val worldTime = this.level().gameTime
                    val elapsed = worldTime - this.getCorruptStartTime()
                    if (!(elapsed.toFloat() < 60.0f)) {
                        this.corrupting = false
                        this.setCorruptEntity(-1)
                    }
                }
            }
        }
    }

    /**
     * SERVER: completes the adoption — binds the Galath partner UUID both
     * ways, marks her corrupting and rides the mommy's head; un-anchors the
     * Galath if she was mid-hug.
     */
    fun handlePartnerLook() {
        val partnerUuid = this.galathPartnerUUID
        if (partnerUuid != null) {
            val partner = getServerGirlEntity(partnerUuid)
            if (partner is GalathEntity) {
                val galath = partner
                this.setGalathPartnerUUID(partnerUuid)
                galath.setMangleliePartnerUUID(this.getGirlId())
                this.setCorrupting(true)
                this.setCurrentAction(Action.RIDE_MOMMY_HEAD)
                this.galathPartnerUUID = null
                if (galath.getCurrentAction() == Action.HUG_MANG) {
                    galath.setAnchored(false)
                    galath.setCurrentAction(null)
                }
            }
        }
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() != Action.THREESOME_CUM || !Action.isAny(action, Action.THREESOME_FAST, Action.THREESOME_SLOW)) {
            if (!this.level().isClientSide && action == Action.THREESOME_CUM) {
                GirlSavedData.saveCumTime(this.getInteractionPlayerUUID(), this.level().gameTime)
            }

            super.setCurrentAction(action)
        }
    }

    /**
     * SERVER: the threesome position lock — while corrupting with no threesome
     * action running, keeps her anchored on the Galath's head.
     */
    fun handleThreesomeState() {
        if (this.isCorrupting() && !Action.isAnyAction(this, Action.THREESOME_SLOW, Action.THREESOME_CUM, Action.THREESOME_FAST)) {
            val galath = this.getGalathPartner(true)
            if (galath != null) {
                if (galath.isAlive && this.getGirlId() == galath.getMangleliePartnerUUID()) {
                    this.setYawRotation(0.0f)
                    this.setTargetPosition(galath.position())
                    this.setAnchored(true)
                } else {
                    Main.LOGGER.warn("A dead mommy has been saved onto a mang. Deleting her and creating a new one")
                    this.discard()
                }
            }
        }
    }

    override fun setYawRotation(yaw: Float) {
        super.setYawRotation(yaw)
    }

    /** CLIENT. */
    override fun transformRenderOffset(vec: Vec3, partialTicks: Float): Vec3 {
        if (!this.isCorrupting()) {
            return vec
        }

        if (isInThreesome(this)) {
            return vec
        }

        val galath = this.getGalathPartner(false)
        return if (galath == null) vec else Client.getLookVector(galath, partialTicks)
    }

    /**
     * SERVER: seeks an unbound Galath within 15 blocks and runs to her (RUN +
     * pathing); stands still when none is found.
     */
    fun handleCorruptInit() {
        if (!this.isCorrupting()) {
            if (this.getCorruptPlayerUUID() == null) {
                val center = this.blockPosition()
                val min = center.offset(-15, -15, -15)
                val max = center.offset(15, 15, 15)
                val aabb = AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max))
                val galaths = this.level().getEntitiesOfClass(GalathEntity::class.java, aabb)
                var chosen: GalathEntity? = null

                for (galath in galaths) {
                    if (galath.isAlive && galath.getMangleliePartner(true) == null && galath.onGround()) {
                        chosen = galath
                        break
                    }
                }

                if (chosen == null) {
                    if (this.getCurrentAction() == Action.RUN) {
                        this.setCurrentAction(null)
                        this.getNavigation().stop()
                    }
                } else if (this.getCurrentAction() != Action.RIDE_MOMMY_HEAD) {
                    this.setCurrentAction(Action.RUN)
                    val selfPos = this.position()
                    val partnerPos = chosen.position()
                    val delta = partnerPos.subtract(selfPos)
                    val yaw = TrigMath.sinDegrees(Math.atan2(delta.z, delta.x)).toFloat() - 90.0f
                    this.setYawRotation(yaw)
                    this.pathNavigator = this.getNavigation()
                    this.pathNavigator.stop()
                    this.pathNavigator.moveTo(chosen, 0.65f.toDouble())
                }
            }
        }
    }

    fun isLookingAtGalathEntity(entity: Entity, partialTicks: Float): Boolean {
        val galath = this.getGalathPartner(partialTicks == 1.0f) ?: return false

        val lookVec = EntityLookVectorHelper.getEntityLookVector(this, partialTicks)
        return this.isThrowBlocked(EntityLookVectorHelper.getEntityLookVector(entity, partialTicks).subtract(lookVec), galath, partialTicks)
    }

    fun isLookingAtGalathPoint(point: Vec3, partialTicks: Float): Boolean {
        val galath = this.getGalathPartner(partialTicks == 1.0f) ?: return false

        val lookVec = EntityLookVectorHelper.getEntityLookVector(this, partialTicks)
        return this.isThrowBlocked(point.subtract(lookVec), galath, partialTicks)
    }

    fun isThrowBlocked(delta: Vec3, galath: GalathEntity, partialTicks: Float): Boolean {
        val rotated = VectorMath.rotateByYaw(delta, RotationHelper.lerpFloat(galath.yHeadRotO, galath.yHeadRot, partialTicks.toDouble()))
        return rotated.x > 0.35
    }

    override fun tick() {
        super.tick()
        if (this.level().isClientSide) {
            this.handleParticleTick()
        }
    }

    /** CLIENT. */
    fun handleParticleTick() {
        if (ClientHooks.localPlayer()!!.tickCount.toFloat() % 7.0f == 0.0f) { // java-npe
            if (Client.isRendererCorrupting(this)) {
                val galath = this.getGalathPartner(false)
                if (galath != null) {
                    val corruptTarget = this.getCorruptTarget()
                    if (corruptTarget == null) {
                        this.yawOffset = 0.0f
                        this.rotationLerp = 0.0f
                    } else {
                        val targetPos = corruptTarget.position().add(0.0, corruptTarget.eyeHeight.toDouble(), 0.0)
                        val mouthPos = galath.position().add(galath.getCachedBoneOffset("mangPos")).add(this.getCachedBoneOffset("head"))
                        val delta = mouthPos.subtract(targetPos)
                        var yaw = (TrigMath.sinDegrees(Math.atan2(delta.z, delta.x)) + 90.0).toFloat()
                        val aimYaw = GalathEntity.getAimYaw(galath, 0.0f)
                        yaw -= galath.yHeadRot
                        if (aimYaw != null) {
                            yaw -= aimYaw
                        }

                        this.yawOffset = if (Math.abs(WorldUtils.normalizeAngleDiff(0.0f, yaw)) < 80.0f) -TrigMath.wrapDegrees(yaw) else 0.0f
                        this.rotationLerp = if (this.yawOffset == 0.0f) 0.0f else ThreadNames.clampDouble(-delta.y / 2.0, -0.75, 0.75).toFloat()
                    }
                }
            }
        }
    }

    /**
     * SERVER: damage is forwarded to her Galath mommy (unless void damage);
     * Mang herself never takes hits.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if ((source.`is`(DamageTypes.FELL_OUT_OF_WORLD) || source.`is`(DamageTypes.GENERIC_KILL))) {
            return super.hurt(source, amount)
        }

        val galath = this.getGalathPartner(true) ?: return super.hurt(source, amount)

        galath.hurt(source, amount)
        return false
    }

    fun getCorruptTarget(): Entity? {
        var corruptEntity: Entity? = this.getCorruptEntity()
        if (corruptEntity != null) {
            return corruptEntity
        }

        for (player in this.level().players()) {
            val dist = player.distanceTo(this)
            if (!(dist > 6.0f) && (corruptEntity == null || corruptEntity.distanceTo(this) > dist)) {
                corruptEntity = player
            }
        }

        return corruptEntity
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        val uuid = this.getCorruptPlayerUUID()
        tag.putString(MOMMY_NBT_KEY, uuid?.toString() ?: "")
        tag.putBoolean("sexmod:iswild", this.wild)
        if (this.despawned) {
            tag.putBoolean("sexmod:despawned", true)
        }
    }

    /** 1.12 `readFromNBT` (the whole-entity load, not `readEntityFromNBT`). */
    override fun load(compound: CompoundTag) {
        super.load(compound)
        val mommyStr = compound.getString(MOMMY_NBT_KEY)
        if ("" != mommyStr) {
            this.galathPartnerUUID = UUID.fromString(mommyStr)
        }

        if (compound.getBoolean("sexmod:despawned")) {
            this.removeOnNextTick = true
        }

        this.wild = compound.getBoolean("sexmod:iswild")
    }

    override fun supportsCustomModels(): Boolean = false

    override fun setCustomModelCode(modelCode: String) {
        super.setCustomModelCode(modelCode)
        GirlWorldData.setCustomModelCode(this)
    }

    fun loadModelCode() {
        if (!this.modelCodeLoaded) {
            this.setCustomModelCode(GirlWorldData.getCustomModelCode(this))
            this.modelCodeLoaded = true
        }
    }

    override fun getNextAction(action: Action): Action? = null

    override fun getCumAction(action: Action): Action? {
        if (Action.isAny(action, Action.THREESOME_FAST, Action.THREESOME_SLOW)) {
            this.threesomeSlowStarted = true
        }

        return null
    }

    override fun reinitTasks() {
        if (this.isCorrupting()) {
            this.setCurrentAction(Action.RIDE_MOMMY_HEAD)
            this.setYawRotation(0.0f)
            // 1.12 dataManager.setDirty(YAW_ROTATION)
            this.entityData.set(YAW_ROTATION, this.entityData.get(YAW_ROTATION), true)
        }
    }

    /** 1.12 `getCanSpawnHere()`. */
    override fun checkSpawnRules(level: LevelAccessor, reason: MobSpawnType): Boolean {
        if (!super.checkSpawnRules(level, reason)) {
            return false
        }

        val pos = this.blockPosition()
        val positions = ArrayList<BlockPos>()
        positions.addAll(BeeWorldData.hivePositions)
        positions.addAll(BeeWorldData.flowerPositions)

        for (hivePos in positions) {
            if (Math.sqrt(pos.distSqr(hivePos)) < 700.0) {
                return false
            }
        }

        BeeWorldData.addHivePosition(pos, BeeWorldData.flowerPositions)
        return true
    }

    /**
     * CLIENT: the threesome action overrides — THREESOME_CUM ends the scene
     * (reset both girls, clear cum trails), the slow/fast transitions swap
     * between the soft/hard/back shared animations and drive the Galath's
     * masterbate/pussy-licking mirror actions.
     */
    override fun handleActionAnimationOverrides(action: Action, animName: String, started: Boolean, event: AnimationState<*>): Boolean {
        if (action == Action.THREESOME_CUM) {
            this.threesomeSlowStarted = false
            this.threesomeFastStarted = false
            this.threesomeCumDone = false
            this.corruptionStage = 2
            this.resetCameraAndPhysics()
            val galath = this.getGalathPartner(false)
            if (galath != null) {
                galath.resetCameraAndPhysics()
                Client.spawnCummyTrails(galath)
            }

            Client.spawnCummyTrails(this)
            return true
        } else if (this.threesomeSlowStarted && action == Action.THREESOME_FAST) {
            this.setCurrentAction(Action.THREESOME_CUM)
            this.createAnimation("animation.shared.double_holding_cum", true, event, true)
            val galath = this.getGalathPartner(false)
            galath?.setCurrentAction(Action.MASTERBATE_SITTING_CUM)

            return true
        } else if ((this.threesomeSlowStarted || started) && action == Action.THREESOME_SLOW) {
            this.threesomeFastStarted = false
            this.setCurrentAction(Action.THREESOME_FAST)
            this.createAnimation("animation.shared.double_holding_soft", true, event, true)
            val galath = this.getGalathPartner(false)
            galath?.startMasterbateSitting()

            return true
        } else {
            if (this.threesomeSlowStarted) {
                return false
            }

            if (started && !this.threesomeFastStarted && action == Action.THREESOME_FAST) {
                this.threesomeFastStarted = true
                this.createAnimation("animation.shared.double_holding_hard", true, event, true)
                return true
            }

            if (!started && action == Action.THREESOME_FAST) {
                this.threesomeCumDone = true
                this.setCurrentAction(Action.THREESOME_SLOW)
                this.createAnimation("animation.shared.double_holding_back", true, event, true)
                val galath = this.getGalathPartner(false)
                galath?.startPussyLicking()

                return true
            } else if (this.threesomeCumDone && action == Action.THREESOME_SLOW) {
                this.threesomeCumDone = false
                this.createAnimation("animation.shared.double_holding_slow", true, event, true)
                return true
            } else {
                return false
            }
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        val controller = event.controller
        if (this.eyesController === controller) {
            if (this.getCorruptEntity() == null) {
                return PlayState.STOP
            }

            this.createAnimation("animation.manglelie.angry_face", true, event)
            return PlayState.CONTINUE
        } else if (this.movementController === controller) {
            if (this.getCurrentAction() == Action.NULL && !this.isCorrupting()) {
                if (Math.abs(this.xo - this.x) + Math.abs(this.zo - this.z) > 0.0) {
                    if (this.entityData.get(SCARED)) {
                        this.createAnimation("animation.manglelie.scared_run", true, event)
                    } else {
                        this.createAnimation("animation.manglelie.walk", true, event)
                    }

                    this.setYRot(this.yHeadRot)
                    return PlayState.CONTINUE
                } else {
                    this.createAnimation("animation.manglelie.idle", true, event)
                    return PlayState.CONTINUE
                }
            } else {
                return PlayState.STOP
            }
        } else {
            when (this.getCurrentAction()) {
                Action.RUN -> this.createAnimation("animation.manglelie.running", true, event)
                Action.RIDE_MOMMY_HEAD -> this.createAnimation("animation.manglelie.sit_on_galath", true, event)
                Action.THREESOME_SLOW -> if (this.threesomeCumDone) {
                    this.createAnimation("animation.shared.double_holding_back", true, event)
                } else {
                    this.playRandomizedAnimation("animation.shared.double_holding_slow", 4, 0.33f, event)
                }
                Action.THREESOME_FAST -> if (this.threesomeFastStarted) {
                    this.playRandomizedAnimation("animation.shared.double_holding_hard", 3, 0.33f, event)
                } else {
                    this.createAnimation("animation.shared.double_holding_soft", true, event)
                }
                Action.THREESOME_CUM -> this.createAnimation("animation.shared.double_holding_cum", true, event)
                else -> return PlayState.STOP
            }

            return PlayState.CONTINUE
        }
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * threesome (pound sounds, cum trails from the `semenEmitter` bone,
     * black screen) — `cs0/1/2` select the pose variant ([corruptionStage]),
     * `sexui` shows the horny meter.
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        controllers.add(this.movementController!!) // java-npe
        controllers.add(this.eyesController!!) // java-npe
        val action = this.actionController!! // java-npe
        action.registerSoundListener { sound ->
            var fallDoubleSemen = false
            when (sound.keyframeData.sound) {
                "pound" -> {
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                    this.addToHornyMeterIfControlled(0.02)
                }
                "cs0" -> this.corruptionStage = 0
                "cs1" -> this.corruptionStage = 1
                "cs2" -> this.corruptionStage = 2
                "sexui" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "doubleSemen0" -> {
                    this.playRandomSoundAtVolume(SoundHandler.MISC_INSERTS, 6.0f)
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                    // Java falls through into "doubleSemen"
                    fallDoubleSemen = true
                }
                "doubleSemen" -> fallDoubleSemen = true
                "blackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
            }

            if (fallDoubleSemen) {
                Client.registerSemenTrail(this)
            }
        }
        controllers.add(action)
    }

    /**
     * Forge handler: Mang's arrows must never damage other girls — the
     * projectile impact on a [BaseGirlEntity] is cancelled.
     * (1.12 `ProjectileImpactEvent.Arrow` -> `ProjectileImpactEvent` filtered to arrows.)
     */
    open class ArrowImpactHandler {
        @SubscribeEvent
        open fun handleArrowHit(event: ProjectileImpactEvent) {
            val arrow = event.projectile as? AbstractArrow ?: return
            val rayTrace = event.rayTraceResult
            if (arrow.owner is ManglelieEntity) {
                if (rayTrace is EntityHitResult && rayTrace.entity is BaseGirlEntity) {
                    event.isCanceled = true
                }
            }
        }
    }

    /** Client-only helpers (PORTING.md §2.3 Rule C). */
    private object Client {
        fun renderGalathInteract(galath: GalathEntity, partialTicks: Float, scene: SexSceneEntity): Vec3 {
            ManglelieRenderer.renderGalathInteract(galath, partialTicks, scene)
            return ManglelieRenderer.getLookVector(galath, partialTicks)
        }

        fun getLookVector(galath: GalathEntity, partialTicks: Float): Vec3 = ManglelieRenderer.getLookVector(galath, partialTicks)

        fun isRendererCorrupting(manglelie: ManglelieEntity): Boolean = ManglelieRenderer.isCorrupting(manglelie)

        fun spawnCummyTrails(girl: BaseGirlEntity) {
            CummyEntity.spawnCummyTrails(girl)
        }

        fun registerSemenTrail(manglelie: ManglelieEntity) {
            CummyEntity.registerTrail(
                DynamicTrailRenderer(
                    10,
                    IPositionProvider { girl ->
                        val emitterPos = girl.getBoneWorldPosition("semenEmitter")
                        val semenDir = girl.getBoneWorldPosition("semenDir")
                        emitterPos.subtract(semenDir).normalize()
                    },
                    ITargetProvider { girl -> girl.getCachedBoneOffset("semenEmitter").add(girl.getTargetPosition()) },
                    manglelie, 0.3f, 0.3f,
                ),
            )
        }
    }

    companion object {
        const val MOMMY_NBT_KEY: String = "sexmod:mommy"
        const val am: Float = 60.0f
        const val ag: Float = 4.0f
        const val SPEED_3_5: Float = 3.5f
        const val ah: Float = 28.0f
        const val ae: Float = 15.0f
        const val ANGLE_15: Float = 15.0f
        const val SCALE_0_65: Float = 0.65f
        const val ao: Float = 3.65f
        const val RANGE_6: Float = 6.0f
        const val ak: Float = 80.0f
        const val DISTANCE_700: Float = 700.0f

        @JvmField
        val GALATH_PARTNER_UUID: EntityDataAccessor<String> = SynchedEntityData.defineId(ManglelieEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val CORRUPTING: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(ManglelieEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val CORRUPT_TARGET_ID: EntityDataAccessor<Int> = SynchedEntityData.defineId(ManglelieEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val CORRUPT_START_TIME: EntityDataAccessor<String> = SynchedEntityData.defineId(ManglelieEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val SCARED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(ManglelieEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = BaseGirlEntity.createAttributes()

        /** `ManglelieNpcModel.isInThreesome`, inlined here because the model class is client-only. */
        private fun isInThreesome(girl: BaseGirlEntity): Boolean =
            Action.isAnyAction(girl, Action.THREESOME_SLOW, Action.THREESOME_FAST, Action.THREESOME_CUM)

        @JvmStatic
        fun getGalathPartnerOf(girl: BaseGirlEntity, server: Boolean): GalathEntity? =
            if (girl !is ManglelieEntity) null else girl.getGalathPartner(server)

        /**
         * SERVER: valid-corrupt-target test — the target must be alive, in the
         * Galath's dimension, daylight-visible, within 15 blocks horizontally and
         * in front of the Galath's aim.
         */
        @JvmStatic
        fun isGalathBlocked(entity: Entity, galath: GalathEntity): Boolean {
            if (!entity.isAlive) {
                return true
            }

            if (entity.level().dimension() != galath.level().dimension()) {
                return true
            }

            if (!MobPredicates.isValidTarget(entity)) {
                return true
            }

            if (!MobPredicates.isDaylight(galath.level(), galath.getTargetPosition().add(0.0, galath.eyeHeight.toDouble(), 0.0), entity)) {
                return true
            }

            val offset = entity.position().subtract(galath.position())
            if (offset.x * offset.x + offset.z * offset.z > 225.0) {
                return true
            }

            val aimYaw = GalathEntity.getAimYaw(galath, 0.0f)
            val yaw = aimYaw ?: galath.yHeadRot
            val rotated = VectorMath.rotateByYaw(offset, yaw)
            return rotated.z < 0.0
        }
    }
}
