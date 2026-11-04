package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.model.api.IGirlModelInfo
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.TrigMath
import java.util.Arrays
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.constant.DataTickets

/**
 * Base geckolib model for the girl entities. Handles: outfit-index texture
 * selection, per-frame animation events (head look, anchored position pinning,
 * action-transition timing), the steve/Alex arm-skin variants, outfit armor
 * bone visibility, and the static camera-bone placement list.
 *
 * **Camera placements.** [CAMERA_PLACEMENTS] (`boyCam`,
 * `girlCam`) names the bones whose world positions the renderer
 * publishes each frame — the scene camera attaches to these; keep the list in
 * sync with the geo models.
 *
 * **Anchored pinning.** [setCustomAnimations] repositions an
 * anchored girl directly to her target position (client-side render smoothing)
 * and drives the animation controller's transition length from the current
 * `Action`. Skipped in the [SexWorldClient] preload world.
 *
 * **Outfit bones.** [applyHeldItems] toggles armor vs. nude bone
 * groups from the girl's armor slots ([IGirlModelInfo] bone lists);
 * [getItemStackForBone] maps armor bones back to their slots (used for
 * armor tinting).
 *
 * CLIENT-side only.
 *
 * Port notes: `getModelLocation` → [getModelResource], `getTextureLocation(e)` → `getTextureResource(e)`
 * (subclasses may declare the parameter nullable), `setLivingAnimations` → [setCustomAnimations],
 * `setMolangQueries` → [applyMolangQueries].
 */
abstract class GirlModel<T : BaseGirlEntity> protected constructor() : GirlModelBase<T>(), IGirlModelInfo {
    @JvmField
    var modelLocations: Array<ResourceLocation> = this.getModelLocations()

    @JvmField
    var mc: Minecraft = Minecraft.getInstance()

    abstract fun getModelLocations(): Array<ResourceLocation>

    @Suppress("UNCHECKED_CAST")
    fun getTextureLocation(): ResourceLocation = this.getTextureResource(null as T?)

    override fun getModelResource(entity: T): ResourceLocation = this.getSexWorldTexture(entity)

    open fun getTextureForGirl(entity: T?): ResourceLocation = this.getTextureResource(entity)

    /**
     * Model location by outfit index: the preload world always uses outfit 0;
     * an out-of-range index falls back to outfit 0 (nude) with a console
     * notice.
     */
    open fun getSexWorldTexture(entity: BaseGirlEntity): ResourceLocation {
        if (SexWorldClient.isPreloadWorld(entity.level())) {
            return this.modelLocations[0]
        } else if (entity.entityData.get(BaseGirlEntity.OUTFIT_INDEX) >= this.modelLocations.size) {
            // original jar had '>' here — OOB at index == length; '>=' guards the last index
            Main.LOGGER.info("Girl doesn't have an outfit Nr." + entity.entityData.get(BaseGirlEntity.OUTFIT_INDEX) + " so im just making her nude lol")
            return this.modelLocations[0]
        } else {
            return this.modelLocations[entity.entityData.get(BaseGirlEntity.OUTFIT_INDEX)]
        }
    }

    open fun getDefaultTexture(entity: BaseGirlEntity?): ResourceLocation = this.getTextureLocation()

    override fun applyMolangQueries(animationState: AnimationState<T>, animTime: Double) {
        if (Minecraft.getInstance().level != null) {
            super.applyMolangQueries(animationState, animTime)
        }
    }

    /**
     * Per-frame animation entry (CLIENT-side): runs after the standard geckolib
     * molang/animation pass, then the arm-skin variant + outfit bone handling,
     * pins anchored girls to their target position, syncs the controller's
     * transition length to the current action, and dispatches the
     * [handleAnimationEvent] head-look. Skipped entirely in the
     * [SexWorldClient] preload world.
     */
    override fun setCustomAnimations(entity: T, instanceId: Long, animationState: AnimationState<T>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        val processor = this.animationProcessor
        this.animateGirl(entity, processor)
        if (!SexWorldClient.isPreloadWorld(entity.level())) {
            if (entity.entityData.get(BaseGirlEntity.IS_ANCHORED)) {
                val target = entity.getTargetPosition()
                entity.lerpTo(target.x, target.y, target.z, entity.getYawRotation(), 0.0f, 3)
            }

            val actionController = entity.actionController
            if (actionController != null) {
                actionController.setTransitionLengthTicks(
                    if (!SexWorldClient.isPreloadWorld(entity.level())) entity.getCurrentAction().transitionTick.toDouble() else 5.0,
                )
            }

            this.handleAnimationEvent(entity, processor, animationState)
            if (entity is AbstractGirlNpcEntity && !entity.isLocallyRegistered() && entity.getOutfitIndex() != 0) {
                this.applyHeldItems(
                    processor,
                    entity.entityDataManager.get(AbstractGirlNpcEntity.HELMET_SLOT),
                    entity.entityDataManager.get(AbstractGirlNpcEntity.CHEST_SLOT),
                    entity.entityDataManager.get(AbstractGirlNpcEntity.LEGS_SLOT),
                    entity.entityDataManager.get(AbstractGirlNpcEntity.BOOTS_SLOT),
                )
            } else {
                this.renderAllBones(processor)
            }
        }
    }

