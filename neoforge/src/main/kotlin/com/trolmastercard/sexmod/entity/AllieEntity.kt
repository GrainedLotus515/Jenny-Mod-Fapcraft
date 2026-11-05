package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.networking.KoboldStatePacket
import com.trolmastercard.sexmod.networking.MakeRichWishPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadInventoryToServerPacket2
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.ItemNbt
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Allie — the summoned lamp genie girl (from the Allies Lamp item,
 * [AllieEntity.LAMP_ITEM] key 111). Not a world-spawned NPC: she is
 * summoned for the lamp holder, grants wishes, and despawns when idle
 * ([updateAITasks] removes her on [Action.NULL]). Scenes:
 * deepthroat and reverse cowgirl, plus the make-me-rich wish.
 *
 * <b>Scene flow.</b> The summon intro (`summonDone` ->
 * [Action.SUMMON_WAIT]) opens the interaction menu; the choice is
 * stored in `GIRL_HAND_STATES` by the `animationFollowUp` packet
 * path and `deepthroat_prepareDone` dispatches it (reverse cowgirl
 * or deepthroat + [KoboldStatePacket] to lock the player in). Scene end
 * via `cowgirl_cumDone`/`deepthroat_cumDone` ->
 * `resetCameraAndPhysics()` + inventory upload
 * ([UploadInventoryToServerPacket2]).
 *
 * <b>Pitfalls.</b> [updateAITasks] despawns her whenever the action
 * returns to [Action.NULL] — any scene teardown must happen before that
 * or she vanishes mid-flow. `LAMP_SCALE` is a despawn animation
 * (1.0 -> 0 triggers the upload and locks movement, `-69` = done).
 * [setCurrentAction] fires [handleAllieOwner] on
 * [Action.REVERSE_COWGIRL_START] (server). The reverse-cowgirl loop
 * variant counters are `stateIndex` (slow, 1..3) and `stateCount`
 * (fast-continues).
 *
 * Port note: the 1.12 size (`LAMP_OFFSET` = 0.5 x 1.0) lives in `ModEntities`.
 */
class AllieEntity : BaseGirlEntity {
    @JvmField
    var LAMP_SCALE: Float = 1.0f

    @JvmField
    var isLampActive: Boolean = false

    @JvmField
    var isDefaultState: Boolean = true

    @JvmField
    var stateIndex: Int = 1

    @JvmField
    var stateCount: Int = 1

    @JvmField
    var stateFlag: Boolean = false

