package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.BiaModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.KoboldStatePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.SoundHandler
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form Allie — the transformation with the deepthroat and
 * reverse-cowgirl scenes of the NPC, minus the summon/wish flow. The owner
 * gets flight while transformed (`onTransformationSpawned`/`onTickClient`
 * pair).
 *
 * <b>Scene flow.</b> Owner commands `deepthroat`/`Reverse cowgirl`
 * start the scenes; `deepthroat_prepareDone` (sound listener) sends
 * [KoboldStatePacket] to lock the player in and repositions the camera.
 * Scene end via `cowgirl_cumDone`/`deepthroat_cumDone` ->
 * `resetCameraAndPhysics()`. [usesInvertedCamera] marks the deepthroat
 * actions for first-person rendering.
 */
class AlliePlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var scaleOffset: Float = 0.0f

    @JvmField
    var knownOwnerPlayer: Player? = null

    @JvmField
    var moanToggle: Boolean = false

    @JvmField
    var slowVariant: Int = 1

    @JvmField
    var fastVariant: Int = 1

    /** Registry factory (used by ModEntities); 1.12 protected `AlliePlayerEntity(World)`. */
    constructor(type: EntityType<out AlliePlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_ALLIE.get(), level, uuid)

    override fun getScaleFactor(): Float = 1.9f + this.scaleOffset

    /** 1.12 `getEyeHeight()` = 1.63. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.63f)

    override fun canBeInteracted(): Boolean = false

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/allie/hand.png"

    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("action.names.deepthroat" == command) {
            this.setCurrentAction(Action.DEEPTHROAT_START)
            this.sendActionPacket(this.getOutfitIndex(), Action.DEEPTHROAT_START)
            this.teleportPlayerToGirl(uuid)
        }

        if ("Reverse cowgirl" == command) {
            this.setCurrentAction(Action.REVERSE_COWGIRL_START)
            this.sendActionPacket(0, Action.REVERSE_COWGIRL_START)
            this.teleportPlayerToGirl(uuid)
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("action.names.deepthroat", "Reverse cowgirl"), false)
        return true
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.DEEPTHROAT_CUM || action !== Action.DEEPTHROAT_FAST && action !== Action.DEEPTHROAT_SLOW) {
            if (this.getCurrentAction() !== Action.REVERSE_COWGIRL_CUM ||
                action !== Action.REVERSE_COWGIRL_SLOW && action !== Action.REVERSE_COWGIRL_FAST_START &&
                action !== Action.REVERSE_COWGIRL_FAST_CONTINUES
            ) {
                super.setCurrentAction(action)
            }
        }
    }

    override fun usesInvertedCamera(): Boolean {
        return when (this.getCurrentAction()) {
            Action.ALLIE_PREPARE_NORMAL, Action.DEEPTHROAT_START, Action.DEEPTHROAT_CUM, Action.DEEPTHROAT_FAST,
            Action.ALLIE_PREPARE_FIRST_TIME, Action.DEEPTHROAT_SLOW,
            -> true
            else -> false
        }
    }

    override fun updateAITasks() {
        super.updateAITasks()
        val ownerUuid = this.getOwnerUserUUID()
        if (ownerUuid != null) {
            val player = this.level().getPlayerByUUID(ownerUuid)
            if (player != null && this.knownOwnerPlayer == null) {
                this.handleOwnerUUID(true)
            }

            this.knownOwnerPlayer = player
        }
    }

    override fun tick() {
        super.tick()
        if (this.level().isClientSide) {
            this.spawnAllieParticles()
        }
    }

    /** CLIENT. */
    fun spawnAllieParticles() {
        if (this.tickCount % 10 == 0) {
            val i = this.random.nextInt(8)
            val offset = this.getCachedBoneOffset("tail$i").add(this.position())
            this.level().addParticle(
                ParticleTypes.PORTAL,
                offset.x,
                offset.y,
                offset.z,
                this.random.nextGaussian() * 0.01f,
                this.random.nextGaussian() * 0.01f,
                this.random.nextGaussian() * 0.01f,
            )
        }
    }

    override fun onTransformationSpawned() {
        this.handleOwnerUUID(true)
    }

    override fun onTickClient() {
        this.handleOwnerUUID(false)
    }

    override fun getNextAction(action: Action): Action? {
        if (action === Action.DEEPTHROAT_SLOW) {
            return Action.DEEPTHROAT_FAST
        } else {
            return if (action === Action.REVERSE_COWGIRL_SLOW) Action.REVERSE_COWGIRL_FAST_START else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        if (action === Action.DEEPTHROAT_FAST || action === Action.DEEPTHROAT_SLOW) {
            return Action.DEEPTHROAT_CUM
        } else {
            return if (action !== Action.REVERSE_COWGIRL_SLOW && action !== Action.REVERSE_COWGIRL_FAST_START &&
                action !== Action.REVERSE_COWGIRL_FAST_CONTINUES
            ) {
                null
            } else {
                Action.REVERSE_COWGIRL_CUM
            }
        }
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * deepthroat/reverse-cowgirl scenes; `deepthroat_prepareDone` sends
     * the [KoboldStatePacket] lock-in, `cowgirlSlowDone` re-rolls
     * the slow-variant counter (`slowVariant`), `cowgirl_cumDone`/
     * `deepthroat_cumDone` -> `resetCameraAndPhysics()`.
     * Only action + movement controllers (as in 1.12).
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "deepthroat_prepareMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.hihi"))
                    this.playSound(SoundHandler.MISC_PLOB[0])
                }
                "deepthroat_prepareMSG2" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.boys"))
                    this.playSound(SoundHandler.MISC_PLOB[0])
                }
                "blackscreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "deepthroat_prepareDone" -> {
                    this.setCurrentAction(Action.DEEPTHROAT_START)
                    if (this.isControlledByLocalPlayer()) {
                        PacketHandler.networkWrapper.sendToServer(KoboldStatePacket(this.getGirlId(), this.getInteractionPlayerUUID(), false, true))
                        this.cameraYaw = this.yRot + 180.0f
                        this.positionPlayerRelative(0.0, 0.0, 1.35f.toDouble(), 0.0f, 30.0f)
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "deepthroat_fastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_BJMOAN))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }
                }
                "deepthroat_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.DEEPTHROAT_SLOW)
                }
                "deepthroat_startDone" -> this.setCurrentAction(Action.DEEPTHROAT_SLOW)
                "deepthroat_slowMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                        HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                    }
                }
                "deepthroat_cumMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND))
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_CUMINFLATION), 1.5f)
                }
                "cowgirl_cumDone", "deepthroat_cumDone" -> if (this.isControlledByLocalPlayer()) {
                    this.resetCameraAndPhysics()
                }
                "deepthroat_normal_prepareMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.alright"))
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_PLOB))
                }
                "giggle" -> this.playRandomSound(SoundHandler.GIRLS_ALLIE_GIGGLE)
                "pounding" -> this.playRandomSound(SoundHandler.MISC_POUNDING)
                "moan" -> this.playRandomSound(SoundHandler.GIRLS_ALLIE_MOAN)
                "mmm" -> this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_MMM))
                "slide" -> this.playRandomSound(SoundHandler.MISC_SLIDE, 0, 1, 4, 6)
                "slowMoan" -> {
                    if (this.random.nextBoolean()) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_AHH))
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                    }
                }
                "cowgirlSlowDone" -> {
                    val oldState = this.slowVariant

                    do {
                        this.slowVariant = this.random.nextInt(3) + 1
                    } while (this.slowVariant == oldState)
                }
                "fastMoan" -> {
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }

                    if (!this.moanToggle) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_MOAN))
                        this.moanToggle = true
                    } else {
                        this.moanToggle = false
                    }
                }
                "fastSwitch" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    val action = this.getCurrentAction()
                    if (action === Action.REVERSE_COWGIRL_FAST_START) {
                        this.setCurrentAction(Action.REVERSE_COWGIRL_FAST_CONTINUES)
                    } else {
                        this.resetAnimationControllerOffset()
                        val oldCount = this.fastVariant

                        do {
                            this.fastVariant = this.random.nextInt(3) + 1
                        } while (this.fastVariant == oldCount)
                    }
                }
                "openSexUi" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "cum" -> this.playRandomSoundAtVolume(SoundHandler.MISC_INSERTS, 6.0f)
                "aftermoan" -> this.playRandomSound(SoundHandler.GIRLS_ALLIE_AFTERSESSIONMOAN)
            }
        }
        val action = this.actionController!! // java-npe: created above
        action.registerSoundListener(soundListener)
        controllers.add(action)
        controllers.add(this.movementController!!) // java-npe
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() === Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.bia.blink", true, event)
                } else {
                    this.createAnimation("animation.allie.null", true, event)
                }
            }
            "movement" -> {
                var speed = 4.0 * (Math.abs(this.x - this.xOld) + Math.abs(this.y - this.yOld) + Math.abs(this.z - this.zOld))
                speed = Math.min(1.0 + speed, 4.0)
                this.movementController!!.setAnimationSpeed(speed) // java-npe
                this.createAnimation("animation.allie.tail", true, event)
            }
            "action" -> when (this.getCurrentAction()) {
                Action.ALLIE_PREPARE_NORMAL -> this.createAnimation("animation.allie.deepthroat_normal_prepare", false, event)
                Action.DEEPTHROAT_START -> this.createAnimation("animation.allie.deepthroat_start", false, event)
                Action.DEEPTHROAT_CUM -> this.createAnimation("animation.allie.deepthroat_cum", false, event)
                Action.DEEPTHROAT_FAST -> this.createAnimation("animation.allie.deepthroat_fast", true, event)
                Action.ALLIE_PREPARE_FIRST_TIME -> this.createAnimation("animation.allie.deepthroat_prepare", false, event)
                Action.DEEPTHROAT_SLOW -> this.createAnimation("animation.allie.deepthroat_slow", true, event)
                Action.NULL -> this.createAnimation("animation.allie.null", true, event)
                Action.SUMMON -> this.createAnimation("animation.allie.summon", false, event)
                Action.SUMMON_NORMAL -> this.createAnimation("animation.allie.summon_normal", false, event)
                Action.SUMMON_NORMAL_WAIT -> this.createAnimation("animation.allie.summon_normal_wait", true, event)
                Action.SUMMON_WAIT -> this.createAnimation("animation.allie.summon_wait", true, event)
                Action.RICH_FIRST_TIME -> this.createAnimation("animation.allie.rich", false, event)
                Action.RICH_NORMAL -> this.createAnimation("animation.allie.rich_normal", false, event)
                Action.SUMMON_SAND -> this.createAnimation("animation.allie.summon_sand", false, event)
                Action.ATTACK -> this.createAnimation("animation.allie.attack" + this.nextAttack, false, event)
                Action.BOW -> this.createAnimation("animation.allie.bowcharge", false, event)
                Action.REVERSE_COWGIRL_START -> this.createAnimation("animation.allie.reverse_cowgirl_start", true, event)
                Action.REVERSE_COWGIRL_SLOW -> this.createAnimation("animation.allie.reverse_cowgirl_slow" + this.slowVariant, true, event)
                Action.REVERSE_COWGIRL_FAST_CONTINUES -> this.createAnimation("animation.allie.reverse_cowgirl_fastc" + this.fastVariant, true, event)
                Action.REVERSE_COWGIRL_FAST_START -> this.createAnimation("animation.allie.reverse_cowgirl_fasts", true, event)
                Action.REVERSE_COWGIRL_CUM -> this.createAnimation("animation.allie.reverse_cowgirl_cum", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = BiaModel()
    }

    companion object {
        const val au: Double = 4.0
        const val at: Double = 4.0

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
