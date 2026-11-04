package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.model.AllieModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.client.renderer.KoboldRenderer
import com.trolmastercard.sexmod.entity.api.IKobold
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.MatrixStack
import com.trolmastercard.sexmod.util.PlayerKoboldRenderer
import com.trolmastercard.sexmod.util.RotationHelper
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form kobold (implements [IKobold]) — the
 * transformation with blowjob, anal and mating-press scenes plus full
 * appearance customization (size, eye color, body color) stored in the
 * kobold-specific data keys.
 *
 * <b>State.</b> Inherits [AbstractKoboldPlayerEntity] keys (119-121);
 * adds `SIZE_SCALAR` (122, hoisted `SHARED_FLOAT_122`) = size scalar in `[0, 0.25]` (encoded as
 * part-id `0` value / 100 * 0.25). `setCustomPartList` maps part
 * indices 0/1/2 to size/body-color/eye-color and packs the rest into the DNA
 * string. [DEFAULT_BODY_COLOR] = [EyeAndKoboldColor.PURPLE].
 *
 * <b>Scene flow.</b> Owner commands `anal`/`oral`/`mating`
 * teleport the acting player in, broadcast the intro action and strip.
 * Progression runs in the sound handler: `blowjobStartDone`
 * -> [Action.SUCKBLOWJOB_BLINK], `analStartDone` ->
 * [Action.KOBOLD_ANAL_SLOW], `mating_press_startDone` -> soft,
 * jump switches to fast. Cum ends via `analCumDone`/
 * `blowjobCumDone`/`mating_press_cumDone` ->
 * `resetCameraAndPhysics()`.
 *
 * <b>Pitfalls.</b> `SIZE_SCALAR` scales the model (matrix, camera pivot) and the
 * sound pitch (0.9-1.1) — every consumer must use the same
 * `0.25F - SIZE_SCALAR` formula. [getNextAction] and
 * [getCumAction] must cover the blink variant of the blowjob
 * loop. The animation predicate returns [PlayState.STOP] inside
 * `SexWorldClient` worlds.
 */
class KoboldPlayerEntity : AbstractKoboldPlayerEntity, IKobold {
    @JvmField
    var flySecondFrame: Boolean = false

    @JvmField
    var blowjobRightSide: Boolean = true

    @JvmField
    var switchVariant: Boolean = false

