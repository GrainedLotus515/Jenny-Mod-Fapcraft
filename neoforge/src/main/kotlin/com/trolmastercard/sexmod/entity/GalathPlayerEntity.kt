package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.api.IPositionProvider
import com.trolmastercard.sexmod.api.ITargetProvider
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.GalathFlightHud
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.CatModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.entity.api.IGalath
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.TeleportPlayerPacket
import com.trolmastercard.sexmod.networking.UpdateVelocityPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.DynamicTrailRenderer
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.Vector4d
import com.trolmastercard.sexmod.util.VectorMath
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form Galath — the transformation with the rape
 * (intro/ongoing/cum) and corrupt (slow/fast/cum) scenes. The owner gets
 * flight while transformed (`onTransformationSpawned`/`onTickClient` pair).
 *
 * <b>Scene flow.</b> Owner commands `cowgirl`/`mating press`
 * start the scenes via [handleOwnerCommand];
 * [handleCumState] keeps the wings animated during the scenes
 * ([wingsAnimated] flag), [handlePlayerAction] hides the horny meter during
 * the rape intro. Rape variant cycling ([rapeVariant]) and scene end
 * (`reset` -> `resetCameraAndPhysics()`) run in the sound listener.
 */
open class GalathPlayerEntity : AbstractPlayerGirlEntity, IGalath {
    @JvmField
    var wingsAnimated: Boolean = false

    @JvmField
    var rapeVariant: Int = 0

    @JvmField
    var corruptHard: Boolean = false

