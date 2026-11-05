package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.util.Color

/**
 * Renderer for the horny-potion player-girls ([AbstractPlayerGirlEntity]):
 * a [GirlRenderer] whose model mirrors the transformed player — arms,
 * held items, shield blocking and bow pulling follow the owner player's state,
 * and the first-person camera rendering is supported through the
 * `isFirstPerson` flag.
 *
 * **Owner sync.** [doRenderEntity] copies the owner's held items,
 * using-item/rendering flags and armor (`syncArmor`) into the renderer
 * before delegating; the item bones draw the owner's actual stacks
 * ([renderEquippedItem] with bow-pull progress and shield-block poses).
 *
 * **First person.** `isFirstPerson` marks renders issued from the
 * first-person camera path (girlCam bone camera in
 * `GirlCameraHelper`); [isGirlVisible] consumes the flag exactly
 * once, and [shouldRenderFirstPersonHead] hides the head while the
 * local player's own girl is rendered in first person (except in
 * inventory/creative screens).
 *
 * CLIENT-side render thread only. No shadow is drawn for player-girls
 * (shadow radius 0; PORT-GAP: the vanilla dispatcher still draws the fire overlay,
 * which 1.12's empty `doRenderShadowAndFire` suppressed).
 *
 * Pose hooks ([applyBowRotation], [applyShieldBlockingTransform], [applyItemPostRotation],
 * [preRenderCallback]) act on `renderCtx.poseStack`.
 */
