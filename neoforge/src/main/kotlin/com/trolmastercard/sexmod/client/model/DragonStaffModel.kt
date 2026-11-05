package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.DragonStaffItem
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib model for the dragon staff item (geo/texture/animation locations
 * only).
 */
open class DragonStaffModel : GeoModel<DragonStaffItem>() {
    override fun getModelResource(item: DragonStaffItem?): ResourceLocation = Main.rl("geo/kobold/staff.geo.json")

    override fun getTextureResource(item: DragonStaffItem?): ResourceLocation = Main.rl("textures/entity/kobold/staff.png")

    override fun getAnimationResource(item: DragonStaffItem?): ResourceLocation = Main.rl("animations/kobold/staff.animation.json")
}
