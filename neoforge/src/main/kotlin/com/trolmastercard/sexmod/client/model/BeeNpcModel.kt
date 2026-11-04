package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.constant.DataTickets

/**
 * Geckolib model for the bee NPC: bee geo (nude/armored variants) with a
 * chest-bone visibility toggle driven by the current animation name, plus the
 * standard head-look and armor/nude bone groups.
 */
class BeeNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/bee/bee.geo.json"), Main.rl("geo/bee/armored.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/bee/bee.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/bee/bee.animation.json")

    /**
     * Hides the `chest` bone unless the movement controller's current
     * animation is a chest animation (e.g. the chest-opening scene).
     */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        if (!SexWorldClient.isPreloadWorld(entity.level())) {
            val processor = this.animationProcessor
            val chestBone = processor.getBone("chest")
            if (chestBone != null) {
                val currentAnimation = entity.movementController?.getCurrentAnimationName()
                chestBone.setHidden(currentAnimation == null || !currentAnimation.contains("chest"))
            }
        }
    }

    override fun handleAnimationEvent(girl: BaseGirlEntity, processor: AnimationProcessor<BaseGirlEntity>, event: AnimationState<BaseGirlEntity>) {
        if (!SexWorldClient.isPreloadWorld(girl.level()) &&
            (girl.getCurrentAction() == Action.NULL || girl.getCurrentAction() == Action.ATTACK || girl.getCurrentAction() == Action.BOW)
        ) {
            val modelData = event.getData(DataTickets.ENTITY_MODEL_DATA) ?: return
            val neckBone = processor.getBone("neck")
            neckBone?.setRotY(modelData.netHeadYaw() * 0.5f * (Math.PI / 180.0).toFloat())
            val headBone = processor.getBone("head")
            if (headBone != null) {
                headBone.setRotY(modelData.netHeadYaw() * (Math.PI / 180.0).toFloat())
                headBone.setRotX(1.0f + modelData.headPitch() * (Math.PI / 180.0).toFloat())
            }
            var bodyBone = processor.getBone("body")
            if (bodyBone == null) bodyBone = processor.getBone("dd")
            bodyBone?.setRotY(0.0f)
        }
    }

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun Attachments(): Array<String> = arrayOf("band", "feeler", "feeler2", "brow", "brow2", "brow3", "brow4")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "upperBodyL", "upperBodyR")

    override fun BottomArmor(): Array<String> = arrayOf("armorBootyR", "armorBootyL", "armorPantsLowL", "armorPantsLowR", "armorPantsLowR", "armorPantsUpR", "armorPantsUpL", "armorHip")

    override fun Bottom(): Array<String> = arrayOf("sideL", "sideR", "fleshL", "fleshR", "vagina", "curvesL", "curvesR", "kneeL", "kneeR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")
}
