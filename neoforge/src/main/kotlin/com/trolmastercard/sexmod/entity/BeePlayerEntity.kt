package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.BeeModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.SoundHandler
import java.util.UUID
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
 * <b>Role.</b> Player-form Bee — the transformation with the single citizen
 * scene (start/slow/fast/cum). Flight is granted to the owner while the
 * transformation is active (`onTransformationSpawned`/`onTickClient` pair).
 * Progression and scene end run in the sound listener
 * (`sex_cumDone` -> `resetCameraAndPhysics()`).
 */
class BeePlayerEntity : AbstractPlayerGirlEntity {
    /** Registry factory (used by ModEntities); 1.12 protected `BeePlayerEntity(World)`. */
    constructor(type: EntityType<out BeePlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_BEE.get(), level, uuid)

    override fun onTransformationSpawned() {
        this.handleOwnerUUID(true)
    }

    override fun onTickClient() {
        this.handleOwnerUUID(false)
    }

    override fun getScaleFactor(): Float = 1.4f

    /** 1.12 `getEyeHeight()` = 1.3. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.3f)

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/bee/hand.png"

    /**
     * SERVER: the single owner command (sex) — broadcasts, strips, starts
     * [Action.CITIZEN_START] and positions the acting player in front of
     * the girl.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        this.sendActionPacket(0, Action.CITIZEN_START)
        this.setOutfitIndex(0)
        this.setCurrentAction(Action.CITIZEN_START)
        this.teleportPlayerToGirl(uuid)
        val player = this.level().getPlayerByUUID(uuid)
        if (player != null) {
            val offset = this.getVectorTowardPlayer(-0.2)
            player.teleportTo(offset.x, offset.y, offset.z)
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("action.names.sex"), false)
        return true
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.CITIZEN_CUM || action !== Action.CITIZEN_FAST && action !== Action.COWGIRLSLOW) {
            super.setCurrentAction(action)
        }
    }

    override fun tick() {
        super.tick()
    }

    override fun canBeInteracted(): Boolean = false

    override fun getNextAction(action: Action): Action? = if (action === Action.CITIZEN_SLOW) Action.CITIZEN_FAST else null

    override fun getCumAction(action: Action): Action? =
        if (action !== Action.CITIZEN_FAST && action !== Action.CITIZEN_SLOW) null else Action.CITIZEN_CUM

    override fun reinitTasks() {
        super.reinitTasks()
        this.setOutfitIndex(1)
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.bee.null", true, event)
                } else {
                    this.createAnimation("animation.bee.idle", true, event)
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.NULL -> this.createAnimation("animation.bee.null", false, event)
                Action.CITIZEN_START -> this.createAnimation("animation.bee.sex_start", false, event)
                Action.CITIZEN_SLOW -> this.createAnimation("animation.bee.sex_slow", true, event)
                Action.CITIZEN_FAST -> this.createAnimation("animation.bee.sex_fast", true, event)
                Action.CITIZEN_CUM -> this.createAnimation("animation.bee.sex_cum", false, event)
                Action.THROW_PEARL -> this.createAnimation("animation.bee.throw_pearl", true, event)
                Action.ATTACK -> this.createAnimation("animation.bee.attack" + this.nextAttack, false, event)
                Action.BOW -> this.createAnimation("animation.bee.bowcharge", false, event)
                Action.RIDE -> this.createAnimation("animation.bee.ride", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * citizen scene; `sex_startDone`/`sex_fastDone` ->
     * [Action.CITIZEN_SLOW] (jump keeps fast), `sex_cumDone` ->
     * `resetCameraAndPhysics()`. Only action + movement controllers (as in 1.12).
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            // Java fall-through: sex_fastDone -> sex_startDone
            val startDone = {
                this.setCurrentAction(Action.CITIZEN_SLOW)
                if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }
            when (sound.keyframeData.sound) {
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "pearl" -> if (this.isLocalPlayerNearby() && this.getCurrentAction() === Action.THROW_PEARL) {
                    PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                }
                "resetCumPercentage" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                }
                "sex_fastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }
                }
                "sex_startMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                    }
                }
                "sex_fastReady" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
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
            }
        }
        val action = this.actionController!! // java-npe: created above
        action.registerSoundListener(soundListener)
        controllers.add(action)
        controllers.add(this.movementController!!) // java-npe
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = BeeModel()
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
