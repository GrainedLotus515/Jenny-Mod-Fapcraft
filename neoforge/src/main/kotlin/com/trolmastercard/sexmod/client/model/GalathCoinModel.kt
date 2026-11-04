package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.GalathCoinItem
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib model for the Galath coin item (geo/texture/animation locations
 * only; the render animations live in `GalathCoinRenderer`).
 */
open class GalathCoinModel : GeoModel<GalathCoinItem>() {
    override fun getModelResource(item: GalathCoinItem?): ResourceLocation = Main.rl("geo/galath/galath_coin.geo.json")

    // ASSET_RENAMES: textures/items/ -> textures/item/
    override fun getTextureResource(item: GalathCoinItem?): ResourceLocation = Main.rl("textures/item/galath_coin/galath_coin.png")

    override fun getAnimationResource(item: GalathCoinItem?): ResourceLocation = Main.rl("animations/galath/galath_coin.animation.json")
}
