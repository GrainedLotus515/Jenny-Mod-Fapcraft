package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.animation.AnimationState

/**
 * Geckolib model for the Ellie NPC: nude/dressed outfit geo files, plus a
 * custom sitting pose — while Ellie sits idle, her head tracks the nearest
 * player within 15 blocks (yaw clamped per facing, pitch by height
 * difference). Outfit bone lists define the armor/nude variants.
 */
class EllieNpcModel : GirlModel<BaseGirlEntity>() {
    @JvmField
    var headYawOffsets: HashMap<Int, FloatArray> = hashMapOf(
        0 to floatArrayOf(0.0f, -1.2f, 1.2f),
        -90 to floatArrayOf(2.0f, -71.56f, -68.0f),
        90 to floatArrayOf(-2.0f, 68.0f, 70.5f),
    )

    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/ellie/nude.geo.json"), Main.rl("geo/ellie/dressed.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/ellie/ellie.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/ellie/ellie.animation.json")

    /**
     * Sitting head-tracking: only for the plain NPC (not player-girls) in the
     * SITDOWNIDLE action; the head yaw aims at the nearest player with
     * per-facing clamps (180-degree back-facing has its own arc), pitch from
     * the player's height difference.
     */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        if (!SexWorldClient.isPreloadWorld(entity.level())) {
            if (entity !is AbstractPlayerGirlEntity) {
                if (entity.getCurrentAction() == Action.SITDOWNIDLE) {
                    val nearestPlayer = entity.level().getNearestPlayer(entity, 15.0)
                    if (nearestPlayer != null) {
                        val headBone = this.animationProcessor.getBone("head") ?: return
                        val toPlayer = entity.position().subtract(nearestPlayer.position())
                        val facingYaw = Math.round(entity.getYawRotation())
                        var headYaw: Float
                        if (facingYaw == 180) {
                            headYaw = Math.atan2(toPlayer.x, toPlayer.z).toFloat() * 1.2f
                            headYaw = if (headYaw > 0.0f) {
                                Math.max(1.5f, Math.min(3.14f, headYaw))
                            } else {
                                Math.max(-3.14f, Math.min(-1.5f, headYaw))
                            }

                            if (headYaw != 1.5f && headYaw != 3.14f && headYaw != -3.14f && headYaw != -1.5f) {
                                headYaw += 3.0f
                            } else {
                                headYaw = 0.0f
                            }
                        } else {
                            // java-npe: an unexpected facing (not 0 / +-90 / 180) threw in 1.12 too
                            val offsets = this.headYawOffsets[facingYaw]!!
                            val minClamp = offsets[1]
                            val maxClamp = offsets[2]
                            headYaw = ((Math.atan2(toPlayer.x, toPlayer.z) + offsets[0]).toFloat() + entity.getYawRotation()) * 0.8f
                            headYaw = ThreadNames.clampDouble(headYaw.toDouble(), minClamp.toDouble(), maxClamp.toDouble()).toFloat()
                            if (headYaw == minClamp || headYaw == maxClamp) {
                                headYaw = 0.0f
                            }
                        }

                        val headPitch = if (headYaw == 0.0f) 0.0f else ThreadNames.clampFloat(((nearestPlayer.y - entity.y) * 0.5).toFloat(), -0.75f, 0.75f)
                        headBone.setRotY(headYaw)
                        headBone.setRotX(headPitch)
                    }
                }
            }
        }
    }

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun Attachments(): Array<String> = arrayOf("headband")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "upperBodyL", "upperBodyR")

    override fun BottomArmor(): Array<String> = arrayOf("armorBootyR", "armorBootyL", "armorPantsLowL", "armorPantsLowR", "armorPantsLowR", "armorPantsUpR", "armorPantsUpL", "armorHip")

    override fun Bottom(): Array<String> = arrayOf("fleshL", "fleshR", "vagina", "hotpants", "slip", "curvesL", "curvesR", "kneeL", "kneeR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")
}
