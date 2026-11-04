package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.resources.ResourceLocation

/**
 * Geckolib model for the Allie NPC: three outfit geo variants (nude, armored,
 * nude-2) with the shared Allie texture and the standard armor/nude bone
 * groups.
 */
class AllieNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/allie/allie.geo.json"), Main.rl("geo/allie/armored.geo.json"), Main.rl("geo/allie/allie.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/allie/allie.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/allie/allie.animation.json")

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "clothes", "clothesR", "clothesL")
}
