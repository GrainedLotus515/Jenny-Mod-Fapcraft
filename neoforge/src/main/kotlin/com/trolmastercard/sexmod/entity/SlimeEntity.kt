package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.potion.HornyPotion
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.LootTableHandler
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> The Slime NPC — a jump-happy slime girl with a pregnancy
 * lifecycle: it hops around (procedural jump animation, no AI tasks), gets
 * "impregnated" by the horny potion, grows, then births a
 * [WildSlimeEntity] that matures back into a new Slime. Scenes: blowjob
 * and doggy (same action set as Jenny).
 *
 * <b>State.</b> Own data keys: `TICKS_UNTIL_BIRTH` (113) = pregnancy
 * progress (0..2400; 1 = undress, 2 = pregnant start, 4+ = scene-ready),
 * `TARGET_YAW` (112) = hop direction, `HORNY_LEVEL` (111) =
 * countdown to birthing a wild slime (-1 = not pregnant). `slimeState`
 * drives the idle jump animation cycle (IDLE/JUMP_START/JUMP_AIR/JUMP_END).
 *
 * <b>Scene flow.</b> [checkInteractionTrigger] (server, AI tick)
 * anchors the slime when pregnant: if progress >= 4 she starts the doggy
 * bed intro by herself, otherwise the nearest player within 1 block gets
 * locked in and she starts doggy or blowjob. Cum ends via the
 * `bjcDone`/`doggyCumDone` sounds ->
 * `resetCameraAndPhysics()` + `pregnant = 2400`
 * (`changeDataParameterFromClient`) which starts the growth.
 *
 * <b>Pitfalls.</b> [addAdditionalSaveData]/[readAdditionalSaveData]
 * deliberately cross-write `hornyLevel` and `ticksUntilBirth`
 * (swapped keys — jar behavior, keep as-is). [setCurrentAction]
 * guards the cum loops. `handleJumpState` only runs while the action is
 * [Action.NULL]; the slime jumps by itself every ~50 ticks, adding
 * pregnancy progress at tick 50 (server) and snapping yaw to the target
 * angle. Fall damage ([causeFallDamage]) is intentionally a no-op.
 *
 * Port notes: `initEntityAI` (empty) is an empty [registerGoals];
 * `getJumpUpwardsMotion` is [getJumpPower]; `fall` is [causeFallDamage].
 */
class SlimeEntity : BaseGirlEntity {
    @JvmField
    var slimeState: SlimeEntityState = SlimeEntityState.IDLE

    @JvmField
    var jumpTicks: Int = 0

    @JvmField
    var wasOnGround: Boolean = true

    @JvmField
    var isJumping: Boolean = false