    @JvmField
    var moanCooldown: Int = 0

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out KoboldPlayerEntity>, level: Level) : super(type, level) {
        this.initKoboldDefaults()
    }

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_KOBOLD.get(), level, uuid) {
        this.initKoboldDefaults()
    }

    /** 1.12 entityInit defaults of the hoisted keys. */
    private fun initKoboldDefaults() {
        val color = EyeAndKoboldColor.values()[this.random.nextInt(EyeAndKoboldColor.values().size)]
        this.entityData.set(EYE_COLOR, BlockPos(color.getMainColor()))
        this.entityData.set(SKIN_COLOR_NAME, DEFAULT_BODY_COLOR.name)
        this.entityData.set(SIZE_SCALAR, 0.0f)
    }

    override fun getPlayerCollisionBox(player: Player): AABB = AABB(
        player.x - 0.3f,
        player.y,
        player.z - 0.3f,
        player.x + 0.3f,
        player.y + 0.9f,
        player.z + 0.3f,
    )

    /**
     * Maps the custom-part id list onto the kobold-specific data keys:
     * index 0 = size (`SIZE_SCALAR`), index 1 = body color (`SKIN_COLOR_NAME`), index 2 =
     * eye color (`EYE_COLOR`); the remainder is appended as the DNA string
     * (`MODEL_CODE`). On the CLIENT the render caches are cleared afterwards.
     *
     * Ordering of the three special indices is part of the persisted format —
     * do not reorder.
     */
    override fun setCustomPartList(parts: List<Int>) {
        val builder = StringBuilder()

        for (i in parts.indices) {
            val partId = parts[i]
            when (i) {
                0 -> this.entityData.set(SIZE_SCALAR, partId / 100.0f * 0.25f)
                1 -> this.entityData.set(SKIN_COLOR_NAME, EyeAndKoboldColor.values()[partId].toString())
                2 -> this.entityData.set(EYE_COLOR, BlockPos(EyeAndKoboldColor.values()[partId].getMainColor()))
                else -> AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
            }
        }

        this.entityData.set(MODEL_CODE, builder.toString())
        if (this.level().isClientSide) {
            Client.clearRenderCache()
        }
    }

    override fun getBasePartIdList(): ArrayList<Int> {
        val parts = ArrayList<Int>()
        parts.add(Math.round(this.entityData.get(SIZE_SCALAR) * 100.0f / 0.25f))
        parts.add(EyeAndKoboldColor.indexOf(EyeAndKoboldColor.safeValueOf(this.entityData.get(SKIN_COLOR_NAME))))
        parts.add(EyeAndKoboldColor.indexOf(EyeAndKoboldColor.safeValueOf(this.entityData.get(EYE_COLOR))))
        return parts
    }

    override fun buildModelCodeDNA(builder: StringBuilder): String {
        AbstractNpcOnlyEntity.appendPaddedLetter(builder, 8)
        AbstractNpcOnlyEntity.appendPaddedLetter(builder, 3)
        AbstractNpcOnlyEntity.appendRandomGene(builder)
        AbstractNpcOnlyEntity.appendRandomGene(builder)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 1)
        return builder.toString()
    }

    override fun getCustomPartIdList(): ArrayList<Int> = arrayListOf(
        101,
        EyeAndKoboldColor.values().size,
        EyeAndKoboldColor.values().size,
        8,
        3,
        101,
        101,
        3,
        3,
        4,
        2,
    )

    /** CLIENT (called from the client-side data watch). */
    override fun clearBoneColors() {
        Client.clearBoneColors()
    }

    override fun getScaleFactor(): Float {
        val shrink = 0.25f - this.entityData.get(SIZE_SCALAR)
        return 1.4f - shrink
    }

    /**
     * SERVER: owner commands — `anal` starts [Action.KOBOLD_ANAL_START],
     * `oral` starts [Action.STARTBLOWJOB], `mating` starts
     * [Action.MATING_PRESS_START]. All broadcast the action, strip and
     * teleport the acting player into the scene.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("anal" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.KOBOLD_ANAL_START)
            this.sendActionPacket(this.getOutfitIndex(), Action.KOBOLD_ANAL_START)
            this.setOutfitIndex(0)
        }

        if ("oral" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.STARTBLOWJOB)
            this.sendActionPacket(this.getOutfitIndex(), Action.STARTBLOWJOB)
            this.setOutfitIndex(0)
        }

        if ("mating" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.MATING_PRESS_START)
            this.sendActionPacket(this.getOutfitIndex(), Action.MATING_PRESS_START)
            this.setOutfitIndex(0)
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("anal", "oral", "mating"), null, false)
        return true
    }

    override fun isBlockedByCeiling(): Boolean {
        // 1.12 !Block.isPassable(world, pos) == material.blocksMovement()
        return this.level().getBlockState(this.blockPosition().offset(0, 1, 0)).blocksMotion()
    }

    /**
     * Scales the bone matrix by the kobold size (1 minus the size scalar) so
     * the rendered model matches the `SIZE_SCALAR` slider. CLIENT-only rendering
     * helper.
     */
    override fun applyAdditionalMatrixTransformations(stack: MatrixStack): MatrixStack {
        val shrink = 0.25f - this.entityData.get(SIZE_SCALAR)
        stack.scale(1.0f - shrink, 1.0f - shrink, 1.0f - shrink)
        return stack
    }

    /**
     * Scales the camera-bone pivot by the kobold size so the first-person
     * camera sits at the right height. CLIENT-only.
     */
    override fun transformCameraPivotY(y: Float): Float {
        val grow = 1.0f - (0.25f - this.entityData.get(SIZE_SCALAR))
        return y * grow
    }

    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/kobold/hand.png"

    override fun getHandColor(index: Int): Vec3i {
        try {
            return EyeAndKoboldColor.valueOf(this.entityData.get(SKIN_COLOR_NAME)).getMainColor()
        } catch (ex: Exception) {
            Main.LOGGER.error("could not read the kobold hand color", ex)
            return super.getHandColor(index)
        }
    }

    override fun getNextAction(action: Action): Action? {
        return if (action == Action.SUCKBLOWJOB_BLINK) {
            Action.THRUSTBLOWJOB
        } else {
            if (action == Action.KOBOLD_ANAL_SLOW) Action.KOBOLD_ANAL_FAST else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        return if (action == Action.THRUSTBLOWJOB || action == Action.SUCKBLOWJOB_BLINK) {
            Action.CUMBLOWJOB
        } else if (action == Action.KOBOLD_ANAL_SLOW || action == Action.KOBOLD_ANAL_FAST) {
            Action.KOBOLD_ANAL_CUM
        } else {
            if (action != Action.MATING_PRESS_HARD && action != Action.MATING_PRESS_SOFT) null else Action.MATING_PRESS_CUM
        }
    }

    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction != Action.MATING_PRESS_CUM || action != Action.MATING_PRESS_SOFT && action != Action.MATING_PRESS_HARD) {
            if (currentAction != Action.KOBOLD_ANAL_CUM || action != Action.KOBOLD_ANAL_SLOW && action != Action.KOBOLD_ANAL_FAST) {
                if (currentAction != Action.CUMBLOWJOB || action != Action.SUCKBLOWJOB && action != Action.THRUSTBLOWJOB) {
                    super.setCurrentAction(action)
                }
            }
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState = KoboldPlayerAnimations.predicate(this, event)

    override fun playSound(sound: SoundEvent) {
        this.playSoundAtVolume(sound, 1.0f)
    }

    fun playRandomSounds(sounds: Array<SoundEvent>) {
        this.playRandomSound(sounds, 1.0f)
    }

    fun playRandomSound(sounds: Array<SoundEvent>, volume: Float) {
        this.playSoundAtVolume(sounds[this.random.nextInt(sounds.size)], volume)
    }

    /**
     * Plays a sound pitched by kobold size: smaller kobolds get a slightly
     * higher pitch (0.9-1.1 range from [RotationHelper.lerpDouble]).
     */
    override fun playSoundAtVolume(sound: SoundEvent, volume: Float) {
        val shrink = 0.25f - this.entityData.get(SIZE_SCALAR)
        val progress = (shrink / 0.25f).toDouble()
        val pitch = RotationHelper.lerpDouble(0.9f.toDouble(), 1.1f.toDouble(), progress).toFloat()
        this.playSoundAtPosition(sound, volume, pitch)
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        KoboldPlayerAnimations.registerControllers(this, controllers)
    }

    private object Client {
        fun handModel(): IVanillaModel = AllieModel()

        fun clearRenderCache() {
            PlayerKoboldRenderer.clearRenderCache()
        }

        fun clearBoneColors() {
            PlayerKoboldRenderer.clearRenderCache()
            KoboldRenderer.clearBoneColors()
        }
    }

    companion object {
        @JvmField
        val DEFAULT_BODY_COLOR: EyeAndKoboldColor = EyeAndKoboldColor.PURPLE

        /** Alias of [BaseGirlEntity.SHARED_FLOAT_122] (1.12 id 122). */
        @JvmField
        val SIZE_SCALAR: EntityDataAccessor<Float> = BaseGirlEntity.SHARED_FLOAT_122

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractKoboldPlayerEntity.createAttributes()
    }
}
