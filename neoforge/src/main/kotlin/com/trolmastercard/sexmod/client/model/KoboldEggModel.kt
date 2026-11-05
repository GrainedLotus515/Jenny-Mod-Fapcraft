package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.KoboldEggEntity
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib model for the kobold egg entity (thrown/spawned; geo/texture/
 * animation locations only).
 */
class KoboldEggModel : GeoModel<KoboldEggEntity>() {
    override fun getModelResource(egg: KoboldEggEntity?): ResourceLocation = Main.rl("geo/kobold/koboldegg.geo.json")

    override fun getTextureResource(egg: KoboldEggEntity?): ResourceLocation = Main.rl("textures/entity/kobold/koboldegg.png")

    override fun getAnimationResource(egg: KoboldEggEntity?): ResourceLocation = Main.rl("animations/kobold/egg.animation.json")
}
