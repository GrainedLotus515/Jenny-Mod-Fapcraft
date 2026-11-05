package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeDialogueScreen
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.entity.ai.GirlGotoGoal
import com.trolmastercard.sexmod.entity.ai.WatchClosestGirlGoal
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.potion.HornyPotion
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.SoundHandler
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.PanicGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> The Bee NPC — a flying girl with a citizen sex scene
 * (start/slow/fast/cum) and a taming/chest mechanic: once tamed
 * (`HORNY_FLAG`, id 112) she carries a 27-slot chest inventory and opens
 * the bee dialogue/chest GUIs; wild bees get horny over time
 * (`hornyTimer`, 4800 ticks) and approach the nearest player to start
 * the scene by themselves.
 *
 * <b>State.</b> `HORNY_FLAG` (112) doubles as tamed/chest flag AND
 * pregnancy-heart flag (see [doParticleStuff]); `eggState` is
 * a particle-cycle counter; `hornyTimer` counts up while unbounded
 * (0 = freshly interested). Flying is vanilla [FlyingMoveControl] +
 * [FlyingPathNavigation]; fall damage is disabled.
 *
 * <b>Scene flow.</b> [handleBeeIdle] (server, AI tick) drives the
 * wild approach: at 4800 ticks she locks the nearest non-owner player within
 * 1.5 blocks into the scene (anchor + [Action.CITIZEN_START]).
 * Progression and end run in the sound listener
 * (`sex_startDone`/`sex_fastDone` ->
 * [Action.CITIZEN_SLOW], jump keeps fast, `sex_cumDone` ->
 * `resetCameraAndPhysics()`).
 *
 * <b>Pitfalls.</b> `processInteract` has a dead condition
 * (`HORNY_FLAG && !HORNY_FLAG`) — jar-faithful, the chest-give branch
 * never fires; do not "fix" it into an active chest-consume path without
 * testing the taming flow. NBT write/read uses the `isTamed` and
 * `hasChest` keys, both mapped onto `HORNY_FLAG`.
 *
 * Port notes: 1.12 `EntityFlyHelper` is `FlyingMoveControl(this, 10, false)`
 * (closest 1.21 equivalent; turn rates differ slightly); `SPacketParticles` is
 * [ClientboundLevelParticlesPacket]; `readFromNBT` is [readAdditionalSaveData];
 * `fall` is [causeFallDamage].
 */
class BeeEntity : BeeEntityBase {
    @JvmField
    var hornyTimer: Float = 3200.0f

