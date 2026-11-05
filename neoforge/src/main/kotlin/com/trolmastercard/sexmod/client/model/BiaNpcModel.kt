package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.resources.ResourceLocation

/**
 * Geckolib model for the Bia NPC: nude/dressed geo variants, shared texture,
 * armor/nude bone groups with leaf attachments.
 */
class BiaNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/bia/bianude.geo.json"), Main.rl("geo/bia/biadressed.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/bia/bia.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/bia/bia.animation.json")

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun Attachments(): Array<String> = arrayOf("leaf7", "leaf8")

    override fun TopArmor(): Array<String> = arrayOf("armorChest", "armorBoobs", "armorShoulderR", "armorShoulderL")

    override fun Top(): Array<String> = arrayOf("bra", "upperBodyR", "upperBodyL")

    override fun BottomArmor(): Array<String> = arrayOf("armorBootyR", "armorBootyL", "armorPantsLowL", "armorPantsLowR", "armorPantsLowR", "armorPantsUpR", "armorPantsUpL", "armorHip")

    override fun Bottom(): Array<String> = arrayOf("slip", "fleshL", "fleshR", "vagina", "curvesL", "curvesR", "kneeL", "kneeR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")
}