    /**
     * Applies the girl's armor visibility from her equipped armor slots:
     * each armor slot toggles the armor bone group on and the nude variant off.
     */
    open fun applyHeldItems(processor: AnimationProcessor<T>, helmet: ItemStack, chest: ItemStack, legs: ItemStack, boots: ItemStack) {
        this.renderHeadArmor(processor, !helmet.isEmpty)
        this.renderTopArmor(processor, chest.item is ArmorItem)
        this.renderBottomArmor(processor, !legs.isEmpty)
        this.renderShoesArmor(processor, !boots.isEmpty)
    }

    open fun renderAllBones(processor: AnimationProcessor<T>) {
        this.renderHeadArmor(processor, false)
        this.renderTopArmor(processor, false)
        this.renderBottomArmor(processor, false)
        this.renderShoesArmor(processor, false)
    }

    open fun renderHeadArmor(processor: AnimationProcessor<T>, armorVisible: Boolean) {
        this.renderBoneGroup(this.HeadArmor(), armorVisible, processor)
        this.renderBoneGroup(this.Attachments(), !armorVisible, processor)
    }

    open fun renderTopArmor(processor: AnimationProcessor<T>, armorVisible: Boolean) {
        this.renderBoneGroup(this.TopArmor(), armorVisible, processor)
        this.renderBoneGroup(this.Top(), !armorVisible, processor)
    }

    open fun renderBottomArmor(processor: AnimationProcessor<T>, armorVisible: Boolean) {
        this.renderBoneGroup(this.BottomArmor(), armorVisible, processor)
        this.renderBoneGroup(this.Bottom(), !armorVisible, processor)
    }

    open fun renderShoesArmor(processor: AnimationProcessor<T>, armorVisible: Boolean) {
        this.renderBoneGroup(this.ShoesArmor(), armorVisible, processor)
        this.renderBoneGroup(this.Shoes(), !armorVisible, processor)
    }

    open fun renderBoneGroup(boneNames: Array<String>, visible: Boolean, processor: AnimationProcessor<T>) {
        for (boneName in boneNames) {
            this.renderSingleBone(boneName, visible, processor)
        }
    }

    open fun renderSingleBone(boneName: String, visible: Boolean, processor: AnimationProcessor<T>) {
        processor.getBone(boneName)?.setHidden(!visible)
    }

    /**
     * Whether the girl's skin variant is the vanilla default (Steve) — the
     * Alex-variant arms are hidden otherwise.
     */
    open fun canRender(girl: T): Boolean {
        val uuid = girl.getInteractionPlayerUUID() ?: return true

        val world = girl.level()
        val player = world.getPlayerByUUID(uuid) as? AbstractClientPlayer ?: return true
        // 1.12 getSkinType() == "default" is the wide (Steve) arm model
        return player.skin.model() == PlayerSkin.Model.WIDE
    }

    /**
     * Arm-skin variant bones: shows the Steve (or Alex) arm set depending on
     * the interaction player's skin type, and hides the whole `steve`
     * body bone while the current action has no player.
     */
    open fun animateGirl(girl: T, processor: AnimationProcessor<T>) {
        val isDefaultSkin = this.canRender(girl)
        if (isDefaultSkin) {
            processor.getBone("rightArmAlex")?.setHidden(true)
            processor.getBone("rightLowerArmAlex")?.setHidden(true)
            processor.getBone("rightArmSteve")?.setHidden(false)
            processor.getBone("rightLowerArmSteve")?.setHidden(false)
            processor.getBone("leftArmAlex")?.setHidden(true)
            processor.getBone("leftLowerArmAlex")?.setHidden(true)
            processor.getBone("leftArmSteve")?.setHidden(false)
            processor.getBone("leftLowerArmSteve")?.setHidden(false)
            processor.getBone("steve")?.setHidden(!girl.getCurrentAction().hasPlayer)
        } else {
            processor.getBone("rightArmAlex")?.setHidden(false)
            processor.getBone("rightLowerArmAlex")?.setHidden(false)
            processor.getBone("rightArmSteve")?.setHidden(true)
            processor.getBone("rightLowerArmSteve")?.setHidden(true)
            processor.getBone("leftArmAlex")?.setHidden(false)
            processor.getBone("leftLowerArmAlex")?.setHidden(false)
            processor.getBone("leftArmSteve")?.setHidden(true)
            processor.getBone("leftLowerArmSteve")?.setHidden(true)
            processor.getBone("steve")?.setHidden(!girl.getCurrentAction().hasPlayer)
        }
    }

    open fun shouldRender(girl: T): Boolean = true