    @JvmField
    var corruptCameraActive: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out GalathPlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level) : this(ModEntities.PLAYER_GALATH.get(), level)

    /** Reflected by `UpdatePlayerModelPacket` (getConstructor(Level, UUID)). */
    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_GALATH.get(), level, uuid)

    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/galath/hand.png"

    override fun getNextAction(action: Action): Action? = null

    override fun getCumAction(action: Action): Action? {
        return if (action == Action.CORRUPT_FAST || action == Action.CORRUPT_SLOW) {
            Action.CORRUPT_CUM
        } else {
            if (action == Action.RAPE_ON_GOING) Action.RAPE_CUM else null
        }
    }

    override fun getScaleFactor(): Float = 2.3f

    /**
     * SERVER: owner commands — `cowgirl` starts [Action.RAPE_INTRO],
     * `mating press` starts [Action.CORRUPT_SLOW] and repositions
     * the player via [handleGalathPlayerOwner]. Both broadcast and
     * teleport the acting player into the scene.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("cowgirl" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.RAPE_INTRO)
            this.sendActionPacket(this.getOutfitIndex(), Action.RAPE_INTRO)
        } else if ("mating press" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.CORRUPT_SLOW)
            this.sendActionPacket(this.getOutfitIndex(), Action.CORRUPT_SLOW)
            this.handleGalathPlayerOwner()
        }
    }

    /**
     * Guards the state machine: refuses re-entry into loop phases while the
     * corrupt/rape cum animations play and resets the hard-variant flag
     * ([corruptHard]) when entering [Action.CORRUPT_SLOW].
     */
    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction != Action.CORRUPT_CUM || action != Action.CORRUPT_FAST && action != Action.CORRUPT_SLOW) {
            if (currentAction != Action.RAPE_CUM || action != Action.RAPE_ON_GOING) {
                if (currentAction != Action.RAPE_CUM || action != Action.RAPE_CUM_IDLE) {
                    if (action == Action.CORRUPT_SLOW) {
                        this.corruptHard = false
                    }

                    super.setCurrentAction(action)
                }
            }
        }
    }

    fun handleGalathPlayerOwner() {
        val player = this.getPlayerEntity()
        if (player != null) {
            val pos = VectorMath.rotateByYaw(Vec3(0.5, (0.5f - player.eyeHeight).toDouble(), 0.4f.toDouble()), this.getYawRotation())
                .add(this.getTargetPosition())
            player.teleportTo(pos.x, pos.y, pos.z)
        }
    }

    override fun isHuggingManglelie(): Boolean = false

    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("cowgirl", "mating press", "ride"), false)
        return true
    }

    override fun canBeInteracted(): Boolean = false

    override fun blocksOutfitToggle(): Boolean = false

    override fun getFlightData(): Vector4d = Vector4d(0.0, 0.0, 0.0, 0.0)

    override fun isWingsAnimated(): Boolean = this.getOutfitIndex() == 0 || this.wingsAnimated

    override fun areWingsAnimated(): Boolean {
        return when (this.getCurrentAction()) {
            Action.CORRUPT_CUM, Action.CORRUPT_FAST, Action.CORRUPT_SLOW, Action.COWGIRLCUM -> false
            else -> true
        }
    }

    override fun onTransformationSpawned() {
        this.handleOwnerUUID(true)
    }

    /**
     * BOTH sides: keeps the wing-animation flag ([wingsAnimated]) set while any
     * rape/corrupt action plays, and CLIENT-side hides the horny meter during
     * the rape intro.
     */
    override fun tick() {
        super.tick()
        this.handleCumState()
        if (this.level().isClientSide) {
            this.handlePlayerAction()
        }
    }

    /** CLIENT. */
    fun handlePlayerAction() {
        if (this.isControlledByLocalPlayer()) {
            if (this.getCurrentAction() == Action.RAPE_INTRO) {
                HornyMeterHud.setHornyMeterVisible(false)
            }
        }
    }

    fun handleCumState() {
        when (this.getCurrentAction()) {
            Action.CORRUPT_CUM, Action.CORRUPT_FAST, Action.CORRUPT_SLOW, Action.RAPE_INTRO, Action.RAPE_ON_GOING,
            Action.RAPE_CUM, Action.RAPE_CHARGE, Action.RAPE_CUM_IDLE,
            -> {
                this.wingsAnimated = true
                return
            }
            else -> this.wingsAnimated = false
        }
    }

    fun hasNoGalathOwner(): Boolean {
        val player = this.getOwnerPlayer()
        return if (player == null) {
            false
        } else {
            !this.level().getBlockState(player.blockPosition().above().above()).`is`(Blocks.AIR)
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "eyes" -> if (this.getCurrentAction() == Action.NULL && this.getCurrentAction().autoBlink) {
                this.createAnimation("animation.galath.blink", true, event)
            } else {
                this.createAnimation("animation.galath.null", true, event)
            }
            "movement" -> {
                val movement = this.movementController!! // java-npe
                movement.setAnimationSpeed(1.0)
                if (this.getCurrentAction() != Action.NULL) {
                    this.createAnimation("animation.galath.null", true, event)
                } else if (this.playerRiding) {
                    this.createAnimation("animation.galath.sit", true, event)
                } else if (!this.playerOnGround) {
                    this.createAnimation("animation.galath.controlled_flight", true, event)
                } else if (Math.abs(this.playerMotionOffset.x) + Math.abs(this.playerMotionOffset.y) == 0.0f) {
                    this.createAnimation(if (this.hasNoGalathOwner()) "animation.galath.crouchidle" else "animation.galath.idle", true, event)
                } else if (this.playerSprinting) {
                    movement.setAnimationSpeed(1.5)
                    this.createAnimation(if (this.hasNoGalathOwner()) "animation.galath.crouchwalk" else "animation.galath.run", true, event)
                } else if (this.playerMotionOffset.y >= -0.1f) {
                    movement.setAnimationSpeed(2.0)
                    this.createAnimation(if (this.hasNoGalathOwner()) "animation.galath.crouchwalk" else "animation.galath.walk", true, event)
                } else {
                    movement.setAnimationSpeed(1.5)
                    this.createAnimation(
                        if (this.hasNoGalathOwner()) "animation.galath.crouchwalk" else "animation.galath.backwards_walk", true, event,
                    )
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.CORRUPT_CUM -> this.createAnimation("animation.galath.corrupt_cum", true, event)
                Action.CORRUPT_FAST -> this.createAnimation("animation.galath.corrupt_" + (if (this.corruptHard) "hard" else "soft"), true, event)
                // Java: CORRUPT_SLOW falls through into COWGIRLCUM/RAPE_CHARGE/default, which only break
                Action.CORRUPT_SLOW -> this.createAnimation("animation.galath.corrupt_slow", true, event)
                Action.RAPE_INTRO -> this.createAnimation("animation.galath.rape_intro", true, event)
                Action.RAPE_ON_GOING -> this.createAnimation("animation.galath.rape" + this.rapeVariant, true, event)
                Action.RAPE_CUM -> this.createAnimation("animation.galath.rape_cum", true, event)
                Action.RAPE_CUM_IDLE -> this.createAnimation("animation.galath.rape_cum_idle", true, event)
                Action.NULL -> return PlayState.STOP
                Action.STRIP -> this.createAnimation("animation.galath.strip", true, event)
                Action.ATTACK -> this.createAnimation("animation.galath.attack" + this.nextAttack, true, event)
                Action.BOW -> this.createAnimation("animation.galath.bowcharge", true, event)
                Action.RIDE, Action.SIT -> this.createAnimation("animation.galath.sit", true, event)
                Action.CORRUPT_INTRO -> this.createAnimation("animation.galath.corrupt_intro", true, event)
                Action.CONTROLLED_FLIGHT -> this.createAnimation("animation.galath.controlled_flight", true, event)
                else -> {
                }
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * rape/corrupt scenes — `rapeIntroDone` -> [Action.RAPE_ON_GOING],
     * `rape_switch` re-rolls the variant ([rapeVariant]), jump toggles corrupt
     * hard, `reset` -> `resetCameraAndPhysics()`, and the creampie trails spawn on
     * `creampie`.
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        this.initAnimationControllers()
        val action = this.actionController!! // java-npe
        action.registerSoundListener { sound ->
            var fallReset = false
            when (sound.keyframeData.sound) {
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "cum" -> this.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 2.0f)
                "pound" -> this.playRandomSound(SoundHandler.MISC_POUNDING)
                "flap" -> this.playRandomSound(SoundHandler.MISC_FLAP)
                "setNude" -> {
                    this.wingsAnimated = true
                    val pos = this.position()
                    val slipRPos = this.getCachedBoneOffset("slipR").add(pos)
                    val slipLPos = this.getCachedBoneOffset("slipL").add(pos)
                    val turnablePos = this.getCachedBoneOffset("turnable").add(pos)
                    this.level().addParticle(ParticleTypes.DRAGON_BREATH, slipRPos.x, slipRPos.y, slipRPos.z, 0.0, 0.0, 0.0)
                    this.level().addParticle(ParticleTypes.DRAGON_BREATH, slipLPos.x, slipLPos.y, slipLPos.z, 0.0, 0.0, 0.0)
                    this.level().addParticle(ParticleTypes.DRAGON_BREATH, turnablePos.x, turnablePos.y, turnablePos.z, 0.0, 0.0, 0.0)
                }
                "rapeIntroDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.RAPE_ON_GOING)
                }
                "rape_switch" -> {
                    val random = this.random
                    val oldState = this.rapeVariant

                    do {
                        this.rapeVariant = random.nextInt(3)
                    } while (this.rapeVariant == oldState)

                    return@registerSoundListener
                }
                "poundRape" -> {
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.03f.toDouble())
                    }
                }
                "enableRapeUI" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.setHornyMeterVisible(false)
                }
                "reloadRenderer" -> {
                    if (!this.isControlledByLocalPlayer()) {
                        return@registerSoundListener
                    }

                    if (ClientHooks.thirdPersonView() != 0) {
                        Client.reloadRenderers()
                    }
                }
                "corruptSwitch" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.CORRUPT_FAST)
                }
                "corrupt_hard" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.corruptHard = true
                    this.resetAnimationControllerOffset()
                }
                "corrupt_hard_end" -> {
                    this.setCurrentAction(Action.CORRUPT_SLOW)
                    this.corruptHard = false
                }
                "addCum" -> HornyMeterHud.addToHornyMeter(0.03)
                "clearcum" -> {
                    Client.spawnCummyTrails(this)
                    // Java falls through into "reset"
                    fallReset = true
                }
                "reset" -> fallReset = true
                "setCamCorrupt" -> {
                    if (!this.isControlledByLocalPlayer()) {
                        return@registerSoundListener
                    }

                    this.corruptCameraActive = true
                    val corruptPlayer = ClientHooks.localPlayer()!! // java-npe
                    val corruptYaw = this.getYawRotation() + 220.0f
                    val corruptPos = VectorMath.rotateByYaw(Vec3(0.5, (0.5f - corruptPlayer.eyeHeight).toDouble(), 0.4f.toDouble()), this.getYawRotation())
                        .add(this.getTargetPosition())
                    PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(corruptPlayer.uuid.toString(), corruptPos, corruptYaw, 15.0f))
                    HornyMeterHud.showHornyMeter()
                }
                "enableBoyCam" -> if (this.isControlledByLocalPlayer()) {
                    this.corruptCameraActive = false
                }
                "creampie" -> {
                    Client.registerCreampieTrails(this)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_SMALLINSERTS), 3.0f)
                }
                "blackScreenTamed", "blackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "flapControlled" -> if (this.isControlledByLocalPlayer()) {
                    GalathFlightHud.showHud()
                    this.playRandomSound(SoundHandler.MISC_FLAP)
                    Client.sendFlightInput(this)
                }
                "clap" -> this.playRandomSound(SoundHandler.MISC_CLAP)
                "energysound" -> this.playSound(SoundHandler.MISC_BEEW[1])
                "energy2" -> this.playSound(SoundHandler.MISC_BEEW[2])
                "tpSound" -> this.playSound(SoundHandler.MISC_WEOWEO[2])
                "sexui" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }

            if (fallReset) {
                if (this.isControlledByLocalPlayer()) {
                    this.resetCameraAndPhysics()
                }
            }
        }
        controllers.add(action)
        controllers.add(this.eyesController!!) // java-npe
        controllers.add(this.movementController!!) // java-npe
    }

    /** Client-only helpers (PORTING.md §2.3 Rule C). */
    private object Client {
        fun handModel(): IVanillaModel = CatModel()

        fun reloadRenderers() {
            // 1.12 mc.renderGlobal.loadRenderers()
            Minecraft.getInstance().levelRenderer.allChanged()
        }

        fun spawnCummyTrails(girl: BaseGirlEntity) {
            CummyEntity.spawnCummyTrails(girl)
        }

        fun registerCreampieTrails(galath: GalathPlayerEntity) {
            CummyEntity.registerTrail(
                DynamicTrailRenderer(
                    130,
                    IPositionProvider { girl ->
                        val cockTipPos = girl.getBoneWorldPosition("futaCockTip")
                        val tipDir = girl.getBoneWorldPosition("futaCockTipDirHelp")
                        cockTipPos.subtract(tipDir).normalize()
                    },
                    ITargetProvider { girl -> girl.getCachedBoneOffset("futaCockTip").add(girl.getTargetPosition()) },
                    galath, 0.3f, 0.3f,
                ),
            )
            CummyEntity.registerTrail(
                DynamicTrailRenderer(
                    100,
                    IPositionProvider { _ -> VectorMath.rotateByYaw(Vec3(0.0, 0.0, 0.6f.toDouble()), galath.getYawRotation()) },
                    ITargetProvider { girl -> girl.getCachedBoneOffset("creampiePos").add(girl.getTargetPosition()) },
                    galath, 0.6f, 0.5f,
                ),
            )
        }

        fun sendFlightInput(galath: GalathPlayerEntity) {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            val input = player.input
            val moveVec = input.moveVector
            if (moveVec.x != 0.0f || moveVec.y != 0.0f) {
                val partialTicks = mc.timer.getGameTimeDeltaPartialTick(true)
                val vel = VectorMath.rotateByYawPitch(
                    Vec3(-moveVec.x.toDouble(), 0.0, moveVec.y.toDouble()),
                    RotationHelper.lerp(player.xRotO, player.xRot, partialTicks),
                    RotationHelper.lerp(player.yHeadRotO, player.yHeadRot, partialTicks),
                )
                PacketHandler.networkWrapper.sendToServer(UpdateVelocityPacket(vel, galath.getGirlId()))
            }
        }
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
