package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.resources.ResourceLocation

/**
 * Geckolib model for the cat NPC: cat geo (both outfit slots map to the same
 * file), shared texture, standard armor/nude bone groups.
 */
class CatNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/cat/cat.geo.json"), Main.rl("geo/cat/cat.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/cat/cat.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/cat/cat.animation.json")

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "cloth")

    override fun BottomArmor(): Array<String> = arrayOf("armorBootyR", "armorBootyL", "armorPantsLowL", "armorPantsLowR", "armorPantsLowR", "armorPantsUpR", "armorPantsUpL", "armorHip")

    override fun Bottom(): Array<String> = arrayOf("fleshL", "fleshR", "vagina", "curvesL", "curvesR", "kneeL", "kneeR", "cloth")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")
}