    /**
     * Head-look bones for the idle/attack/bow actions: neck takes half the
     * head yaw, head takes full yaw + pitch, body straightens. Other actions
     * (scenes) drive the head via the animation controller instead.
     */
    open fun handleAnimationEvent(girl: T, processor: AnimationProcessor<T>, event: AnimationState<T>) {
        if (!SexWorldClient.isPreloadWorld(girl.level())) {
            if (this.shouldRender(girl)) {
                if (girl.getCurrentAction() == Action.NULL || girl.getCurrentAction() == Action.ATTACK || girl.getCurrentAction() == Action.BOW) {
                    val modelData = event.getData(DataTickets.ENTITY_MODEL_DATA) ?: return
                    val neckBone = processor.getBone("neck")
                    neckBone?.setRotY(modelData.netHeadYaw() * 0.5f * (Math.PI / 180.0).toFloat())
                    val headBone = processor.getBone("head")
                    if (headBone != null) {
                        headBone.setRotY(modelData.netHeadYaw() * (Math.PI / 180.0).toFloat())
                        headBone.setRotX(modelData.headPitch() * (Math.PI / 180.0).toFloat())
                    }
                    var bodyBone = processor.getBone("body")
                    if (bodyBone == null) bodyBone = processor.getBone("dd")
                    bodyBone?.setRotY(0.0f)
                }
            }
        }
    }

    /**
     * Maps an armor bone name back to the girl's armor slot (helmet/chest/
     * legs/boots) — used by the renderer's armor tinting.
     */
    open fun getItemStackForBone(girl: BaseGirlEntity, boneName: String): ItemStack {
        return if (Arrays.asList(*this.HeadArmor()).contains(boneName)) {
            girl.entityDataManager.get(AbstractGirlNpcEntity.HELMET_SLOT)
        } else if (Arrays.asList(*this.TopArmor()).contains(boneName)) {
            girl.entityDataManager.get(AbstractGirlNpcEntity.CHEST_SLOT)
        } else if (Arrays.asList(*this.BottomArmor()).contains(boneName)) {
            girl.entityDataManager.get(AbstractGirlNpcEntity.LEGS_SLOT)
        } else {
            if (Arrays.asList(*this.ShoesArmor()).contains(boneName)) girl.entityDataManager.get(AbstractGirlNpcEntity.BOOTS_SLOT) else ItemStack.EMPTY
        }
    }

    companion object {
        @JvmField
        val BRA_STRING_BONES: List<String> = Arrays.asList(
            "braStringMidStartR",
            "braStringMidMid1R",
            "braStringMidMid2R",
            "braStringMidMid3R",
            "braStringMidEndR",
            "braStringBackR",
            "braStringRightEndR",
            "braStringRightStartR",
            "braStringRightL",
            "braStringMidMid1L",
            "braStringMidMid2L",
            "braStringMidMid3L",
            "braStringMidEndL",
            "braStringBackL",
            "braStringLeftEndL",
            "braStringLeftStartL",
            "braStringMidStartL",
            "braStringRightR",
        )

        @JvmField
        val CAMERA_PLACEMENTS: List<String> = Arrays.asList("boyCam", "girlCam")

        @JvmField
        var enableModelCache: Boolean = true

        @JvmStatic
        fun getInterpolatedPosition(girl: BaseGirlEntity): Vec3 {
            return lerpPositions(Vec3(girl.xOld, girl.yOld, girl.zOld), girl.position())
        }

        @JvmStatic
        fun getBoneOffsetWorld(girl: BaseGirlEntity, offset: Vec3): Vec3 {
            return lerpPositions(offset, girl.position())
        }

        /**
         * Direction-dependent pose math used to aim camera/bone offsets: maps the
         * vector between two positions onto a yaw (-180..0), a head-roll and a
         * bob/offset value, with NaN guards. Used by
         * [getInterpolatedPosition]/[getBoneOffsetWorld].
         */
        @JvmStatic
        fun lerpPositions(from: Vec3, to: Vec3): Vec3 {
            val delta = to.subtract(from)
            val absDelta = Vec3(Math.abs(delta.x), Math.abs(delta.y), Math.abs(delta.z))
            val xRatio = absDelta.x / (absDelta.x + absDelta.y + absDelta.z)
            val yRatio = absDelta.y / (absDelta.x + absDelta.y + absDelta.z)
            val zRatio = absDelta.z / (absDelta.x + absDelta.y + absDelta.z)
            val directionVec = Vec3(
                (if (delta.x > 0.0) 1 else -1) * xRatio,
                (if (delta.y > 0.0) 1 else -1) * yRatio,
                (if (delta.z > 0.0) 1 else -1) * zRatio,
            )
            val verticalRatio = directionVec.y / 2.0 + 0.5
            var yaw = RotationHelper.lerpDouble(-180.0, 0.0, verticalRatio).toFloat()
            if (java.lang.Float.isNaN(yaw)) {
                yaw = -90.0f
            }

            var roll = if (verticalRatio < 0.5) 0.0f else RotationHelper.lerpDouble(0.0, 16.0, -verticalRatio).toFloat()
            if (java.lang.Float.isNaN(roll)) {
                roll = 0.0f
            }

            var bob = (4.0 - Math.sin((Math.PI / 2) + verticalRatio * 2.0 * Math.PI) * 4.0).toFloat()
            if (java.lang.Float.isNaN(bob)) {
                bob = 8.0f
            }

            return Vec3(TrigMath.wrapDegrees(yaw).toDouble(), roll.toDouble(), bob.toDouble())
        }
    }
}
