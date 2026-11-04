package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.KoboldEggItem
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib model for the kobold egg item (held stack; geo/texture/animation
 * locations only).
 */
class KoboldEggItemModel : GeoModel<KoboldEggItem>() {
    override fun getModelResource(item: KoboldEggItem?): ResourceLocation = Main.rl("geo/kobold/koboldegg.geo.json")

    override fun getTextureResource(item: KoboldEggItem?): ResourceLocation = Main.rl("textures/entity/kobold/koboldegg.png")

    override fun getAnimationResource(item: KoboldEggItem?): ResourceLocation = Main.rl("animations/kobold/egg.animation.json")
}
