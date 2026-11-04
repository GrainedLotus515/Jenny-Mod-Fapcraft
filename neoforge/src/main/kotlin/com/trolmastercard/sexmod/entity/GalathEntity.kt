package com.trolmastercard.sexmod.entity

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.GalathFlightHud
import com.trolmastercard.sexmod.client.model.GirlAnimationController
import com.trolmastercard.sexmod.entity.ai.DoorInteractAiGoal
import com.trolmastercard.sexmod.entity.ai.WatchClosestGirlGoal
import com.trolmastercard.sexmod.entity.api.IGalath
import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerCamPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket2
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.AllieWorldData
import com.trolmastercard.sexmod.util.BeeWorldData
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.GirlWorldData
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import com.trolmastercard.sexmod.util.Vector4d
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.BossEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.TemptGoal
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.monster.WitherSkeleton
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.TorchBlock
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.entity.PartEntity
import net.neoforged.neoforge.event.entity.EntityMountEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent
import net.neoforged.neoforge.fluids.FluidType
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Galath — the flying succubus boss girl (implements
 * [SexEntityPart.IEntityMultiPart], [IGalath]). Wild Galaths spawn near wither
 * skeleton/blaze hives, fly via the [GalathFlightData] state machine and
 * attack players/mobs with dragon charges ([DragonEntity]), sword
 * attacks and a rape pounce. Defeating her (knockout) lets a player "corrupt"
 * her; once corrupted she grants a [GalathCoinItem] and becomes the
 * player's bound succubus with cowgirl/anal/threesome/morning-blowjob scenes
 * and the Manglelie partnership.
 *
 * <b>State.</b> Own data keys (1.12 ids 111..122; 1.21 assigns dense ids, see
 * PORTING.md §2.6): [TARGET_ENTITY_ID] = target entity id, [ATTACK_PROGRESS] =
 * attack/sword progress, [LEFT_ENERGY_BALL_ALIVE]/[RIGHT_ENERGY_BALL_ALIVE] =
 * energy-ball alive flags, [MIRROR_SIDE] = mirror side,
 * [SWORD_ATTACK_PROGRESS] = sword attack progress,
 * [FLIGHT_TARGET_POS] = flight target pos ("x|y|z"),
 * [PARALYZED] = paralyzed flag, [RAPE_PROGRESS] = rape
 * progress, [HIDE_EFFECTS_FLAG] = knock-out state,
 * [WildSlimeFaceLayer] = Manglelie partner UUID,
 * [SPRINTING] = sprint flag. The two [SexEntityPart]
 * hitboxes ([energyBallHitboxLeft], [energyBallHitboxRight]) back
 * the energy-ball attack.
 *
 * <b>Flow.</b> Wild: [updateAITasks] -> [tickFlightSystems] drives the
 * flight state machine ([GalathFlightState.initFlightData] picks a random
 * [GalathFlightData] action when a target is in range, see
 * [GalathFlightState.acquireFlightTarget]); [setFlightVelocity] (dragon hit)
 * sends her into [Action.KNOCK_OUT_FLY] -> KNOCK_OUT_GROUND
 * ([GalathFlightState.handleKnockout]) -> KNOCK_OUT_STAND_UP. The corrupting player
 * then right-clicks ([GalathInteractions.processGirlInteract])
 * which starts [Action.CORRUPT_INTRO]; `handleCorruptCum` (server)
 * advances CORRUPT_CUM -> [Action.GIVE_COIN] ->
 * [grantCoinAndBindMaster] grants the coin and binds the master.
 * Tamed: scenes enter through [GalathInteractions.processMasterInteract] +
 * [GalathInteractions.doAction]; [handleRapeState] and
 * [GalathSceneTicks.handleRapeCum]/[GalathSceneTicks.endTamedCorruptScene] end
 * rape/corrupt scenes (player reset via [SetPlayerMovementPacket]).
 *
 * <b>Pitfalls.</b> [setCurrentAction] blocks transitions out
 * of GALATH_DE_SUMMON, guards all cum loops, persists cum time via
 * [GirlSavedData] and fires the GIVE_COIN/HUG_MANG/MORNING_BLOWJOB_CUM
 * teardown hooks. The boss bar ([bossBar]) is only visible for wild Galaths
 * ([tickWildAi]); `despawned` girls only update the Manglelie world
 * data. [getAimYaw] mutates the render yaw — many
 * systems depend on it. The energy-ball hitboxes must be active exactly while
 * [summonSkeletonTicks] is in [9, 30] (see [GalathEnergyBalls.resetEnergyBalls]).
 *
 * Port notes: the 1.12 `IEntityMultiPart`/`MultiPartEntityPart` pair is the
 * NeoForge [PartEntity] protocol ([isMultipartEntity], [getParts], [setId]
 * numbering the parts after the parent like `EnderDragon`); part damage still
 * reaches [attackEntityFromPart]. The 1.12 tracking hooks
 * (`addTrackingPlayer`/`removeTrackingPlayer`) are [startSeenByPlayer]/[stopSeenByPlayer].
 */
open class GalathEntity : BaseGirlEntity, SexEntityPart.IEntityMultiPart, IGalath {
    @JvmField
    var bossBar: ServerBossEvent = ServerBossEvent(Component.literal(this.getDisplayNameText()), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS)

    @JvmField
    val energyBallHitboxLeft: SexEntityPart = SexEntityPart(this, "energyBallHitBox", 0.75f, 0.75f)

    @JvmField
    val energyBallHitboxRight: SexEntityPart = SexEntityPart(this, "energyBallHitBox", 0.75f, 0.75f)

    @JvmField
    var activeFlight: GalathFlightData? = null

    @JvmField
    var flightTargetPosition: Vec3? = null

    @JvmField
    var lastFlightPosition: Vec3? = null

    @JvmField
    var rapeChargeTicks: Int = 0

    @JvmField
    var chargeStartPosition: Vec3? = null

    @JvmField
    var summonedSkeletons: MutableList<WitherSkeleton> = ArrayList()

    @JvmField
    var headRotationX: Float = 0.0f

    @JvmField
    var dashStartTick: Long = -1L

    @JvmField
    var dashEndTick: Long = -1L

    @JvmField
    var bodyRotationY: Float = 0.0f

    @JvmField
    var bodyScaleY: Float = 0.0f

    @JvmField
    var deathHandled: Boolean = false

    @JvmField
    var clientTargetPosition: Vec3? = null

    @JvmField
    var markedDespawned: Boolean = false

    @JvmField
    var previousPosition: Vec3? = null

    @JvmField
    var predictedPosition: Vec3? = null

    @JvmField
    var velocity: Vec3? = null

    @JvmField
    var boostSpeed: Float = 0.0f

    @JvmField
    var corruptIntroActive: Boolean = false

    @JvmField
    var summonSkeletonTicks: Int = 0

    @JvmField
    var headTiltZ: Double = 0.0

    @JvmField
    var headTiltX: Double = 0.0

