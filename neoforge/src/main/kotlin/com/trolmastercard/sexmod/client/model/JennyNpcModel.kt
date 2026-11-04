package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.resources.ResourceLocation

/**
 * Geckolib model for the Jenny NPC: nude/dressed geo variants, shared texture,
 * standard armor/nude bone groups (no attachments).
 */
class JennyNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/jenny/jennynude.geo.json"), Main.rl("geo/jenny/jennydressed.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/jenny/jenny.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/jenny/jenny.animation.json")

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "upperBodyL", "upperBodyR")

    override fun BottomArmor(): Array<String> = arrayOf("armorBootyR", "armorBootyL", "armorPantsLowL", "armorPantsLowR", "armorPantsLowR", "armorPantsUpR", "armorPantsUpL", "armorHip")

    override fun Bottom(): Array<String> = arrayOf("fleshL", "fleshR", "vagina", "curvesL", "curvesR", "kneeL", "kneeR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")
}