open class GirlPlayerRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>) :
    GirlRenderer<BaseGirlEntity>(ctx, model, 0.0) {
    @JvmField var mainhandItem: ItemStack = ItemStack.EMPTY
    @JvmField var offhandItem: ItemStack = ItemStack.EMPTY
    @JvmField var isRendering: Boolean = false
    @JvmField var isUsingItem: Boolean = false
    lateinit var playerGirl: AbstractPlayerGirlEntity
    @JvmField var partialTicks: Float = 0.0f

    private var overlayLayer: GirlLayerRenderer<BaseGirlEntity>? = null

    /** Whether [playerGirl] has been set (Java `playerGirl != null`). */
    fun hasPlayerGirl(): Boolean = this::playerGirl.isInitialized

    init {
        this.shadowRadius = 0.0f
    }

    /**
     * Whether the girl should be drawn: locally registered previews always
     * render; otherwise the `isFirstPerson` flag is consumed (reset to
     * `false`) and returned — the first-person camera path is the only
     * one that may draw the local player's own girl.
     */
    open fun isGirlVisible(girl: BaseGirlEntity): Boolean {
        if (girl.isLocallyRegistered()) {
            return true
        }

        val visible = isFirstPerson
        isFirstPerson = false
        return visible
    }

    /**
     * Player-girl render entry: gated by [isGirlVisible]; syncs the
     * owner's items/flags/armor, draws the name label for other players, then
     * delegates to the normal girl pipeline.
     */
    override fun doRenderEntity(
        entity: BaseGirlEntity,
        x: Double,
        y: Double,
        z: Double,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        if (this.isGirlVisible(entity)) {
            val playerGirl = entity as AbstractPlayerGirlEntity
            val ownerUuid = playerGirl.getOwnerUserUUID()
            if (ownerUuid != null) {
                val owner = Minecraft.getInstance().player?.level()?.getPlayerByUUID(ownerUuid)
                if (owner != null) {
                    this.mainhandItem = owner.mainHandItem
                    this.offhandItem = owner.offhandItem
                    this.isUsingItem = playerGirl.playerUsingItem
                    this.isRendering = playerGirl.playerSneaking
                    this.playerGirl = playerGirl
                    this.partialTicks = partialTick
                    playerGirl.syncArmor(owner)
                    if (this.isOwnPlayer(owner, entity)) {
                        this.renderEntity = entity
                        this.renderCtx.poseStack = poseStack
                        this.renderCtx.bufferSource = bufferSource
                        this.renderCtx.packedLight = packedLight
                        this.renderCtx.partialTick = partialTick
                        this.vanillaBase = this.vanillaRenderBase(entity, entityYaw, partialTick)
                        this.renderLivingLabel(entity, owner.gameProfile.name, x, y + playerGirl.getScaleFactor(), z, 300)
                    }

                    super.doRenderEntity(entity, x, y, z, entityYaw, partialTick, poseStack, bufferSource, packedLight)
                }
            }
        }
    }

    /**
     * Render entity used for position interpolation: the owner player when
     * present (so the girl's pose tracks the owner's movement), else the girl
     * itself.
     */
    override fun getRenderEntity(girl: BaseGirlEntity): Entity {
        if (girl !is AbstractPlayerGirlEntity) {
            return girl
        }

        val owner = girl.getOwnerPlayer()
        return owner ?: girl
    }

    /**
     * Name-label visibility: never for the local player; for others, hidden
     * while the current action hides name tags.
     */
    open fun isOwnPlayer(player: Player, girl: BaseGirlEntity): Boolean {
        if (player.uuid == Minecraft.getInstance().player?.uuid) {
            return false
        }

        val action = girl.getCurrentAction()
        return !action.hideNameTag
    }

    open fun onBoneRenderStart(boneName: String, bone: GeoBone) {
    }

    open fun onBoneRenderingLayer(boneName: String, bone: GeoBone, playerGirl: AbstractPlayerGirlEntity) {
    }

    /**
     * Bone recursion for player-girls: adds scene/using-item pose overrides
     * (upperBody/head tilt while `isRendering`, bow-draw arm pitch, shield
     * blocking arm poses), renders the head overlay (elytra layer), the held
     * main/offhand items on their bones, and the armor-tinted cube pass (see
     * [GirlRenderer.renderRecursively] for the shared rules).
     */
    override fun renderRecursively(bone: GeoBone, r: Float, g: Float, b: Float, a: Float) {
        var red = r
        var green = g
        var blue = b
        val boneName = bone.name
        if (this.isRendering) {
            if (boneName == "upperBody") {
                bone.setRotX(bone.rotX - 0.5f)
            }

            if (boneName == "head") {
                bone.setRotX(bone.rotX + 0.5f)
            }
        }

        if (boneName == "head") {
            this.renderOverlay(bone, Color.ofRGB(red, green, blue))
        }

        this.onBoneRenderStart(boneName, bone)
        this.onBoneRenderingLayer(boneName, bone, this.playerGirl)
        if (this.isUsingItem && (this.mainhandItem.item is BowItem || this.offhandItem.item is BowItem)) {
            if (boneName == "armR") {
                bone.setRotX(bone.rotX - this.playerGirl.xRot / 50.0f)
            }

            if (boneName == "armL") {
                bone.setRotY(bone.rotY - this.playerGirl.xRot / 50.0f)
            }

            if (this.offhandItem.item is BowItem) {
                val tempStack = this.offhandItem
                this.offhandItem = this.mainhandItem
                this.mainhandItem = tempStack
            }
        }

        if (this.isUsingItem && this.mainhandItem.item is ShieldItem) {
            if (boneName == "armR") {
                bone.setRotZ(0.0f)
                bone.setRotX(0.5f)
            } else if (this.offhandItem.item is ShieldItem && boneName == "armL") {
                bone.setRotZ(0.0f)
                bone.setRotX(0.5f)
            }
        }

        if (boneName == "weapon" && !this.mainhandItem.isEmpty) {
            this.renderEquippedItem(bone, false)
        }

        if (boneName == "offhand" && !this.offhandItem.isEmpty) {
            this.renderEquippedItem(bone, true)
        }

        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, bone)
            if ("Head2" == boneName && !this.shouldRenderHead2()) {
                return
            } else if (("neck" == boneName || "head" == boneName) && !this.shouldRenderFirstPersonHead()) {
                return
            }

            if (!bone.isHidden) {
                val armorColor = this.calculateBoneArmorColor(boneName, red, green, blue)
                red = armorColor.x
                green = armorColor.y
                blue = armorColor.z
                val armorAlpha = armorColor.w.toDouble()
                if (!this.activeCustomPartBones.contains(boneName)) {
                    for (cube in bone.cubes) {
                        poseStack.pushPose()
                        try {
                            this.currentRenderingBone = bone
                            this.renderCubeGeometry(cube, red, green, blue, a, armorAlpha)
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }

                for (childBone in bone.childBones) {
                    if (armorAlpha == 0.0) {
                        this.renderRecursively(childBone, red, green, blue, a)
                    } else {
                        this.renderCustomBones(childBone, red, green, blue, a, armorAlpha)
                    }
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Whether the head bone may be rendered: always for girls without an owner
     * UUID or in third person; in first person only while an inventory screen
     * is open (the vanilla head would otherwise occlude the first-person
     * camera).
     */
    open fun shouldRenderFirstPersonHead(): Boolean {
        if (!this.playerGirl.hasOwnerUUID()) {
            return true
        } else {
            val screen = Minecraft.getInstance().screen
            return if (ClientHooks.thirdPersonView() != 0) true else screen is InventoryScreen || screen is CreativeModeInventoryScreen
        }
    }

    /**
     * Renders the [GirlLayerRenderer] (elytra) at the head bone with the
     * bone transform applied; restores the entity texture afterwards. This is
     * what draws armor/elytra layers on player-girls.
     */
    open fun renderOverlay(bone: GeoBone, color: Color) {
        val bufferSource = this.renderCtx.bufferSource ?: return
        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            MatrixHelper.applyBoneTransform(poseStack, bone)
            this.preRenderCallback()
            var layer = this.overlayLayer
            if (layer == null) {
                layer = GirlLayerRenderer(this)
                this.overlayLayer = layer
            }
            layer.render(
                this.playerGirl,
                this.playerGirl.walkAnimation.position(),
                this.playerGirl.walkAnimation.speed(),
                this.partialTicks,
                0.0f,
                0.0f,
                0.0f,
                color,
                poseStack,
                bufferSource,
                this.renderCtx.packedLight,
            )
            this.renderCtx.switchTexture(this.getTextureLocation(this.playerGirl))
        } finally {
            poseStack.popPose()
        }
    }

    open fun preRenderCallback() {
    }

    /**
     * Renders the owner's held item on the weapon/offhand bone: applies the
     * bow-pull or shield-block pose, tracks bow draw progress onto the girl
     * (item-use count / active hand / held-item override), then renders the
     * third-person item and restores the texture.
     */
    open fun renderEquippedItem(bone: GeoBone, isOffhand: Boolean) {
        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            MatrixHelper.applyBoneTransform(poseStack, bone)
            val stack = if (isOffhand) this.offhandItem else this.mainhandItem
            when (stack.useAnimation) {
                UseAnim.BOW -> this.applyBowRotation(isOffhand)
                UseAnim.BLOCK -> this.applyShieldBlockingTransform(isOffhand, this.isUsingItem)
                else -> {
                }
            }

            if (this.isUsingItem && !isOffhand && stack.item is BowItem) {
                this.bowPullProgress += 0.015f
                this.playerGirl.setItemUseCount(Math.round(-this.bowPullProgress * 20.0f + stack.getUseDuration(this.playerGirl)))
                this.playerGirl.setHeldItemOverride(stack)
                this.playerGirl.startUsingItem(InteractionHand.MAIN_HAND)
                this.playerGirl.setHandActiveState()
            } else {
                this.bowPullProgress = 0.0f
                this.playerGirl.setItemUseCount(0)
                this.playerGirl.setHeldItemOverride(ItemStack.EMPTY)
                this.playerGirl.setHandActiveState()
            }

            this.applyItemPostRotation(isOffhand, stack)
            poseStack.scale(0.75f, 0.75f, 0.75f)
            this.renderVanillaItem(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Base item rotation: 90 degrees for main hand, 200 for offhand.
     */
    open fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        this.renderCtx.poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 200.0f else 90.0f))
    }

    open fun applyBowRotation(isOffhand: Boolean) {
        this.renderCtx.poseStack.mulPose(Axis.XP.rotationDegrees(20.0f))
    }

    /**
     * Shield-blocking pose: mirrors the shield across the body, and while
     * actively blocking (isUsingItem) raises it in front of the face (with
     * per-hand offsets).
     */
    open fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        val poseStack = this.renderCtx.poseStack
        if (isOffhand) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
            if (isBlocking) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(35.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(-20.0f))
                poseStack.translate(0.0f, 0.0f, 0.228f)
            }
        } else if (isBlocking) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
            poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0f))
            poseStack.translate(0.0f, 0.165f, 0.0f)
        }
    }

    companion object {
        @JvmField
        var isFirstPerson: Boolean = false
    }
}
