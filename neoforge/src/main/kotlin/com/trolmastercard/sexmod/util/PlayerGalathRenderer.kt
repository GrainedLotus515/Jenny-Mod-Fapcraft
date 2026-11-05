package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.renderer.GalathRenderer
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BodyParts
import com.trolmastercard.sexmod.entity.api.IGalath
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.DyedItemColor
import org.joml.Vector4f
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * <b>Role.</b> Renderer for the player-controlled Galath (owner's own galath
 * when transformed / summoned). Extends [GirlPlayerRenderer] with galath
 * specifics: a bone blacklist so armor/clothing overlays don't double-draw,
 * per-armor overlay colors (gold/iron/leather dye), the dash point-of-view
 * overlay, and a two-pass body/steve-bone render.
 *
 * <b>Pitfalls.</b> [getBlacklistedBones] mutates the shared
 * `GalathRenderer.BLACKLISTED_BONES` set — repeated calls must stay
 * idempotent. [doRenderEntity] skips the dash POV only for the owner in
 * first person; anchored girls always render it.
 *
 * Port notes (CLIENT-ONLY): the dash POV gets the pose stack moved to the 1.12
 * origin and the buffer source; armor materials compare holders
 * (`ArmorMaterials.X`), leather colour comes from `DyedItemColor`.
 */
open class PlayerGalathRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    override fun getAdditionalOverlayColor(entity: BaseGirlEntity): Vector3fSexmodSpecial? {
        return if (SexWorldClient.isPreloadWorld(entity.level())) {
            null
        } else {
            if ((entity as IGalath).isWingsAnimated()) null else GalathRenderer.ZERO_OFFSET
        }
    }

    override fun getBlacklistedBones(): HashSet<String> {
        GalathRenderer.BLACKLISTED_BONES.addAll(BodyParts.CUSTOM_PART_BONES)
        return GalathRenderer.BLACKLISTED_BONES
    }

    override fun drawOverlayLines(girl: BaseGirlEntity, color: Vector3fSexmodSpecial, lineWidth: Float) {
        renderGirlTint(girl, color, lineWidth)
    }

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
        super.doRenderEntity(entity, x, y, z, entityYaw, partialTick, poseStack, bufferSource, packedLight)
        val localUuid = Minecraft.getInstance().player?.uuid
        if (ClientHooks.thirdPersonView() != 0 || localUuid != (entity as AbstractPlayerGirlEntity).getOwnerUserUUID() || entity.isAnchored()) {
            poseStack.pushPose()
            try {
                translateToPlayerOrigin(poseStack, entity, partialTick)
                GalathRenderer.renderDashPov(entity, partialTick, poseStack, bufferSource)
            } finally {
                poseStack.popPose()
            }
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        super.applyBowRotation(isOffhand)
        if (isOffhand) {
            this.renderCtx.poseStack.translate(0.15, 0.0, 0.0)
        }
    }

    /** (Java names the parameters `isBlocking, isMainHand`; positions match the base hook.) */
    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        val blocking = isOffhand
        val mainHand = isBlocking
        if (blocking) {
            poseStack.translate(0.0, -0.05, -0.05)
            poseStack.mulPose(Axis.XP.rotationDegrees(15.0f))
            if (mainHand) {
                poseStack.translate(0.3, 0.2, 0.0)
                poseStack.mulPose(Axis.XP.rotationDegrees(-30.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(15.0f))
            }
        } else {
            poseStack.translate(0.0, 0.0, 0.1)
            poseStack.mulPose(Axis.XP.rotationDegrees(30.0f))
            if (mainHand) {
                poseStack.mulPose(Axis.XP.rotationDegrees(-29.0f))
            }
        }
    }

    override fun calculateBoneArmorColor(boneName: String, r: Float, g: Float, b: Float): Vector4f {
        var red = r
        var green = g
        var blue = b
        if (!blacklistBones.contains(boneName)) {
            return this.createOverlayColor(red, green, blue)
        }

        if ("armorHelmet" == boneName) {
            return super.calculateBoneArmorColor(boneName, red, green, blue)
        }

        val data = this.renderEntity.entityData
        val stack: ItemStack = when (boneName) {
            "braBoobL", "braBoobR", "armorNippleR", "armorNippleL" -> data.get(AbstractGirlNpcEntity.CHEST_SLOT)
            "turnable", "static", "slip" -> data.get(AbstractGirlNpcEntity.LEGS_SLOT)
            "shinL", "shinR", "sockL", "sockR", "kneeL", "kneeR" -> data.get(AbstractGirlNpcEntity.BOOTS_SLOT)
            else -> ItemStack.EMPTY
        }

        val armor = stack.item as? ArmorItem ?: return this.createOverlayColor(red, green, blue)

        val material = armor.material
        return if (material.`is`(ArmorMaterials.GOLD)) {
            Vector4f(red, green, blue, -0.15625f)
        } else if (material.`is`(ArmorMaterials.IRON) || material.`is`(ArmorMaterials.CHAIN)) {
            Vector4f(red, green, blue, -0.125f)
        } else if (material.`is`(ArmorMaterials.LEATHER)) {
            val colorInt = DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR)
            red *= (colorInt shr 16 and 0xFF) / 255.0f
            green *= (colorInt shr 8 and 0xFF) / 255.0f
            blue *= (colorInt and 0xFF) / 255.0f
            Vector4f(red, green, blue, -0.09375f)
        } else {
            Vector4f(red, green, blue, -0.1875f)
        }
    }

    /** Two passes: `body`, then `steve` with the render-scale factor as alpha (girl texture, as in 1.12). */
    override fun renderModelBuffer(model: BakedGeoModel, entity: BaseGirlEntity, r: Float, g: Float, b: Float, a: Float, scale: Float) {
        if (model.topLevelBones().isEmpty()) {
            return
        }

        val rootBone = model.topLevelBones()[0]
        var bodyBone: GeoBone? = null
        var headBone: GeoBone? = null

        for (childBone in rootBone.childBones) {
            when (childBone.name) {
                "steve" -> headBone = childBone
                "body" -> bodyBone = childBone
            }
        }

        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, rootBone)
            if (bodyBone != null) {
                this.renderRecursively(bodyBone, r, g, b, a)
            }

            this.renderCtx.switchTexture(this.getTextureLocation(this.renderEntity))
            if (headBone != null) {
                this.renderRecursively(headBone, r, g, b, this.renderEntity.getRenderScaleFactor())
            }
        } finally {
            poseStack.popPose()
        }
    }

    companion object {
        @JvmField
        val blacklistBones: HashSet<String> = hashSetOf(
            "kneeL", "kneeR", "shinL", "shinR", "armorHelmet", "sockL", "sockR",
            "braBoobL", "braBoobR", "armorNippleR", "armorNippleL", "slip", "turnable", "static",
        )
    }
}