    @JvmField
    var prevHeadTiltZ: Double = 0.0

    @JvmField
    var prevHeadTiltX: Double = 0.0

    @JvmField
    var customModelCodeLoaded: Boolean = false

    @JvmField
    var currentPath: Path? = null

    @JvmField
    var wanderTarget: BlockPos? = null

    @JvmField
    var wanderTicks: Int = 0

    @JvmField
    var previousAction: Action? = null

    @JvmField
    var rapeCumTicks: Int = 0

    @JvmField
    var standUpTicks: Int = 0

    @JvmField
    var knockoutGroundTicks: Int = 0

    @JvmField
    var lastHurtSoundTime: Long = 0L

    @JvmField
    var morningBlowjobStarted: Boolean = false

    @JvmField
    var despawned: Boolean = false

    @JvmField
    var rapeVariant: Int = 0

    @JvmField
    var corruptHard: Boolean = false

    @JvmField
    var masterbateSittingPending: Boolean = false

    @JvmField
    var pussyLickingForward: Boolean = false

    @JvmField
    var bedSoftAnimation: Boolean = false

    @JvmField
    var bedBackAnimation: Boolean = false

    @JvmField
    var renderSword: Boolean = false

    @JvmField
    var spawnSwordParticles: Boolean = false

    @JvmField
    var drawStars: Boolean = true

    @JvmField
    var wingsAnimated: Boolean = false

