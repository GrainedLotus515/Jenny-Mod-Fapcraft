package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.model.KoboldEggItemModel
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.item.KoboldEggItem
import com.trolmastercard.sexmod.util.ItemNbt
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.util.FastColor
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.renderer.GeoItemRenderer

/**
 * Item renderer for the kobold egg: renders the geckolib egg model, tinting
 * the `shell` bone with the static egg base color and the
 * `colorSpots` bone with the egg's variant color derived from the item
 * meta (wool id, `ItemNbt.getMeta`).
 *
 * CLIENT-side render thread only. The tint overrides the incoming color
 * parameters for those two bone names — keep the name matching intact.
 * No-argument constructor (PORTING.md §2.8 item template).
 */
class KoboldEggItemRenderer : GeoItemRenderer<KoboldEggItem>(KoboldEggItemModel()) {
    @JvmField
    var eggStack: ItemStack? = null

    override fun renderByItem(stack: ItemStack, transformType: ItemDisplayContext, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        this.renderEggItem(stack, transformType, poseStack, bufferSource, packedLight, packedOverlay)
    }

    fun renderEggItem(stack: ItemStack, transformType: ItemDisplayContext, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        this.eggStack = stack
        super.renderByItem(stack, transformType, poseStack, bufferSource, packedLight, packedOverlay)
    }

    /**
     * Per-bone tint pass: `shell` -> static [KoboldEggRenderer.eggColor],
     * `colorSpots` -> the wool-id color of the held stack (meta).
     */
    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: KoboldEggItem,
        bone: GeoBone,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        var tint = colour
        val boneName = bone.name
        if ("shell" == boneName) {
            tint = FastColor.ARGB32.color(
                FastColor.ARGB32.alpha(colour),
                KoboldEggRenderer.eggColor.red,
                KoboldEggRenderer.eggColor.green,
                KoboldEggRenderer.eggColor.blue,
            )
        }

        val stack = this.eggStack
        if ("colorSpots" == boneName && stack != null) {
            val eggColor = this.getEggColor(stack).getMainColor()
            tint = FastColor.ARGB32.color(FastColor.ARGB32.alpha(colour), eggColor.x, eggColor.y, eggColor.z)
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, tint)
    }

    fun getEggColor(stack: ItemStack): EyeAndKoboldColor {
        return EyeAndKoboldColor.getColorByWoolId(ItemNbt.getMeta(stack))
    }
}