    @JvmField
    var eggState: Int = 0

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out BeeEntity>, level: Level) : super(type, level) {
        this.moveControl = FlyingMoveControl(this, 10, false)
    }

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.BEE.get(), level)

    override fun getDisplayNameText(): String = "Bee"

    override fun getScaleFactor(): Float = -0.1f

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(HORNY_FLAG, false)
    }

    /** 1.12 `createNavigator`. */
    override fun createNavigation(level: Level): PathNavigation {
        val navigator = FlyingPathNavigation(this, level)
        navigator.setCanOpenDoors(false)
        navigator.setCanFloat(true)
        navigator.setCanPassDoors(true)
        this.pathNavigator = navigator
        return navigator
    }

    /** 1.12 `initEntityAI()` (does not call super: no base girl goals). */
    override fun registerGoals() {
        val watch = WatchClosestGirlGoal(this, Player::class.java, 3.0f, 1.0f)
        this.watchClosestGirlGoal = watch
        this.goalSelector.addGoal(0, GirlGotoGoal(this))
        this.goalSelector.addGoal(1, PanicGoal(this, 1.25))
        this.goalSelector.addGoal(1, FloatGoal(this))
        this.goalSelector.addGoal(2, watch)
        this.goalSelector.addGoal(3, WaterAvoidingRandomFlyingGoal(this, 1.0))
    }

    /**
     * SERVER, every AI tick: removes a stale horny potion, ticks the wild
     * approach ([handleBeeIdle]), latches the cum-flag for the
     * particle cycle, spawns particles and applies the flight ceiling.
     */
    override fun updateAITasks() {
        super.updateAITasks()
        if (this.hasEffect(HornyPotion.HORNY_POTION) && this.hornyTimer < 4800.0f && this.getInteractionPlayerUUID() == null) {
            this.removeEffect(HornyPotion.HORNY_POTION)
            this.hornyTimer = 6.9420184E7f
        }

        this.handleBeeIdle()
        if (this.getCurrentAction() == Action.CITIZEN_CUM) {
            this.eggState = Math.max(1, this.eggState)
        }

        this.doParticleStuff()
        this.rayTraceFlower()
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.CITIZEN_CUM || action !== Action.CITIZEN_FAST && action !== Action.COWGIRLSLOW) {
            super.setCurrentAction(action)
        }
    }

    /**
     * SERVER: the wild-bee horny timer — counts up while unbounded and
     * masterless; at 4800 ticks she approaches the nearest free player and,
     * within 1.5 blocks, locks them into the citizen scene (anchor + yaw +
     * [Action.CITIZEN_START]).
     */
    fun handleBeeIdle() {
        if (this.getInteractionPlayerUUID() == null) {
            if (!this.hasMaster()) {
                this.hornyTimer++
                if (!(this.hornyTimer < 4800.0f)) {
                    val player = this.level().getNearestPlayer(this, 10.0)
                    if (player != null) {
                        if (getActiveSceneInfo(player) == null) {
                            if (!AbstractPlayerGirlEntity.isOwnerPlayer(player)) {
                                if (player.distanceTo(this) < 1.5f) {
                                    this.hornyTimer = 0.0f
                                    this.setInteractionPlayerUUID(player.uuid)
                                    this.entityData.set(IS_ANCHORED, true)
                                    this.setTargetPosition(this.getFrontOffsetVector())
                                    this.setYawRotation(player.yRot - 180.0f)
                                    this.pathNavigator.stop()
                                    PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
                                    this.setCurrentAction(Action.CITIZEN_START)
                                    val vec = this.getVectorTowardPlayer(0.2)
                                    player.teleportTo(vec.x, vec.y, vec.z)
                                } else {
                                    this.pathNavigator.stop()
                                    this.pathNavigator.moveTo(player, 1.0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * SERVER: ray-traces straight down and cancels upward motion once more
     * than 3 blocks above ground — the bee's flight ceiling.
     */
    fun rayTraceFlower() {
        val rayTrace = this.level().clip(
            ClipContext(this.position(), Vec3(this.x, 0.0, this.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this),
        )
        if (rayTrace.type != HitResult.Type.MISS) {
            val pos = rayTrace.blockPos
            val height = this.y - pos.y
            if (height > 3.0 && this.deltaMovement.y > 0.0) {
                this.setDeltaMovement(this.deltaMovement.x, 0.0, this.deltaMovement.z)
            }
        }
    }

    /** 1.12 `SPacketParticles` to every player within 15 blocks. */
    private fun sendParticles(type: ParticleOptions, count: Int) {
        for (player in this.level().players()) {
            if (player.distanceTo(this) < 15.0f) {
                (player as ServerPlayer).connection.send(
                    ClientboundLevelParticlesPacket(
                        type, true, this.x.toFloat().toDouble(), (this.y.toFloat() + 0.3f).toDouble(), this.z.toFloat().toDouble(),
                        0.2f, 0.3f, 0.2f, 0.25f, count,
                    ),
                )
            }
        }
    }

    /**
     * SERVER: the post-cum particle cycle (`eggState`) — heart/anger
     * particles around the bee, then a coin-flip (at tick 200) that sets
     * `HORNY_FLAG` and drives the "accepted/rejected" particle outcome.
     */
    fun doParticleStuff() {
        if (this.eggState != 0) {
            this.eggState++
            if (this.entityData.get(HORNY_FLAG)) {
                if (this.eggState < 40) {
                    this.sendParticles(ParticleTypes.HEART, 1)
                } else {
                    this.eggState = 0
                }
            } else if (this.eggState < 200) {
                this.sendParticles(ParticleTypes.EFFECT, 1)
            } else if (this.eggState == 200) {
                this.entityData.set(HORNY_FLAG, this.random.nextBoolean())
            } else if (this.eggState < 250) {
                this.sendParticles(if (this.entityData.get(HORNY_FLAG)) ParticleTypes.HEART else ParticleTypes.ANGRY_VILLAGER, 3)
            } else {
                this.eggState = 0
            }

            this.sendParticles(ParticleTypes.EFFECT, 10)
        }
    }

    override fun tick() {
        super.tick()
        if (this.hornyTimer < 4800.0f && !this.onGround() && this.deltaMovement.y < 0.0) {
            this.setDeltaMovement(this.deltaMovement.x, this.deltaMovement.y * 0.4, this.deltaMovement.z)
        }
    }

    /** 1.12 `fall()` was empty: no fall damage. */
    override fun causeFallDamage(fallDistance: Float, multiplier: Float, source: DamageSource): Boolean = false

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (this.entityData.get(HORNY_FLAG) &&
            !this.entityData.get(HORNY_FLAG) &&
            player.getItemInHand(hand).item === Items.CHEST
        ) {
            this.entityData.set(HORNY_FLAG, true)
            player.getItemInHand(hand).shrink(1)
            return super.mobInteract(player, hand)
        }

        if (this.level().isClientSide && this.entityData.get(HORNY_FLAG)) {
            this.openBeeDialogue(player)
        }

        return super.mobInteract(player, hand)
    }

    /** CLIENT. */
    fun openBeeDialogue(player: Player) {
        Client.openBeeDialogue(this, player)
    }

    override fun openInteractionMenu(player: Player): Boolean = false

    override fun doAction(action: String, uuid: UUID) {
    }

    override fun getNextAction(action: Action): Action? = if (action === Action.CITIZEN_SLOW) Action.CITIZEN_FAST else null

    override fun getCumAction(action: Action): Action? =
        if (action !== Action.CITIZEN_FAST && action !== Action.CITIZEN_SLOW) null else Action.CITIZEN_CUM

    override fun startActionFromHandState() {
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putBoolean("isTamed", this.entityData.get(HORNY_FLAG))
        tag.putBoolean("hasChest", this.entityData.get(HORNY_FLAG))
        tag.put("inventory", this.inventory.serializeNBT(this.registryAccess()))
    }

    /** 1.12 `readFromNBT` override (ran after the super read). */
    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains("isTamed")) {
            this.entityData.set(HORNY_FLAG, tag.getBoolean("isTamed"))
        }

        this.entityData.set(HORNY_FLAG, tag.getBoolean("hasChest"))
        this.inventory.deserializeNBT(this.registryAccess(), tag.getCompound("inventory"))
    }

    /** CLIENT. */
    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

        when (event.controller.name) {
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.bee.null", true, event)
                } else {
                    this.createAnimation("animation.bee." + (if (this.entityData.get(HORNY_FLAG)) "idle_has_chest" else "idle"), true, event)
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.CITIZEN_START -> this.createAnimation("animation.bee.sex_start", false, event)
                Action.CITIZEN_SLOW -> this.createAnimation("animation.bee.sex_slow", true, event)
                Action.CITIZEN_FAST -> this.createAnimation("animation.bee.sex_fast", true, event)
                Action.CITIZEN_CUM -> this.createAnimation("animation.bee.sex_cum", false, event)
                Action.THROW_PEARL -> this.createAnimation("animation.bee.throw_pearl", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * citizen scene; `sex_startDone`/`sex_fastDone` ->
     * [Action.CITIZEN_SLOW] (jump keeps fast), `sex_cumDone` ->
     * `resetCameraAndPhysics()`.
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            // Java fall-through: sex_fastDone -> sex_startDone
            val startDone = {
                this.setCurrentAction(Action.CITIZEN_SLOW)
                if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }
            when (sound.keyframeData.sound) {
                "pearl" -> if (this.isLocalPlayerNearby() && this.getCurrentAction() === Action.THROW_PEARL) {
                    PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                }
                "resetCumPercentage" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                }
                "sex_fastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    this.addToHornyMeterIfControlled(0.04f.toDouble())
                }
                "sex_startMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    this.addToHornyMeterIfControlled(0.02f.toDouble())
                }
                "sex_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    startDone()
                }
                "sex_startDone" -> startDone()
                "sex_cumMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_CUMINFLATION), 2.0f)
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                }
                "blackscreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "sex_cumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                }
                "sex_fastReady" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
                }
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun openBeeDialogue(bee: BeeEntity, player: Player) {
            Minecraft.getInstance().setScreen(BeeDialogueScreen(bee, player))
        }
    }

    companion object {
        const val timerO: Float = 4800.0f
        const val timerQ: Float = 10.0f

        /** 1.12 key 112 (shadows [BeeEntityBase.HORNY_FLAG] inside this class, as in Java). */
        @JvmField
        val HORNY_FLAG: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(BeeEntity::class.java, EntityDataSerializers.BOOLEAN)

        /** 1.12 `applyEntityAttributes` (registered only these attributes; no super call). */
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder =
            BaseGirlEntity.createAttributes()
                .add(Attributes.KNOCKBACK_RESISTANCE)
                .add(Attributes.ARMOR)
                .add(Attributes.ARMOR_TOUGHNESS)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                .add(Attributes.FLYING_SPEED, 0.4f.toDouble())
                .add(Attributes.MAX_HEALTH, 12.0)
                .add(Attributes.MOVEMENT_SPEED, 0.2f.toDouble())
    }
}