    @JvmField
    var stateFlag2: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out AllieEntity>, level: Level) : super(type, level)

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.ALLIE.get(), level)

    constructor(level: Level, lampItem: ItemStack) : this(level) {
        this.entityData.set(LAMP_ITEM, lampItem)
    }

    override fun getDisplayNameText(): String = "Allie"

    override fun getScaleFactor(): Float = 1.0f

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(LAMP_ITEM, ItemStack.EMPTY)
    }

    fun hasLampItem(): Boolean {
        val nbt = ItemNbt.getTag(this.entityData.get(LAMP_ITEM))
        return if (nbt == null) true else nbt.getInt("sexmodUses") == 1
    }

    /**
     * SERVER: lifecycle guard — Allie only exists while summoned: she removes
     * herself when idle (NULL action) or when her interaction player leaves
     * the world.
     */
    override fun updateAITasks() {
        super.updateAITasks()
        if (this.getCurrentAction() === Action.NULL) {
            this.discard()
        }

        val uuid = this.getInteractionPlayerUUID()
        if (uuid != null) {
            val player = this.level().getPlayerByUUID(uuid)
            if (player == null) {
                this.discard()
            }
        }
    }

    /** CLIENT. */
    override fun onInteractionMenuClosed() {
        if (!this.stateFlag2) {
            this.isLampActive = true
        }
    }

    /**
     * CLIENT: per-tick — drives the despawn animation (LAMP_SCALE shrinking,
     * uploads the inventory and locks movement at 0), opens the interaction
     * menu once when `isLampActive` is set, fires the portal-puff
     * particles and the one-shot spawn burst.
     */
    override fun tick() {
        super.tick()
        if (this.LAMP_SCALE != 1.0f && this.LAMP_SCALE != -69.0f && this.LAMP_SCALE <= 0.0f) {
            if (this.isControlledByLocalPlayer()) {
                PacketHandler.networkWrapper.sendToServer(UploadInventoryToServerPacket2(this.getGirlId()))
                HandlePlayerMovement.setMovementLock(true)
            }

            this.LAMP_SCALE = -69.0f
        }

        if (this.level().isClientSide) {
            if (this.isLampActive) {
                this.openInteraction()
            }

            if (this.isDefaultState) {
                this.resetToDefaultState()
            }

            this.spawnRandomParticles()
        }
    }

    fun spawnRandomParticles() {
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

    /** CLIENT. */
    fun resetToDefaultState() {
        this.isDefaultState = false
        WorldUtils.spawnParticles(this.level(), ParticleTypes.PORTAL, this.position(), 300, 0.75, 1.5)
    }

    /** CLIENT. */
    fun openInteraction() {
        this.openInteractionMenu(ClientHooks.localPlayer()!!) // java-npe: Minecraft.player
        this.isLampActive = false
    }

    /**
     * CLIENT: opens the interaction menu (once per activation) offering the
     * deepthroat, reverse-cowgirl and make-me-rich actions.
     */
    override fun openInteractionMenu(player: Player): Boolean {
        this.stateFlag2 = false
        val options = arrayOf("action.names.makemerichallie", "action.names.deepthroat", "Reverse cowgirl")
        openInventoryGui(player, this, options, false)
        return true
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
     * Guards the state machine: refuses re-entry into loop phases while the
     * cum animation plays and repositions the interacting player onto her
     * target position when the reverse-cowgirl starts (SERVER).
     */
    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.DEEPTHROAT_CUM || action !== Action.DEEPTHROAT_FAST && action !== Action.DEEPTHROAT_SLOW) {
            if (this.getCurrentAction() !== Action.REVERSE_COWGIRL_CUM ||
                action !== Action.REVERSE_COWGIRL_SLOW && action !== Action.REVERSE_COWGIRL_FAST_START &&
                action !== Action.REVERSE_COWGIRL_FAST_CONTINUES
            ) {
                if (!this.level().isClientSide && action === Action.REVERSE_COWGIRL_START) {
                    this.handleAllieOwner()
                }

                super.setCurrentAction(action)
            }
        }
    }

    fun handleAllieOwner() {
        val player = this.getPlayerEntity()
        if (player != null) {
            val targetPos = this.getTargetPosition()
            player.teleportTo(targetPos.x, targetPos.y, targetPos.z)
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

        when (event.controller.name) {
            "eyes" -> if (this.getCurrentAction() !== Action.NULL || !this.getCurrentAction().autoBlink) {
                this.createAnimation("animation.allie.null", true, event)
            }
            "movement" -> this.createAnimation("animation.allie.tail", true, event)
            "action" -> when (this.getCurrentAction()) {
                Action.SUMMON -> this.createAnimation("animation.allie.summon", false, event)
                Action.SUMMON_NORMAL -> this.createAnimation("animation.allie.summon_normal", false, event)
                Action.SUMMON_NORMAL_WAIT -> this.createAnimation("animation.allie.summon_normal_wait", true, event)
                Action.SUMMON_WAIT -> this.createAnimation("animation.allie.summon_wait", true, event)
                Action.ALLIE_PREPARE_FIRST_TIME -> this.createAnimation("animation.allie.deepthroat_prepare", false, event)
                Action.ALLIE_PREPARE_NORMAL -> this.createAnimation("animation.allie.deepthroat_normal_prepare", false, event)
                Action.DEEPTHROAT_START -> this.createAnimation("animation.allie.deepthroat_start", false, event)
                Action.DEEPTHROAT_SLOW -> this.createAnimation("animation.allie.deepthroat_slow", true, event)
                Action.DEEPTHROAT_FAST -> this.createAnimation("animation.allie.deepthroat_fast", true, event)
                Action.DEEPTHROAT_CUM -> this.createAnimation("animation.allie.deepthroat_cum", false, event)
                Action.RICH_FIRST_TIME -> this.createAnimation("animation.allie.rich", false, event)
                Action.RICH_NORMAL -> this.createAnimation("animation.allie.rich_normal", false, event)
                Action.SUMMON_SAND -> this.createAnimation("animation.allie.summon_sand", false, event)
                Action.REVERSE_COWGIRL_START -> this.createAnimation("animation.allie.reverse_cowgirl_start", true, event)
                Action.REVERSE_COWGIRL_SLOW -> this.createAnimation("animation.allie.reverse_cowgirl_slow" + this.stateIndex, true, event)
                Action.REVERSE_COWGIRL_FAST_CONTINUES -> this.createAnimation("animation.allie.reverse_cowgirl_fastc" + this.stateCount, true, event)
                Action.REVERSE_COWGIRL_FAST_START -> this.createAnimation("animation.allie.reverse_cowgirl_fasts", true, event)
                Action.REVERSE_COWGIRL_CUM -> this.createAnimation("animation.allie.reverse_cowgirl_cum", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * summon dialogue, wish and the deepthroat/reverse-cowgirl scenes.
     * `deepthroat_prepareDone` dispatches the stored scene choice
     * (`GIRL_HAND_STATES`); `cowgirlSlowDone`/`cowgirlFastDone`
     * re-roll the loop variant counters; jump on `fastSwitch` toggles
     * the fast-continues variant; `cowgirl_cumDone`/
     * `deepthroat_cumDone` -> `resetCameraAndPhysics()` +
     * inventory upload; `disappear` starts the despawn scale.
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "summonMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.summon1"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ALLIE_SCAWY[0], 0.5f)
                }
                "summonMSG2" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.summon2"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_GIGGLE[this.random.nextInt(4)])
                }
                "summonMSG3" -> this.sendChatMessage(I18n.get("allie.dialogue.summon3"))
                "summonMSG4" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.summon4"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_LIGHTBREATHING[2])
                }
                "summonMSG5" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.summon5"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_HMPH[4])
                }
                "summonMSG6" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.summon6"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_GIGGLE[3])
                }
                "summonMSG7" -> this.sendChatMessage(I18n.get("allie.dialogue.summon7"))
                "summonMSG8" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.summon8"))
                    this.playRandomSound(SoundHandler.GIRLS_ALLIE_HUH)
                    if (this.isControlledByLocalPlayer()) {
                        this.openInteractionMenu(this.level().getPlayerByUUID(this.getInteractionPlayerUUID()!!)!!) // java-npe
                    }
                }
                "summonDone" -> this.setCurrentAction(Action.SUMMON_WAIT)
                "deepthroat_prepareMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.hihi"))
                    this.playRandomSound(SoundHandler.GIRLS_ALLIE_GIGGLE)
                }
                "deepthroat_prepareMSG2" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.boys"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_SIGH[0])
                }
                "scream" -> this.playRandomSound(SoundHandler.MISC_SCREAM)
                "blackscreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "deepthroat_prepareDone" -> if (this.isControlledByLocalPlayer()) {
                    if ("reverse_cowgirl" == this.entityData.get(GIRL_HAND_STATES)) {
                        this.xRot = 30.0f
                        this.setCurrentAction(Action.REVERSE_COWGIRL_START)
                    } else {
                        this.setCurrentAction(Action.DEEPTHROAT_START)
                        PacketHandler.networkWrapper.sendToServer(KoboldStatePacket(this.getGirlId(), this.getInteractionPlayerUUID(), false, true))
                        this.cameraYaw = this.yRot + 180.0f
                        this.positionPlayerRelative(0.0, 0.0, 1.35f.toDouble(), 0.0f, 30.0f)
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "deepthroat_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.DEEPTHROAT_SLOW)
                }
                "deepthroat_startDone" -> this.setCurrentAction(Action.DEEPTHROAT_SLOW)
                "deepthroat_fastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_BJMOAN))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }
                }
                "deepthroat_slowMSG1" -> {
                    if (this.random.nextFloat() > 0.33f) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND))
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_BJMOAN))
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                        HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                    }
                }
                "deepthroat_cumMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_MOAN))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND))
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_CUMINFLATION), 1.5f)
                }
                "cowgirl_cumDone", "deepthroat_cumDone" -> if (this.isControlledByLocalPlayer()) {
                    this.resetCameraAndPhysics()
                    PacketHandler.networkWrapper.sendToServer(UploadInventoryToServerPacket2(this.getGirlId()))
                }
                "summon_normalMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.sup"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_GIGGLE[this.random.nextInt(4)])
                }
                "summon_normalMSG2" -> this.sendChatMessage(I18n.get("allie.dialogue.youhave"))
                "summon_normalMSG3" -> {
                    // java-npe: 1.12 dereferenced the lamp tag unconditionally
                    if (ItemNbt.getTag(this.entityData.get(LAMP_ITEM))!!.getInt("sexmodUses") == 2) {
                        this.sendChatMessage(I18n.get("allie.dialogue.2wishes"))
                    } else {
                        this.sendChatMessage(I18n.get("allie.dialogue.1wish"))
                    }

                    this.playSound(SoundHandler.GIRLS_ALLIE_HMPH[4])
                }
                "summon_normalMSG4" -> this.sendChatMessage("So...")
                "summon_normalMSG5" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.tellme"))
                    this.playRandomSound(SoundHandler.GIRLS_ALLIE_HUH)
                }
                "summon_normalDone" -> {
                    this.setCurrentAction(Action.SUMMON_NORMAL_WAIT)
                    if (this.isControlledByLocalPlayer()) {
                        this.openInteractionMenu(ClientHooks.localPlayer()!!) // java-npe: Minecraft.player
                    }
                }
                "deepthroat_normal_prepareMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.alright"))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_GIGGLE))
                }
                "rich_MSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.wishgranted"))
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_PLOB))
                    if (this.isControlledByLocalPlayer()) {
                        PacketHandler.networkWrapper.sendToServer(MakeRichWishPacket(this.position()))
                    }
                }
                "disappear" -> this.LAMP_SCALE = 0.99f
                "summon_sandMSG1" -> {
                    this.sendChatMessage(I18n.get("allie.dialogue.nooo"))
                    this.playSound(SoundHandler.GIRLS_ALLIE_SCAWY[2])
                }
                "summon_sandMSG2" -> if (this.isLocalPlayerNearby()) {
                    this.broadcastChatAround(I18n.get("allie.dialogue.phobia"), true)
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

                    this.addToHornyMeterIfControlled(0.02f.toDouble())
                }
                "cowgirlSlowDone" -> {
                    val oldState = this.stateIndex

                    do {
                        this.stateIndex = this.random.nextInt(3) + 1
                    } while (this.stateIndex == oldState)
                }
                "fastMoan" -> {
                    this.addToHornyMeterIfControlled(0.04f.toDouble())

                    if (!this.stateFlag) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_MOAN))
                        this.stateFlag = true
                    } else {
                        this.stateFlag = false
                    }
                }
                "fastSwitch" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    val action = this.getCurrentAction()
                    if (action === Action.REVERSE_COWGIRL_FAST_START) {
                        this.setCurrentAction(Action.REVERSE_COWGIRL_FAST_CONTINUES)
                    } else {
                        this.resetAnimationControllerOffset()
                        val oldCount = this.stateCount

                        do {
                            this.stateCount = this.random.nextInt(3) + 1
                        } while (this.stateCount == oldCount)
                    }
                }
                "openSexUi" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "cum" -> this.playRandomSoundAtVolume(SoundHandler.MISC_INSERTS, 6.0f)
                "aftermoan" -> this.playRandomSound(SoundHandler.GIRLS_ALLIE_AFTERSESSIONMOAN)
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    override fun doAction(action: String, uuid: UUID) {
        this.stateFlag2 = true
        if ("action.names.makemerichallie" == action) {
            this.setCurrentAction(if (this.hasLampItem()) Action.RICH_FIRST_TIME else Action.RICH_NORMAL)
        } else {
            this.changeDataParameterFromClient("animationFollowUp", if ("action.names.deepthroat" == action) "deepthroat" else "reverse_cowgirl")
            this.setCurrentAction(if (this.hasLampItem()) Action.ALLIE_PREPARE_FIRST_TIME else Action.ALLIE_PREPARE_NORMAL)
        }
    }

    companion object {
        const val LAMP_RANGE: Int = 300
        const val LAMP_SLOTS: Int = 8

        @JvmField
        val LAMP_OFFSET: Vec3 = Vec3(0.5, 1.0, 0.0)

        @JvmField
        val LAMP_ITEM: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AllieEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = BaseGirlEntity.createAttributes()
    }
}