    @JvmField
    var blinkTicks: Int = 0

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out SlimeEntity>, level: Level) : super(type, level)

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.SLIME.get(), level)

    override fun getDisplayNameText(): String = "Slime"

    override fun getScaleFactor(): Float = 1.6f

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.CUMBLOWJOB || action !== Action.THRUSTBLOWJOB && action !== Action.SUCKBLOWJOB) {
            if (this.getCurrentAction() !== Action.DOGGYCUM || action !== Action.DOGGYFAST && action !== Action.DOGGYSLOW) {
                super.setCurrentAction(action)
            }
        }
    }

    /** CLIENT. */
    override fun shouldRenderNameTag(): Boolean = false

    /** 1.12 `initEntityAI()` was empty: the slime has no goals. */
    override fun registerGoals() {
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(TICKS_UNTIL_BIRTH, 0)
        builder.define(TARGET_YAW, 0.0f)
        builder.define(HORNY_LEVEL, -1)
    }

    override fun getCumAction(action: Action): Action? {
        if (action === Action.SUCKBLOWJOB || action === Action.THRUSTBLOWJOB) {
            return Action.CUMBLOWJOB
        } else {
            return if (action !== Action.DOGGYSLOW && action !== Action.DOGGYFAST) null else Action.DOGGYCUM
        }
    }

    override fun getNextAction(action: Action): Action? {
        if (action === Action.SUCKBLOWJOB) {
            return Action.THRUSTBLOWJOB
        } else {
            return if (action === Action.DOGGYSLOW) Action.DOGGYFAST else null
        }
    }

    /** 1.12 `getJumpUpwardsMotion()`. */
    override fun getJumpPower(): Float = 0.9f

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt("hornyLevel", this.entityData.get(TICKS_UNTIL_BIRTH))
        tag.putInt("ticksUntilBirth", this.entityData.get(HORNY_LEVEL))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        this.entityData.set(TICKS_UNTIL_BIRTH, tag.getInt("hornyLevel"))
        this.entityData.set(HORNY_LEVEL, tag.getInt("ticksUntilBirth"))
        if (this.entityData.get(TICKS_UNTIL_BIRTH) != 0) {
            this.entityData.set(OUTFIT_INDEX, 0)
        }

        this.noPhysics = false
        this.setNoGravity(false)
    }

    /** 1.12 `getLootTable()`. */
    override fun getDefaultLootTable(): ResourceKey<LootTable> = LootTableHandler.SLIME_TABLE

    override fun reinitTasks() {
        this.entityData.set(TICKS_UNTIL_BIRTH, 0)
        this.entityData.set(OUTFIT_INDEX, 1)
    }

    /**
     * SERVER, every AI tick: the horny-potion trigger — when dosed and idle
     * she removes the potion, starts the undress (if dressed) and arms the
     * pregnancy progress at 2.
     */
    override fun updateAITasks() {
        super.updateAITasks()
        this.checkInteractionTrigger()
        this.handleHornyJump()
        if (this.hasEffect(HornyPotion.HORNY_POTION) && this.slimeState == SlimeEntityState.IDLE && this.entityData.get(HORNY_LEVEL) == -1) {
            this.entityData.set(TICKS_UNTIL_BIRTH, 2)
            if (this.entityData.get(OUTFIT_INDEX) == 1) {
                this.setCurrentAction(Action.UNDRESS)
            }

            this.removeEffect(HornyPotion.HORNY_POTION)
        }
    }

    /**
     * BOTH sides: pregnancy particles (hearts every 10 ticks once pregnant),
     * the jump-state machine while idle, and CLIENT-side interaction
     * positioning + horny-level particles.
     */
    override fun tick() {
        super.tick()
        if (this.getCurrentAction() === Action.NULL) {
            this.handleJumpState()
        }

        if (this.entityData.get(TICKS_UNTIL_BIRTH) >= 2 && this.tickCount % 10 == 0) {
            spawnParticlesAround(ParticleTypes.HEART, this)
        }

        if (this.level().isClientSide) {
            this.handleHornyLevel()
            this.handlePlayerInteraction()
        }
    }

    /** CLIENT. */
    fun handlePlayerInteraction() {
        val interactionUuid = this.getInteractionPlayerUUID()
        if (interactionUuid != null) {
            val player = ClientHooks.localPlayer()!! // java-npe: Minecraft.player
            if (interactionUuid == player.uuid) {
                var pos = this.position()
                val offset = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 0.65f.toDouble()), this.getYawRotation())
                pos = pos.add(offset)
                player.setPos(pos.x, pos.y, pos.z)
                player.setDeltaMovement(0.0, 0.0, 0.0)
            }
        }
    }

    fun handleHornyLevel() {
        val hornyLevel = this.entityData.get(HORNY_LEVEL)
        if (hornyLevel != -1) {
            spawnParticlesAround(ParticleTypes.WITCH, this)
            if (hornyLevel == 0) {
                this.playSound(SoundHandler.MISC_PLOB[0])
            }
        }
    }

    /**
     * SERVER: counts the horny level down each AI tick; when it goes below 0 a
     * [WildSlimeEntity] is spawned in place and the pregnancy flag
     * clears.
     */
    fun handleHornyJump() {
        var hornyLevel = this.entityData.get(HORNY_LEVEL)
        if (hornyLevel != -1) {
            this.entityData.set(HORNY_LEVEL, hornyLevel - 1)
            if (--hornyLevel < 0) {
                val slime = WildSlimeEntity(this.level())
                slime.setPos(this.x, this.y, this.z)
                this.level().addFreshEntity(slime)
                this.entityData.set(HORNY_LEVEL, -1)
            }
        }
    }

    /**
     * SERVER: the pregnancy scene trigger — once progress reaches 4 and she is
     * on the ground with no action, she self-starts the doggy bed intro
     * (anchored); below 4, the nearest player within 1 block gets locked into
     * a doggy or blowjob scene.
     */
    fun checkInteractionTrigger() {
        val ticksUntilBirth = this.entityData.get(TICKS_UNTIL_BIRTH)
        if (ticksUntilBirth >= 2) {
            if (ticksUntilBirth >= 4 && this.onGround() && this.getCurrentAction() === Action.NULL) {
                this.setTargetPosition(this.position())
                this.setYawRotation(this.yRot)
                this.entityData.set(IS_ANCHORED, true)
                this.setNoGravity(true)
                this.noPhysics = true
                this.setCurrentAction(Action.STARTDOGGY)
            } else {
                val player = this.level().getNearestPlayer(this, 1.0)
                if (player != null && player.onGround() && getActiveSceneInfo(player) == null) {
                    this.setTargetPosition(this.position())
                    this.setYawRotation(this.yRot)
                    this.entityData.set(IS_ANCHORED, true)
                    this.setNoGravity(true)
                    this.noPhysics = true
                    player.setNoGravity(true)
                    player.noPhysics = true
                    PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
                    this.setInteractionPlayerUUID(player.uuid)
                    player.yRot = this.getYawRotation()
                    val offset = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 0.65f.toDouble()), this.getYawRotation())
                    player.setPos(this.x + offset.x, this.y, this.z + offset.z)
                    if (this.getCurrentAction() === Action.WAITDOGGY) {
                        this.setCurrentAction(Action.DOGGYSTART)
                    } else {
                        this.setCurrentAction(Action.SUCKBLOWJOB)
                    }
                }
            }
        }
    }

    /**
     * The procedural jump machine. CLIENT: cycles the jump animation states on
     * takeoff/landing and locks yaw to `TARGET_YAW`. SERVER: every ~50
     * ticks performs the hop (stop, face target, launch) and increments
     * `TICKS_UNTIL_BIRTH` at tick 50, triggering the undress at
     * progress 1. `TARGET_YAW` aims at the nearest player once pregnant.
     */
    fun handleJumpState() {
        if (this.level().isClientSide) {
            if (this.jumpTicks.toDouble() == 90.0) {
                this.slimeState = SlimeEntityState.JUMP_START
            }

            if (!this.wasOnGround && this.onGround()) {
                this.slimeState = SlimeEntityState.JUMP_END
                this.jumpTicks = 0
            }

            val targetYaw = this.entityData.get(TARGET_YAW)
            this.yRot = targetYaw
            this.yHeadRot = targetYaw
            this.yBodyRot = targetYaw
        } else {
            if (this.jumpTicks.toDouble() == 85.0) {
                this.entityData.set(TARGET_YAW, this.getBirthProgress())
            }

            if (this.jumpTicks.toDouble() == 100.0) {
                this.stopMovement()
            }

            if (!this.wasOnGround && this.onGround()) {
                this.isJumping = this.entityData.get(HORNY_LEVEL) == -1 && this.random.nextFloat() < 0.1f
            }

            if (this.isJumping && this.jumpTicks == 50) {
                val ticks = this.entityData.get(TICKS_UNTIL_BIRTH)
                val newTicks = ticks + 1
                this.entityData.set(TICKS_UNTIL_BIRTH, newTicks)
                if (newTicks == 1) {
                    this.setCurrentAction(Action.UNDRESS)
                }
            }
        }

        if (this.onGround()) {
            this.jumpTicks++
        }

        this.wasOnGround = this.onGround()
    }

    /**
     * SERVER: ends one hop — zeroes motion, jumps, rotates to the target yaw
     * and launches toward it at 0.7 blocks/tick. Resets the hop counter.
     */
    fun stopMovement() {
        this.setDeltaMovement(0.0, 0.0, 0.0)
        this.jumpFromGround()
        val targetYaw = this.entityData.get(TARGET_YAW)
        this.yRot = targetYaw
        this.yRotO = targetYaw
        var vec = Vec3(0.0, 0.0, 0.7f.toDouble())
        vec = VectorMath.rotateByYaw(vec, targetYaw)
        this.setDeltaMovement(vec.x, this.deltaMovement.y, vec.z)
        this.jumpTicks = 0
    }

    /**
     * SERVER: computes the hop target yaw — random when not pregnant, otherwise
     * aimed at the nearest player (who is not already in a scene).
     */
    fun getBirthProgress(): Float {
        val ticks = this.entityData.get(TICKS_UNTIL_BIRTH)
        if (this.entityData.get(HORNY_LEVEL) != -1) {
            return this.getRandomAngle()
        } else if (ticks < 2) {
            return this.getRandomAngle()
        } else {
            val player = this.level().getNearestPlayer(this, 30.0) ?: return this.getRandomAngle()
            return if (getActiveSceneInfo(player) != null) {
                this.getRandomAngle()
            } else {
                Math.atan2(this.z - player.z, this.x - player.x).toFloat() * (180.0 / Math.PI).toFloat() + 90.0f
            }
        }
    }

    fun getRandomAngle(): Float = Reference.RANDOM.nextFloat() * 360.0f

    /** 1.12 `fall(distance, multiplier)` was empty: no fall damage. */
    override fun causeFallDamage(fallDistance: Float, multiplier: Float, source: DamageSource): Boolean = false

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() !== Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.slime.fhappy", true, event)
                } else {
                    this.createAnimation("animation.slime.null", true, event)
                }
            }
            "action" -> {
                if (this.getCurrentAction() === Action.NULL) {
                    this.createAnimation(this.slimeState.animationId, true, event)
                } else {
                    when (this.getCurrentAction()) {
                        Action.UNDRESS -> this.createAnimation("animation.slime.undress", false, event)
                        Action.DRESS -> this.createAnimation("animation.slime.dress", false, event)
                        Action.STRIP -> this.createAnimation("animation.slime.strip", false, event)
                        Action.STARTBLOWJOB -> this.createAnimation("animation.slime.blowjobintro", false, event)
                        Action.SUCKBLOWJOB -> this.createAnimation("animation.slime.blowjobsuck", true, event)
                        Action.THRUSTBLOWJOB -> this.createAnimation("animation.slime.blowjobthrust", true, event)
                        Action.CUMBLOWJOB -> this.createAnimation("animation.slime.blowjobcum", false, event)
                        Action.STARTDOGGY -> this.createAnimation("animation.slime.doggygoonbed", false, event)
                        Action.WAITDOGGY -> this.createAnimation("animation.slime.doggywait", true, event)
                        Action.DOGGYSTART -> this.createAnimation("animation.slime.doggystart", false, event)
                        Action.DOGGYSLOW -> this.createAnimation("animation.slime.doggyslow", true, event)
                        Action.DOGGYFAST -> this.createAnimation("animation.slime.doggyfast", true, event)
                        Action.DOGGYCUM -> this.createAnimation("animation.slime.doggycum", false, event)
                        else -> {}
                    }
                }
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * pregnancy undress/dress, the jump cycle and the blowjob/doggy scenes.
     * Scene end: `bjcDone`/`doggyCumDone` ->
     * `resetCameraAndPhysics()` and pregnancy start
     * (`pregnant = 2400`); `undress` -> nude + NULL action.
     * Only the action and eyes controllers are registered (no movement).
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "undress" -> if (this.isLocalPlayerNearby()) {
                    this.changeDataParameterFromClient("currentModel", "0")
                    this.setCurrentAction(Action.NULL)
                }
                "dress" -> if (this.isLocalPlayerNearby()) {
                    this.entityData.set(OUTFIT_INDEX, 1)
                    this.setCurrentAction(null)
                    this.resetCameraAndPhysics()
                }
                "becomeNude" -> this.entityData.set(OUTFIT_INDEX, 0)
                "sexUiOn" -> if (this.isControlledByLocalPlayer() && !HornyMeterHud.isVisible) {
                    HornyMeterHud.showHornyMeter()
                }
                "bjiMSG10" -> if (this.isControlledByLocalPlayer()) {
                    this.positionPlayerRelative(-0.4, -0.8, -0.2, 60.0f, -3.0f)
                }
                "bjiMSG11" -> {
                    this.playSoundAtVolume(SoundEvents.SLIME_SQUISH, 0.5f)
                    this.addToHornyMeterIfControlled(0.02)
                }
                "bjiMSG12" -> {
                    if (Reference.RANDOM.nextInt(5) == 0) {
                        this.playSoundAtVolume(SoundEvents.SLIME_JUMP, 0.5f)
                    }

                    this.playSoundAtVolume(SoundEvents.SLIME_SQUISH, 0.5f)
                    this.addToHornyMeterIfControlled(0.02)
                }
                "bjtMSG1" -> {
                    this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                    this.playSound(SoundEvents.SLIME_DEATH)
                    this.addToHornyMeterIfControlled(0.04)
                }
                "bjiDone" -> {
                    this.setCurrentAction(Action.SUCKBLOWJOB)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "bjtDone" -> this.setCurrentAction(Action.SUCKBLOWJOB)
                "bjtReady", "doggyfastReady" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
                }
                "bjcMSG1" -> this.playSound(SoundEvents.SLIME_JUMP)
                "bjcMSG2" -> {
                    this.playSound(SoundEvents.SLIME_JUMP)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.hideHornyMeter()
                    }
                }
                "doggyslowMSG2" -> this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                "bjcBlackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "bjcDone", "doggyCumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                    this.changeDataParameterFromClient("pregnant", 2400.toString())
                }
                "doggyGoOnBedMSG1" -> {
                    this.playSound(SoundEvents.SLIME_SQUISH)
                    this.cameraYaw = this.yRot
                }
                "doggyGoOnBedDone" -> this.setCurrentAction(Action.WAITDOGGY)
                "doggystartMSG1" -> this.playSound(SoundHandler.MISC_TOUCH[0])
                "doggystartMSG2" -> this.playSound(SoundHandler.MISC_TOUCH[1])
                "doggystartMSG3" -> this.playSoundAtVolume(SoundEvents.SLIME_SQUISH, 0.25f)
                "doggystartMSG4" -> this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_SMALLINSERTS), 1.5f)
                "doggystartMSG5" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.33f)
                    this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                }
                "doggystartDone" -> {
                    this.setCurrentAction(Action.DOGGYSLOW)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "doggyslowMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.33f)
                    var choice = Reference.RANDOM.nextInt(4)
                    if (choice == 0) {
                        choice = Reference.RANDOM.nextInt(2)
                        if (choice == 0) {
                            this.playSound(SoundEvents.SLIME_JUMP)
                        } else {
                            this.playSound(SoundEvents.SLIME_SQUISH)
                        }
                    } else {
                        this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                    }

                    this.addToHornyMeterIfControlled(0.02)
                }
                "doggyfastMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    this.addToHornyMeterIfControlled(0.04)

                    this.blinkTicks++
                    if (this.blinkTicks % 2 == 0) {
                        val choice2 = Reference.RANDOM.nextInt(2)
                        if (choice2 == 0) {
                            this.playSound(SoundEvents.SLIME_JUMP)
                        } else {
                            this.playSound(SoundEvents.SLIME_SQUISH)
                        }
                    } else {
                        this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                    }
                }
                "doggyfastDone" -> this.setCurrentAction(Action.DOGGYSLOW)
                "doggycumMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.MISC_CUMINFLATION[0], 4.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 2.0f)
                    this.playSound(SoundEvents.SLIME_DEATH)
                }
                "jumpStart" -> this.playSound(SoundEvents.SLIME_JUMP)
                "jumpStartDone" -> this.slimeState = SlimeEntityState.JUMP_AIR
                "jumpEndSound" -> this.playSound(SoundEvents.SLIME_SQUISH)
                "jumpEndDone" -> this.slimeState = SlimeEntityState.IDLE
            }
        }
        val action = this.actionController!! // java-npe: created above
        action.registerSoundListener(soundListener)
        controllers.add(action)
        controllers.add(this.eyesController!!) // java-npe
    }

    enum class SlimeEntityState(@JvmField val animationId: String) {
        IDLE("animation.slime.idle"),
        JUMP_START("animation.slime.jumpstart"),
        JUMP_AIR("animation.slime.jumpair"),
        JUMP_END("animation.slime.jumpend");

        fun getAnimationId(): String = this.animationId
    }

    companion object {
        const val PREG_SCALE_0_7: Double = 0.699999988079071 // 1.12 `(double) 0.7F`
        const val PREG_SCALE_0_9: Float = 0.9f
        const val PREG_MAX_100: Double = 100.0
        const val PREG_GROWTH_0_1: Float = 0.1f
        const val PREGNANT_TICKS: Int = 2400

        @JvmField
        val HORNY_LEVEL: EntityDataAccessor<Int> = SynchedEntityData.defineId(SlimeEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val TARGET_YAW: EntityDataAccessor<Float> = SynchedEntityData.defineId(SlimeEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val TICKS_UNTIL_BIRTH: EntityDataAccessor<Int> = SynchedEntityData.defineId(SlimeEntity::class.java, EntityDataSerializers.INT)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = BaseGirlEntity.createAttributes()
    }
}