    @JvmField
    var alternateMoan: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out GalathEntity>, level: Level) : super(type, level) {
        // Forge MC-158205 (EnderDragon): part ids must be the successors of the parent id
        this.setId(ENTITY_COUNTER.getAndAdd(2 + 1) + 1)
    }

    /** Kept for Java call sites and reflection (NpcType uses getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.GALATH.get(), level)

    constructor(world: Level, player: Player, pos: Vec3, spawnStructure: Boolean) : this(world) {
        val uuid = player.uuid
        this.entityData.set(MASTER, uuid.toString())
        this.bossBar.isVisible = false
        this.wanderTarget = this.blockPosition()
        val npcName = AllieWorldData.getNpcName(uuid, NpcType.GALATH)
        if (npcName != null) {
            super.setCustomNameOverride(npcName)
        }

        if (!spawnStructure) {
            if (this.random.nextFloat() > 0.1f) {
                this.setCurrentAction(Action.GALATH_SUMMON)
            } else {
                this.setCurrentAction(Action.MASTERBATE)
                this.setYawRotation(180.0f - TrigMath.sinDegrees(Math.atan2(pos.x - player.x, pos.z - player.z)).toFloat())
                ThreadNames.createDaemonThread(8000) {
                    val master = this.getMasterPlayer()
                    if (master != null) {
                        if (master.isAlive) {
                            this.setTargetPosition(master.position())
                            this.setYawRotation(master.yRot + 180.0f)
                            this.setCurrentAction(Action.RAPE_INTRO)
                            this.setInteractionPlayerUUID(master.uuid)
                            this.setAnchored(true)
                        }
                    }
                }
            }
        }
    }

    constructor(world: Level, player: Player, pos: Vec3) : this(world, player, pos, false)

    override fun setCustomModelCode(modelCode: String) {
        super.setCustomModelCode(modelCode)
        GirlWorldData.setCustomModelCode(this)
    }

    override fun getDisplayNameText(): String = "Galath"

    override fun getScaleFactor(): Float = if (this.getMangleliePartnerUUID() == null) 0.5f else 1.35f

    /** 1.12 `getEyeHeight()` = 1.9 (final in 1.21, PORTING.md §2.1). */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.9f)

    override fun hasMaster(): Boolean = super.hasMaster()

    /** 1.12 `isPushedByWater()`. */
    override fun isPushedByFluid(): Boolean = false

    /** 1.12 `handleJumpWater()` (NeoForge routes every fluid jump through `jumpInFluid`). */
    override fun jumpInFluid(type: FluidType) {
        if (this.hasMaster()) {
            super.jumpInFluid(type)
        }
    }

    override fun getWaterSlowDown(): Float = if (this.hasMaster()) super.getWaterSlowDown() else 0.0f

    override fun isInWater(): Boolean = if (this.hasMaster()) super.isInWater() else false

    /** 1.12 `handleWaterMovement()`. */
    override fun updateInWaterStateAndDoFluidPushing(): Boolean =
        if (this.hasMaster()) super.updateInWaterStateAndDoFluidPushing() else false

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(TARGET_ENTITY_ID, -1)
        builder.define(ATTACK_PROGRESS, 0)
        builder.define(LEFT_ENERGY_BALL_ALIVE, true)
        builder.define(RIGHT_ENERGY_BALL_ALIVE, true)
        builder.define(MIRROR_SIDE, false)
        builder.define(FLIGHT_TARGET_POS, "null")
        builder.define(SWORD_ATTACK_PROGRESS, -1)
        builder.define(PARALYZED, false)
        builder.define(RAPE_PROGRESS, 0.0f)
        builder.define(HIDE_EFFECTS_FLAG, false)
        builder.define(WildSlimeFaceLayer, "")
        builder.define(SPRINTING, false)
    }

    /** 1.12 `initEntityAI()`: replaces the base goal set (no wander goal). */
    override fun registerGoals() {
        val watch = WatchClosestGirlGoal(this, Player::class.java, 3.0f, 1.0f)
        this.watchClosestGirlGoal = watch
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(2, TemptGoal(this, 0.4, Ingredient.of(*TEMPTATION_ITEMS.toTypedArray()), false))
        this.goalSelector.addGoal(3, DoorInteractAiGoal(this))
        this.goalSelector.addGoal(5, watch)
    }

    /** 1.12 `addTrackingPlayer`. */
    override fun startSeenByPlayer(serverPlayer: ServerPlayer) {
        super.startSeenByPlayer(serverPlayer)
        this.bossBar.addPlayer(serverPlayer)
    }

    /** 1.12 `removeTrackingPlayer`. */
    override fun stopSeenByPlayer(serverPlayer: ServerPlayer) {
        super.stopSeenByPlayer(serverPlayer)
        this.bossBar.removePlayer(serverPlayer)
    }

    override fun getTargetPosition(): Vec3 {
        val clientTarget = this.clientTargetPosition
        return if (this.level().isClientSide && clientTarget != null) clientTarget else super.getTargetPosition()
    }

    fun getMangleliePartnerUUID(): UUID? {
        val partnerStr = this.entityData.get(WildSlimeFaceLayer)
        if ("" == partnerStr) {
            return null
        }

        return try {
            UUID.fromString(partnerStr)
        } catch (ex: Exception) {
            null
        }
    }

    fun getMangleliePartner(server: Boolean): ManglelieEntity? {
        val uuid = this.getMangleliePartnerUUID() ?: return null

        val partner = if (server) getServerGirlEntity(uuid) else getClientGirlEntity(uuid)
        return partner as? ManglelieEntity
    }

    fun setMangleliePartnerUUID(uuid: UUID?) {
        this.entityData.set(WildSlimeFaceLayer, uuid?.toString() ?: "")
    }

    fun markDespawnedWithPartner() {
        this.markedDespawned = true
        val manglelie = this.getMangleliePartner(true)
        manglelie?.markDespawned()
    }

    /** SERVER: ends the rape scene, see [GalathActions.handleRapeState]. */
    fun handleRapeState() {
        GalathActions.handleRapeState(this)
    }

    fun getFlightTargetPos(): Vec3 {
        val parts = this.entityData.get(FLIGHT_TARGET_POS).split("|")
        return Vec3(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
    }

    fun setFlightTargetPos(pos: Vec3?) {
        val p = pos!! // java-npe
        this.entityData.set(FLIGHT_TARGET_POS, p.x.toString() + "|" + p.y + "|" + p.z)
    }

    fun getSwordAttackProgress(): Int = this.entityData.get(SWORD_ATTACK_PROGRESS)

    fun setSwordAttackProgress(progress: Int) {
        this.entityData.set(SWORD_ATTACK_PROGRESS, progress)
    }

    /**
     * 1.12 `Entity.isNonBoss()` = false. 1.21 has no such hook (boss handling is
     * per-feature); kept as a plain method for callers.
     */
    fun isNonBoss(): Boolean = false

    override fun isHuggingManglelie(): Boolean {
        return when (this.getCurrentAction()) {
            Action.HUG_MANG, Action.MORNING_BLOWJOB_SLOW, Action.MORNING_BLOWJOB_FAST, Action.MORNING_BLOWJOB_CUM -> true
            else -> false
        }
    }

    fun updatePredictedPosition() {
        var vel = this.deltaMovement
        this.previousPosition = this.position()
        this.predictedPosition = this.position().add(vel)
        vel = vel.scale(0.9)
        this.velocity = vel
    }

    /**
     * BOTH sides: the per-tick split — tamed girls tick the master systems
     * ([GalathRiding.updateRidingGravity] gravity + [tickTamedSystems]),
     * wild girls tick the flight UI (boss bar, gravity, flight data) and the
     * wild systems ([GalathSceneTicks.tickWildSystems]). CLIENT
     * additionally ticks the coin-give particles
     * ([GalathSceneTicks.tickGiveCoinScene]).
     */
    override fun tick() {
        val hasMasterFlag = this.hasMaster()
        if (hasMasterFlag) {
            GalathRiding.updateRidingGravity(this)
        } else {
            this.updateFlightUI()
        }

        this.updatePredictedPosition()
        super.tick()
        if (hasMasterFlag) {
            this.tickTamedSystems()
        } else {
            GalathSceneTicks.tickWildSystems(this)
        }

        if (this.level().isClientSide) {
            GalathSceneTicks.tickGiveCoinScene(this)
        }
    }

    override fun canBeInteractedWith(): Boolean = false

    /**
     * SERVER: tamed per-tick systems — soft fall damping, riding-player yaw
     * sync, the carry camera pitch, the boost impulse, wing/effect flags, and
     * the rape/corrupt scene enders ([GalathSceneTicks.handleRapeCum],
     * [GalathSceneTicks.endTamedCorruptScene]).
     */
    fun tickTamedSystems() {
        val motion = this.deltaMovement
        if (!this.isInWater && !this.isNoGravity && motion.y < 0.0 && this.getCurrentAction() != Action.MASTERBATE) {
            this.setDeltaMovement(motion.x, motion.y * 0.4f, motion.z)
        }

        GalathRiding.syncRenderYawToRider(this)
        this.updateFlightRenderAngles()
        GalathRiding.updateMasterLookPitch(this)
        GalathRiding.tickBoostFlight(this)
        GalathSceneTicks.updateWingsAnimated(this)
        GalathSceneTicks.endTamedCorruptScene(this)
        GalathSceneTicks.handleRapeCum(this)
        if (this.getTargetEntity() == null) {
            this.renderSword = false
        }
    }

    fun updateFlightUI() {
        GalathSceneTicks.updateGravity(this)
        GalathSceneTicks.updateHealthBar(this)
        GalathEnergyBalls.tickSummonSkeletonProgress(this)
    }

    override fun isCustomType(): Boolean = if (this.getCurrentAction() != Action.CORRUPT_INTRO) false else this.corruptIntroActive

    /** 1.12 `collideWithNearbyEntities()`. */
    override fun pushEntities() {
    }

    /** 1.12 `addPotionEffect(PotionEffect)` was a no-op. */
    override fun addEffect(effectInstance: MobEffectInstance, entity: Entity?): Boolean = false

    /** CLIENT. */
    override fun resetAnimationControllerTicks() {
        if (this.getCurrentAction() != Action.GALATH_DE_SUMMON) {
            this.actionController?.setTickOffset(0.0)
        }
    }

    override fun getEffectiveDisplayName(): String {
        val master = this.getMasterPlayer()
        return if (master == null) {
            super.getEffectiveDisplayName()
        } else {
            String.format("%s %s[%s]", super.getEffectiveDisplayName(), ChatFormatting.DARK_PURPLE, master.gameProfile.name)
        }
    }

    override fun getFlightData(): Vector4d = Vector4d(this.headTiltZ, this.headTiltX, this.prevHeadTiltZ, this.prevHeadTiltX)

    fun updateFlightRenderAngles() {
        this.prevHeadTiltZ = this.headTiltZ
        this.prevHeadTiltX = this.headTiltX
        val delta = this.predictedPosition!!.subtract(this.previousPosition!!) // java-npe
        val rotated = VectorMath.rotateByYaw(delta, this.yBodyRot + 180.0f)
        this.headTiltZ = TrigMath.toRadians(ThreadNames.clampDouble(rotated.z * 40.0, -50.0, 50.0)).toDouble()
        this.headTiltX = TrigMath.toRadians(ThreadNames.clampDouble(rotated.x * 40.0, -50.0, 50.0)).toDouble()
    }

    /**
     * SERVER: the paralysis knock — dragon explosions call this; once
     * paralyzed she stops the flight action, is flung away from the source
     * ([Action.KNOCK_OUT_FLY]) and broadcasts the "time to corrupt her"
     * message with the effect particles.
     */
    fun setFlightVelocity(targetPos: Vec3?) {
        if (!this.entityData.get(PARALYZED)) {
            this.entityData.set(PARALYZED, true)
            this.activeFlight?.updateFlight(this)

            this.activeFlight = null
            val pos = this.position()
            val random = this.random
            val vel = if (targetPos == null) {
                Vec3(random.nextDouble(), random.nextDouble(), random.nextDouble()).normalize()
            } else {
                pos.subtract(targetPos).normalize()
            }
            this.setVelocity(vel.x * 1.0, 1.0, vel.z * 1.0)
            this.setCurrentAction(Action.KNOCK_OUT_FLY)
            this.setNoGravity(false)
            this.noPhysics = false
            this.getNavigation().stop()
            playRandomSound(this, SoundHandler.GIRLS_GALATH_AAA, true)
        }
    }

    fun sendTrackingMessage(entity: Entity?) {
        sendMessageToTrackingPlayers(this, ChatFormatting.YELLOW.toString() + "Galath is paralyzed! Now it's time to corrupt her")
        sendMessageToTrackingPlayers(this, ChatFormatting.GRAY.toString() + "(Walk to her and right click her)")
        PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(this.position(), true), this)
        this.setFlightVelocity(null)
        this.entityData.set(HIDE_EFFECTS_FLAG, true)
    }

    /**
     * SERVER, every AI tick: the tamed/wild split — wild girls (no
     * master) tick the flight state machine, boss bar and knock-out recovery;
     * tamed girls follow their owner (or the Manglelie partnership) and run
     * the scene/morning-blowjob helpers.
     */
    override fun updateAITasks() {
        if (this.despawned) {
            GirlSavedData.updateMangleliePartner(this)
        } else {
            this.ensureCustomModelCodeLoaded()
            super.updateAITasks()
            this.watchClosestGirlGoal?.isWatching = this.isFlyingIdle()
            if (this.hasMaster()) {
                GalathTamedAi.tickTamedAi(this)
            } else {
                this.tickWildAi()
            }
        }
    }

    fun ensureCustomModelCodeLoaded() {
        if (!this.customModelCodeLoaded) {
            this.setCustomModelCode(GirlWorldData.getCustomModelCode(this))
            this.customModelCodeLoaded = true
        }
    }

    fun isFlyingIdle(): Boolean {
        val motion = this.deltaMovement
        return if (this.getCurrentAction() != Action.NULL) false else !(Math.abs(motion.x) + Math.abs(motion.z) > 0.01)
    }

    fun handlePlayerRide(player: Player) {
        GalathRiding.handlePlayerRide(this, player)
    }

    fun wanderRandomly() {
        val action = this.getCurrentAction()
        if (action != Action.RUN) {
            if (action != Action.HUG_MANG) {
                if (!this.isAnchored() && action != Action.MASTERBATE) {
                    val player = this.level().getNearestPlayer(this, 15.0)
                    if (this.hasMaster() && player != null && player.distanceTo(this) < 2.0f && player.uuid == this.getMasterUUID()) {
                        this.getNavigation().stop()
                    } else {
                        var target = this.wanderTarget
                        if (target == null ||
                            Math.sqrt(this.distanceToSqr(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())) > this.getFlightRange() ||
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
                            this.tickPathVelocity()
                        } else {
                            this.wanderTicks++
                        }
                    }
                } else {
                    this.getNavigation().stop()
                }
            }
        }
    }

    fun getManglelieOwnerPosition(): BlockPos {
        val ownerUuid = GirlSavedData.getManglelieOwnerOf(this) ?: return BlockPos.ZERO

        val owner = this.level().getPlayerByUUID(ownerUuid)
        return owner?.blockPosition() ?: BlockPos.ZERO
    }

    fun getFlightRange(): Double = Math.sqrt(1800.0)

    fun getRidingPlayer(): Player? {
        val passengers = this.passengers
        return if (passengers.isEmpty()) {
            null
        } else {
            passengers[0] as? Player
        }
    }

    fun getRidingPlayerUUID(): UUID? {
        val rider = this.getRidingPlayer()
        return rider?.uuid
    }

    override fun setCustomNameOverride(name: String) {
        super.setCustomNameOverride(name)
        val masterUuid = this.getMasterUUID()
        if (masterUuid != null) {
            AllieWorldData.addAllie(masterUuid, NpcType.GALATH, name)
        }
    }

    fun applyVelocityDelta(delta: Vec3) {
        val motion = this.deltaMovement
        this.setDeltaMovement(motion.x + delta.x, delta.y / 2.0, motion.z + delta.z)
    }

    fun resetInteractionState() {
        GalathActions.resetInteractionState(this)
    }

    fun tickWildAi() {
        this.bossBar.isVisible = true
        this.tickFlightSystems()
        this.tickIdleWander()
    }

    /**
     * SERVER: the tamed rape/corrupt scenes' shared tick — targets the
     * nearest player or mob in a radius (7 tamed / 20 wild), picks flight
     * actions (see [GalathFlightState.initFlightData]), keeps the energy-ball
     * hitboxes synced and watches the flight target validity.
     */
    fun tickFlightSystems() {
        if (!Action.isAnyAction(this, Action.MASTERBATE, Action.HUG_MANG)) {
            if (this.getInteractionPlayerUUID() == null) {
                GalathFlightState.cancelFlightOnLostTarget(this)
                GalathFlightState.acquireFlightTarget(this)
                GalathFlightState.tickFlightStateMachine(this)
                GalathFlightState.checkFlightFinished(this)
                GalathFlightState.updateChangePositionNoClip(this)
                GalathFlightState.tickKnockoutFlight(this)
                GalathFlightState.freezeDuringKnockout(this)
                GalathFlightState.handleKnockout(this)
                GalathFlightState.tickKnockoutStandUp(this)
                GalathEnergyBalls.pruneDeadSkeletons(this)
                GalathEnergyBalls.spawnSkeletonBreathParticles(this)
                GalathEnergyBalls.despawnSummonedSkeletons(this)
                GalathEnergyBalls.despawnFarSummonedSkeletons(this)
                GalathSceneTicks.tickWildRapeCum(this)
            }
        }
    }

    fun tickIdleWander() {
        if (this.getTargetEntity() != null) {
            this.wanderTarget = null
            this.wanderTicks = 0
        } else if (!this.entityData.get(HIDE_EFFECTS_FLAG)) {
            if (!this.entityData.get(PARALYZED)) {
                this.wanderRandomly()
            }
        }
    }

    /** SERVER: the state-machine gate, see [GalathActions.setCurrentAction]. */
    override fun setCurrentAction(action: Action?) {
        GalathActions.setCurrentAction(this, action)
    }

    /** Keeps the super call reachable from [GalathActions] while the transition gate lives there. */
    fun setCurrentActionBase(action: Action?) {
        super.setCurrentAction(action)
    }

    fun endMorningBlowjobScene() {
        GalathActions.endMorningBlowjobScene(this)
    }

    fun endHugScene() {
        GalathActions.endHugScene(this)
    }

    fun grantCoinAndBindMaster() {
        GalathActions.grantCoinAndBindMaster(this)
    }

    fun setFlying(flying: Boolean) {
        GalathActions.setFlying(this, flying)
    }

    /** CLIENT. */
    override fun getRenderScaleFactor(): Float {
        if (ClientHooks.thirdPersonView() != 0) {
            return 1.0f
        }

        return when (this.getCurrentAction()) {
            Action.CORRUPT_SLOW, Action.CORRUPT_FAST, Action.CORRUPT_CUM -> 0.5f
            // Java: CORRUPT_INTRO breaks out (-> 0.5) only while the intro camera is active, else falls into default
            Action.CORRUPT_INTRO -> if (this.corruptIntroActive) 0.5f else 1.0f
            else -> 1.0f
        }
    }

    override fun supportsCustomModels(): Boolean = false

    fun canStartPussyLicking(): Boolean = GalathActions.canStartPussyLicking(this)

    /** CLIENT. */
    override fun asGirl(): BaseGirlEntity {
        val manglelie = this.getMangleliePartner(false) ?: return super.asGirl()

        val player = ClientHooks.localPlayer()!! // java-npe
        if (player.isShiftKeyDown) {
            return manglelie
        }

        player.displayClientMessage(Component.literal(ChatFormatting.GRAY.toString() + "[sneak] + [right click] if you want to edit Manglelie instead"), true)
        return super.asGirl()
    }

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        return if (GalathInteractions.processInteract(this, player, hand)) {
            InteractionResult.sidedSuccess(this.level().isClientSide)
        } else {
            InteractionResult.PASS
        }
    }

    /** Keeps the super call reachable from [GalathInteractions] while the master/girl interaction split lives there. */
    fun processInteractBase(player: Player, hand: InteractionHand): Boolean = super.mobInteract(player, hand).consumesAction()

    /** CLIENT: master-action dispatch, see [GalathInteractions.doAction]. */
    override fun doAction(action: String, uuid: UUID) {
        GalathInteractions.doAction(this, action, uuid)
    }

    override fun isMultipartEntity(): Boolean = true

    override fun getParts(): Array<PartEntity<*>> = arrayOf(this.energyBallHitboxRight, this.energyBallHitboxLeft)

    /** Numbers the parts right after the parent id, like `EnderDragon.setId` (Forge MC-158205). */
    override fun setId(id: Int) {
        super.setId(id)
        // parts are null while the super constructor runs
        @Suppress("SENSELESS_COMPARISON")
        if (this.energyBallHitboxRight != null && this.energyBallHitboxLeft != null) {
            val parts = this.getParts()
            for (i in parts.indices) {
                parts[i].setId(id + i + 1)
            }
        }
    }

    fun setTargetEntity(target: LivingEntity?) {
        if (target == null) {
            this.entityData.set(TARGET_ENTITY_ID, -1)
        } else {
            this.entityData.set(TARGET_ENTITY_ID, target.id)
        }
    }

    fun getAttackProgress(): Int = this.entityData.get(ATTACK_PROGRESS)

    fun setAttackProgress(progress: Int) {
        this.entityData.set(ATTACK_PROGRESS, progress)
    }

    fun getTargetEntity(): LivingEntity? {
        val targetId = this.entityData.get(TARGET_ENTITY_ID)
        return if (-1 == targetId) null else this.level().getEntity(targetId) as? LivingEntity
    }

    fun playHurtSound(damage: Float) {
        GalathCombat.playHurtSound(this, damage)
    }

    /** 1.12 `attackEntityFrom`. */
    override fun hurt(source: DamageSource, amount: Float): Boolean = GalathCombat.attackEntityFrom(this, source, amount)

    /** Keeps the super call reachable from [GalathCombat] while the damage gate lives there. */
    fun attackEntityFromBase(source: DamageSource, amount: Float): Boolean = super.hurt(source, amount)

    override fun attackEntityFromPart(part: SexEntityPart, source: DamageSource, amount: Float): Boolean =
        GalathCombat.attackEntityFromPart(this, part, source, amount)

    override fun reinitTasks() {
        this.setTargetEntity(null)
        GalathFlightState.clearActiveFlight(this)
    }

    fun getWorld(): Level = this.level()

    /** 1.12 `setFire(int)` was a no-op: she never accumulates fire ticks. */
    override fun setRemainingFireTicks(remainingFireTicks: Int) {
    }

    /** 1.12 `fall(distance, multiplier)` was a no-op. */
    override fun causeFallDamage(fallDistance: Float, multiplier: Float, source: DamageSource): Boolean = false

    override fun getNextAction(action: Action): Action? = null

    override fun getCumAction(action: Action): Action? {
        if (action == Action.CORRUPT_FAST || action == Action.CORRUPT_SLOW) {
            return Action.CORRUPT_CUM
        }

        if (action == Action.RAPE_ON_GOING) {
            return Action.RAPE_CUM
        }

        if (Action.isAny(action, Action.MORNING_BLOWJOB_SLOW, Action.MORNING_BLOWJOB_FAST)) {
            this.morningBlowjobStarted = true
        }

        return null
    }

    override fun isWingsAnimated(): Boolean = this.wingsAnimated

    override fun areWingsAnimated(): Boolean {
        return when (this.getCurrentAction()) {
            Action.CORRUPT_SLOW, Action.CORRUPT_FAST, Action.CORRUPT_CUM, Action.COWGIRLCUM -> false
            else -> true
        }
    }

    /** SERVER: per-tick rape damage, see [GalathCombat.handleRapeAction]. */
    fun handleRapeAction(applyDamage: Boolean) {
        GalathCombat.handleRapeAction(this, applyDamage)
    }

    override fun addAdditionalSaveData(tag: net.minecraft.nbt.CompoundTag) {
        super.addAdditionalSaveData(tag)
        GalathNbtIO.write(this, tag)
    }

    override fun readAdditionalSaveData(tag: net.minecraft.nbt.CompoundTag) {
        super.readAdditionalSaveData(tag)
        GalathNbtIO.read(this, tag)
    }

    fun startMasterbateSitting() {
        GalathActions.startMasterbateSitting(this)
    }

    fun startPussyLicking() {
        GalathActions.startPussyLicking(this)
    }

    override fun handleActionAnimationOverrides(action: Action, animName: String, started: Boolean, event: AnimationState<*>): Boolean =
        GalathAnimations.handleActionAnimationOverrides(this, action, animName, started, event)

    fun getSwordAttackProgress(partialTicks: Float): Float = GalathAnimations.getSwordAttackProgress(this, partialTicks)

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState = GalathAnimations.animationPredicate(this, event)

    /**
     * CLIENT: registers the action (via [GirlAnimationController]),
     * movement and eyes controllers on the entity and hands the sound listener
     * to [GalathAnimations.handleSoundKeyframe].
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val action = GirlAnimationController<BaseGirlEntity>(this, "action", 0) { this.animationPredicate(it) }
        val movement = GirlAnimationController<BaseGirlEntity>(this, "movement", 5) { this.animationPredicate(it) }
        val eyes = GirlAnimationController<BaseGirlEntity>(this, "eyes", 10) { this.animationPredicate(it) }
        this.actionController = action
        this.movementController = movement
        this.eyesController = eyes
        action.registerSoundListener { sound -> GalathAnimations.handleSoundKeyframe(this, sound) }
        controllers.add(action)
        controllers.add(eyes)
        controllers.add(movement)
    }

    /**
     * Event handlers for the Galath fight/taming lifecycle: replaces wither
     * skeleton/blaze spawns near hives with wild Galaths
     * (`canSpawn`), jump = flight boost while riding (`onKeyInput`, client half),
     * unmount resets (`onMount`), death is intercepted — wild girls
     * enter the knockout + "corrupt her" broadcast, tamed girls de-summon to
     * the coin (`onLivingDeath`), player respawn mid-scene resets the
     * girl (`onRespawn`), the SUMMON_SKELETON energy balls are rendered
     * as growing dragons (`onRenderWorldLast`, client half), and waking up with a
     * tamed Galath spawns the morning-blowjob scene when the bed has free
     * space (`onWake`, see [spawnStructure]).
     *
     * Port note: the client-only handlers live in [ClientEvents] (PORTING.md §2.3 Rule E).
     */
    open class GalathLifecycleEvents {
        fun hasRidingPlayer(galath: GalathEntity): Boolean = galath.getRidingPlayer() != null

        /** 1.12 `LivingSpawnEvent.CheckSpawn`. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        open fun canSpawn(event: MobSpawnEvent.PositionCheck) {
            val result = event.result
            if (result != MobSpawnEvent.PositionCheck.Result.FAIL) {
                if (event.spawner == null) {
                    val entity = event.entity
                    if (entity is WitherSkeleton || entity is Blaze) {
                        val pos = entity.blockPosition()
                        val world = entity.level()
                        if (isNearHive(pos, world)) {
                            event.result = MobSpawnEvent.PositionCheck.Result.FAIL
                            BeeWorldData.addHivePosition(pos, BeeWorldData.hivePositions)
                            val galath = GalathEntity(world)
                            galath.moveTo(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                            world.addFreshEntity(galath)
                        }
                    }
                }
            }
        }

        @SubscribeEvent
        open fun onMount(event: EntityMountEvent) {
            if (!event.isMounting) {
                val entity = event.entityBeingMounted
                if (entity is GalathEntity) {
                    if (entity.level().isClientSide) {
                        GalathFlightHud.startFadeOut()
                    } else {
                        entity.resetInteractionState()
                    }
                }
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        open fun onLivingDeath(event: LivingDeathEvent) {
            val entity = event.entity
            if (entity is GalathEntity) {
                // 1.12 /kill dealt OUT_OF_WORLD damage; 1.21 /kill uses GENERIC_KILL
                if (!event.source.`is`(DamageTypes.FELL_OUT_OF_WORLD) && !event.source.`is`(DamageTypes.GENERIC_KILL)) {
                    val galath = entity
                    if (!galath.deathHandled) {
                        if (!entity.level().isClientSide) {
                            if (!galath.hasMaster()) {
                                // 1.12 passed getCombatTracker().getFighter() (= galath); the parameter is unused
                                galath.sendTrackingMessage(galath)
                            } else {
                                GalathCoinItem.deSummonGalath(galath)
                                PacketHandler.networkWrapper.sendToAllTracking(
                                    SpawnEnergyBallParticlesPacket(galath.getGirlId(), GirlSavedData.getManglelieOwnerOf(galath)), galath,
                                )
                                ThreadNames.createDaemonThread(900) { GirlSavedData.updateMangleliePartner(galath) }
                                galath.deathHandled = true
                            }

                            galath.health = 1.0f
                            event.isCanceled = true
                        }
                    }
                }
            }
        }

        @SubscribeEvent
        open fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) {
            val player = event.entity as ServerPlayer
            val girl = getGirlByUUID(player.uuid, true)
            if (girl is GalathEntity) {
                val galath = girl
                galath.setTargetEntity(null)
                ResetGirlPacket.Handler.resetGirl(girl)
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player)
                girl.setCurrentAction(null)
                val flight = galath.activeFlight
                if (flight != null) {
                    flight.updateFlight(galath)
                    galath.activeFlight = null
                }
            }
        }

        /**
         * SERVER: checks the bed-side space requirement for the morning blowjob —
         * the block south/east/north/west of the bed head (and one above) must be
         * free of solid blocks, else the scene cannot spawn.
         */
        fun spawnStructure(world: Level, pos: BlockPos, facing: Direction): Boolean {
            var p = pos
            if (facing == Direction.NORTH) {
                p = p.west()
                return if (this.isValidFlightBlock(world, p)) {
                    false
                } else if (this.isValidFlightBlock(world, p.above())) {
                    false
                } else {
                    if (this.isValidFlightBlock(world, p.south())) false else !this.isValidFlightBlock(world, p.south().above())
                }
            } else if (facing == Direction.WEST) {
                p = p.south()
                return if (this.isValidFlightBlock(world, p)) {
                    false
                } else if (this.isValidFlightBlock(world, p.above())) {
                    false
                } else {
                    if (this.isValidFlightBlock(world, p.east())) false else !this.isValidFlightBlock(world, p.east().above())
                }
            } else if (facing == Direction.SOUTH) {
                p = p.east()
                return if (this.isValidFlightBlock(world, p)) {
                    false
                } else if (this.isValidFlightBlock(world, p.above())) {
                    false
                } else {
                    if (this.isValidFlightBlock(world, p.north())) false else !this.isValidFlightBlock(world, p.north().above())
                }
            } else if (facing == Direction.EAST) {
                p = p.north()
                return if (this.isValidFlightBlock(world, p)) {
                    false
                } else if (this.isValidFlightBlock(world, p.above())) {
                    false
                } else {
                    if (this.isValidFlightBlock(world, p.west())) false else !this.isValidFlightBlock(world, p.west().above())
                }
            } else {
                Main.LOGGER.error(
                    "Weird bed orientation, when checking for space next to bed, on galaths morning blowjob animation: " + facing.getName(),
                )
                return false
            }
        }

        fun isValidFlightBlock(world: Level, pos: BlockPos): Boolean {
            val block = world.getBlockState(pos).block

            for (blockClass in NON_SOLID_FLIGHT_BLOCKS) {
                if (blockClass.isInstance(block)) {
                    return false
                }
            }

            return true
        }

        @SubscribeEvent
        open fun onWake(event: PlayerWakeUpEvent) {
            val player = event.entity
            if (!player.level().isClientSide) {
                if (GirlSavedData.shouldDespawn(player.uuid, player.level())) {
                    val pos = player.position()
                    val blockPos = BlockPos.containing(pos)
                    val facing = player.level().getBlockState(blockPos).getValue(HorizontalDirectionalBlock.FACING)
                    if (!this.spawnStructure(player.level(), blockPos, facing)) {
                        player.sendSystemMessage(
                            Component.literal(
                                String.format(
                                    "%sFor Galath and Manglelie to %swake you up with a blowjob%s, you have to provide enough space to the %sright side%s of your bed. This includes the %stop and bottom half%s of the bed.",
                                    ChatFormatting.GRAY, ChatFormatting.DARK_RED, ChatFormatting.GRAY, ChatFormatting.DARK_RED,
                                    ChatFormatting.GRAY, ChatFormatting.DARK_RED, ChatFormatting.GRAY,
                                ),
                            ),
                        )
                    } else {
                        val yaw = when (player.level().getBlockState(blockPos).getValue(HorizontalDirectionalBlock.FACING)) {
                            Direction.NORTH -> 180.0f
                            Direction.EAST -> -90.0f
                            Direction.WEST -> 90.0f
                            else -> 0.0f
                        }

                        val spawnPos = Vec3(blockPos.x + 0.5, blockPos.y.toDouble(), blockPos.z + 0.5)
                        val ownerUuid = GirlSavedData.getOwnerOf(player)
                        if (ownerUuid != null) {
                            GirlSavedData.updateMangleliePartner(getServerGirlEntity(ownerUuid) as GalathEntity) // java-npe (cast)
                        }

                        val galath = GalathEntity(player.level(), player, pos, true)
                        galath.moveTo(pos.x, pos.y, pos.z)
                        player.level().addFreshEntity(galath)
                        GirlSavedData.grantOwnership(player, galath)
                        galath.canStartPussyLicking()
                        galath.setTargetPosition(spawnPos)
                        galath.setYawRotation(yaw)
                        galath.setAnchored(true)
                        galath.setInteractionPlayerUUID(player.uuid)
                        galath.setCurrentAction(Action.MORNING_BLOWJOB_SLOW)
                        val serverPlayer = player as ServerPlayer
                        PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), serverPlayer)
                        ThreadNames.createDaemonThread(500) {
                            // Port deviation: the 1.12 thread touched the player directly; 1.21 hops back onto the server thread
                            serverPlayer.server.execute {
                                serverPlayer.teleportTo(spawnPos.x, spawnPos.y, spawnPos.z)
                                PacketHandler.networkWrapper.sendTo(SetPlayerCamPacket(-10.0f, yaw + 180.0f + 5.0f, 0), serverPlayer)
                            }
                        }
                    }
                }
            }
        }

        /** Client-only halves of [GalathLifecycleEvents] (PORTING.md §2.3 Rule E). */
        object ClientEvents {
            /** 1.12 `KeyInputEvent`: jump while riding = flight boost. */
            @SubscribeEvent
            fun onKeyInput(event: InputEvent.Key) {
                val mc = Minecraft.getInstance()
                if (mc.options.keyJump.isDown) {
                    if (GalathFlightHud.canUseCharge()) {
                        try {
                            for (girl in getGirlEntityList()) {
                                if (girl.level().isClientSide && girl is GalathEntity && mc.player?.uuid == girl.getRidingPlayerUUID()) {
                                    GalathFlightHud.useCharge()
                                    girl.setCurrentAction(Action.BOOST)
                                    return
                                }
                            }
                        } catch (ex: ConcurrentModificationException) {
                            // ignored: entity list mutated during iteration; retried on the next tick
                        }
                    }
                }
            }

            /** 1.12 `RenderWorldLastEvent`: the growing hand-dragons of SUMMON_SKELETON. */
            @SubscribeEvent
            fun onRenderWorldLast(event: RenderLevelStageEvent) {
                if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
                    return
                }

                val mc = Minecraft.getInstance()
                val renderManager = mc.entityRenderDispatcher
                val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(false)
                val camera = event.camera.position
                val poseStack: PoseStack = event.poseStack
                val bufferSource = mc.renderBuffers().bufferSource()

                try {
                    for (girl in getGirlEntityList()) {
                        if (girl is GalathEntity && girl.level().isClientSide && girl.getCurrentAction() == Action.SUMMON_SKELETON) {
                            val progress = girl.summonSkeletonTicks.toDouble()
                            if (!(progress < 9.0) && !(progress > 30.0)) {
                                val basePos = RotationHelper.lerpVec3dDouble(Vec3(girl.xOld, girl.yOld, girl.zOld), girl.position(), partialTicks.toDouble())
                                val scale = (progress - 9.0) / 21.0
                                if (girl.entityData.get(LEFT_ENERGY_BALL_ALIVE)) {
                                    val offsetR = girl.getCachedBoneOffset("energyBallR")
                                    val posR = basePos.add(offsetR)
                                    val dragonR = DragonEntity(girl.level(), girl)
                                    dragonR.SCALE_1_0 = scale
                                    dragonR.moveTo(posR.x, posR.y, posR.z)
                                    renderDragon(renderManager, dragonR, camera, partialTicks, poseStack, bufferSource)
                                    dragonR.setPos(0.0, -500.0, 0.0)
                                    dragonR.discard()
                                }

                                if (girl.entityData.get(RIGHT_ENERGY_BALL_ALIVE)) {
                                    val offsetL = girl.getCachedBoneOffset("energyBallL")
                                    val posL = basePos.add(offsetL)
                                    val dragonL = DragonEntity(girl.level(), girl)
                                    dragonL.moveTo(posL.x, posL.y, posL.z)
                                    dragonL.SCALE_1_0 = scale
                                    renderDragon(renderManager, dragonL, camera, partialTicks, poseStack, bufferSource)
                                    dragonL.setPos(0.0, -500.0, 0.0)
                                    dragonL.discard()
                                }
                            }
                        }
                    }
                } catch (ex: ConcurrentModificationException) {
                    // ignored: entity list mutated during iteration; retried on the next tick
                }

                bufferSource.endBatch()
            }

            private fun renderDragon(
                renderManager: net.minecraft.client.renderer.entity.EntityRenderDispatcher,
                dragon: DragonEntity,
                camera: Vec3,
                partialTicks: Float,
                poseStack: PoseStack,
                bufferSource: net.minecraft.client.renderer.MultiBufferSource,
            ) {
                renderManager.render(
                    dragon, dragon.x - camera.x, dragon.y - camera.y, dragon.z - camera.z, 0.0f, partialTicks,
                    poseStack, bufferSource, renderManager.getPackedLightCoords(dragon, partialTicks),
                )
            }
        }
    }

    companion object {
        const val a2: Float = 0.6f
        const val b6: Float = 0.6f
        const val bj: Int = 10
        const val an: Int = 20
        const val aU: Float = 50.0f
        const val ba: Float = 40.0f
        const val bM: Int = 5
        const val KoboldTask: Int = 25
        const val bJ: Float = 30.0f
        const val aA: Float = 3.0f
        const val a3: Int = 23
        const val MAX_TICKS_45: Int = 45
        const val ca: Float = 0.3f
        const val a8: Float = 9.0f
        const val aX: Float = 30.0f
        const val bE: Int = 24
        const val aQ: Int = 32
        const val av: Int = 5
        const val bQ: Int = 36
        const val aR: Int = 40
        const val aB: Int = 54
        const val by: Int = 10
        const val b_: Float = 0.25f
        const val ax: Double = 3.0
        const val bF: Double = 1.0
        const val bv: Double = 1.5

        @JvmField
        val az: Double = 0.3f.toDouble()
        const val ag: Double = 40.0
        const val au: Double = 5.0
        const val ae: Double = 0.2
        const val aV: Double = 3.0

        @JvmField
        val ar: Double = 0.1f.toDouble()
        const val ai: Double = 6.0
        const val ah: Double = 50.0
        const val bR: Double = 39.0
        const val bV: Double = 58.0
        const val aZ: Double = 2.0
        const val SCALE_1_0: Double = 1.0
        const val aJ: Float = 0.5f

        @JvmField
        val STAR_COLOR: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.83137256f, 0.6862745f, 0.21568628f)

        @JvmField
        val LEFT_ENERGY_BALL_OFFSET: Vec3 = Vec3((-1.049342f).toDouble(), 2.0547214f.toDouble(), (-0.050482392f).toDouble())

        @JvmField
        val RIGHT_ENERGY_BALL_OFFSET: Vec3 = Vec3(1.2522261f.toDouble(), 1.4357733f.toDouble(), 0.23570988f.toDouble())
        const val aN: Int = 10
        const val ak: Float = 0.2f
        const val am: Int = 5
        const val SPEED_15: Float = 15.0f
        const val aM: Int = 48
        const val be: Float = 0.05f
        const val a7: Float = 0.65f
        const val bh: Float = 0.9f
        const val ANGLE_45: Float = 45.0f
        const val a0: Float = 1.0f
        const val ForgeEventHandler: Float = 1.5f
        const val ao: Float = 110.0f
        const val aj: Int = 15
        const val aw: Float = 6.0f
        const val bp: Float = 0.94f
        const val COUNT_13: Int = 13
        const val bW: Int = 40
        const val bl: Int = 25
        const val aY: Int = 38
        const val DISTANCE_95: Int = 95
        const val bB: Int = 10
        const val aI: Int = 30
        const val bf: Int = 175
        const val `as`: Float = 2.0f
        const val bo: Float = 0.25f
        const val TIMEOUT_1000: Float = 1000.0f
        const val bX: Float = 15.0f
        const val b9: Float = 5.0f
        const val aW: Int = 8000
        const val aK: Float = 0.1f
        const val ac: Float = 5.0f
        const val b5: Float = -10.0f
        const val bk: Int = 16
        const val br: Int = 7
        const val cb: Int = 4
        const val FACTOR_0_5: Float = 0.5f
        const val SexModEntities: Float = 0.55f

        @JvmField
        val NON_SOLID_FLIGHT_BLOCKS: Array<Class<*>> = arrayOf(
            AirBlock::class.java, CarpetBlock::class.java, BushBlock::class.java, ButtonBlock::class.java,
            LadderBlock::class.java, TorchBlock::class.java, SignBlock::class.java, AbstractBannerBlock::class.java,
        )

        @JvmField
        val TARGET_ENTITY_ID: EntityDataAccessor<Int> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val ATTACK_PROGRESS: EntityDataAccessor<Int> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val LEFT_ENERGY_BALL_ALIVE: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val RIGHT_ENERGY_BALL_ALIVE: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val MIRROR_SIDE: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val SWORD_ATTACK_PROGRESS: EntityDataAccessor<Int> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val FLIGHT_TARGET_POS: EntityDataAccessor<String> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val PARALYZED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val RAPE_PROGRESS: EntityDataAccessor<Float> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val HIDE_EFFECTS_FLAG: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.BOOLEAN)

        /** Manglelie partner UUID (the odd name is the deobfuscated 1.12 field name, kept). */
        @JvmField
        val WildSlimeFaceLayer: EntityDataAccessor<String> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val SPRINTING: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GalathEntity::class.java, EntityDataSerializers.BOOLEAN)
        const val b0: Double = 0.2
        const val bS: Float = 5.0f
        const val a1: Int = 60

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder =
            BaseGirlEntity.createAttributes()
                .add(Attributes.FOLLOW_RANGE, 50.0)
                .add(Attributes.FLYING_SPEED, 0.6f.toDouble())
                .add(Attributes.MAX_HEALTH, 110.0)
                .add(Attributes.MOVEMENT_SPEED, 0.6f.toDouble())

        @JvmStatic
        fun getMangleliePartnerOf(girl: BaseGirlEntity, server: Boolean): ManglelieEntity? =
            if (girl !is GalathEntity) null else girl.getMangleliePartner(server)

        @JvmStatic
        fun isNearHive(pos: BlockPos, world: Level): Boolean {
            for (hivePos in BeeWorldData.hivePositions) {
                if (Math.sqrt(pos.distSqr(hivePos)) < 1000.0) {
                    return false
                }
            }

            try {
                for (girl in getGirlEntityList()) {
                    if (!girl.level().isClientSide && girl is GalathEntity && girl.isAlive &&
                        girl.distanceToSqr(Vec3.atLowerCornerOf(pos)) < 1000000.0
                    ) {
                        return false
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }

            var y = pos.y
            while (y < 15.0f + pos.y) {
                if (!world.getBlockState(BlockPos(pos.x, y, pos.z)).`is`(Blocks.AIR)) {
                    return false
                }
                y++
            }

            y = pos.y
            while (y > pos.y - 5.0f) {
                if (world.getBlockState(BlockPos(pos.x, y, pos.z)).block is LiquidBlock) {
                    return false
                }
                y--
            }

            return true
        }

        @JvmStatic
        fun handlePlayerJoin(player: Player) {
            val girl = getServerGirlEntity(GirlSavedData.getOwnerOf(player))
            if (girl != null) {
                if (girl == player.vehicle) {
                    girl.setInteractionPlayerUUID(player.uuid)
                    girl.setCurrentAction(Action.CONTROLLED_FLIGHT)
                }
            }
        }

        /**
         * SERVER: computes the aim yaw toward the flight target (progress-lerped
         * positions) and applies it to the render yaw. Returns null outside the
         * aiming actions (FLY/SUMMON_SKELETON/RAPE_PREPARE) — callers must handle
         * null (see `ManglelieEntity.isLookingAtGalathPoint`).
         */
        @JvmStatic
        fun getAimYaw(galath: GalathEntity, partialTicks: Float): Float? {
            val action = galath.getCurrentAction()
            if (action != Action.FLY && action != Action.SUMMON_SKELETON && action != Action.RAPE_PREPARE) {
                return null
            }

            val target = galath.getTargetEntity() ?: return null

            val targetPos = RotationHelper.lerpVec3dDouble(Vec3(target.xOld, target.yOld, target.zOld), target.position(), partialTicks.toDouble())
            val selfPos = RotationHelper.lerpVec3dDouble(Vec3(galath.xOld, galath.yOld, galath.zOld), galath.position(), partialTicks.toDouble())
            val delta = targetPos.subtract(selfPos)
            val yaw = TrigMath.sinDegrees(Math.atan2(delta.z, delta.x)).toFloat() - 90.0f
            galath.yBodyRot = yaw
            galath.yBodyRotO = yaw
            return yaw
        }
    }
}
