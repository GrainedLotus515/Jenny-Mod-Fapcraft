package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.WinchesterItem
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib model for the Winchester item (geo/texture/animation locations only).
 * Note: the texture never existed in the 1.12 tree either (see ASSET_RENAMES.md);
 * the path follows the 1.21 `textures/item/` layout.
 */
open class SummonItemModel : GeoModel<WinchesterItem>() {
    override fun getModelResource(item: WinchesterItem?): ResourceLocation = Main.rl("geo/west/winchester.geo.json")

    override fun getTextureResource(item: WinchesterItem?): ResourceLocation = Main.rl("textures/item/winchester/winchester.png")

    override fun getAnimationResource(item: WinchesterItem?): ResourceLocation = Main.rl("animations/west/winchester.animation.json")
}
