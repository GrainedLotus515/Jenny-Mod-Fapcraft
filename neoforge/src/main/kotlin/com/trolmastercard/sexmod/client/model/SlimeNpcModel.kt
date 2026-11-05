package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import java.util.Arrays
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3f
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Geckolib model for the slime NPC: three outfit geo variants (nude, armored,
 * dressed) plus the bed-scene slime body — the `bedSlime`/
 * `bedSlimeLayer` bones are only visible during the doggy bed actions —
 * and a hat bone that mirrors the head's pose for plain NPCs.
 */
class SlimeNpcModel : GirlModel<BaseGirlEntity>() {
    @JvmField
    var bedSlimeActions: Array<Action> = arrayOf(Action.STARTDOGGY, Action.DOGGYCUM, Action.DOGGYSLOW, Action.DOGGYFAST, Action.DOGGYCUM, Action.DOGGYSTART, Action.WAITDOGGY)

    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/slime/nude.geo.json"), Main.rl("geo/slime/armored.geo.json"), Main.rl("geo/slime/dressed.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/slime/slime.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/slime/slime.animation.json")

    /**
     * Toggles the bed-slime bones by action (visible only for the doggy bed
     * actions) and copies the head pose onto the hat bone for plain NPCs.
     */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        val processor = this.animationProcessor
        if (!SexWorldClient.isPreloadWorld(entity.level()) && processor.getBone("bedSlime") != null && processor.getBone("bedSlimeLayer") != null) {
            ModelBones.setHidden(processor, "bedSlime", !Arrays.asList(*this.bedSlimeActions).contains(entity.getCurrentAction()))
            ModelBones.setHidden(processor, "bedSlimeLayer", !Arrays.asList(*this.bedSlimeActions).contains(entity.getCurrentAction()))
        }

        if (entity !is AbstractPlayerGirlEntity) {
            this.applyBoneName(arrayOf("head"), "hat")
        }
    }

    /**
     * Sums the named bones' rotations/positions into the target bone — used to
     * bind the hat to the head's current pose.
     */
    fun applyBoneName(boneNames: Array<String>, targetBoneName: String) {
        val processor = this.animationProcessor
        val targetBone = processor.getBone(targetBoneName) ?: return // java-npe in 1.12; a model without a hat skips it
        val sourceBones = arrayOfNulls<GeoBone>(boneNames.size)

        for (i in sourceBones.indices) {
            sourceBones[i] = processor.getBone(boneNames[i])
        }

        val rotationSum = Vector3f(0.0f, 0.0f, 0.0f)
        val positionSum = Vector3f(0.0f, 0.0f, 0.0f)

        for (bone in sourceBones) {
            if (bone == null) {
                return // java-npe in 1.12; missing source bone skips the copy
            }
            rotationSum.add(Vector3f(bone.rotX, bone.rotY, bone.rotZ))
            positionSum.add(Vector3f(bone.posX, bone.posY, bone.posZ))
        }

        targetBone.setRotX(rotationSum.x)
        targetBone.setRotY(rotationSum.y)
        targetBone.setRotZ(rotationSum.z)
        targetBone.setPosX(positionSum.x)
        targetBone.setPosY(positionSum.y)
        targetBone.setPosZ(positionSum.z)
        targetBone.setPosZ(positionSum.z)
    }

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun Attachments(): Array<String> = arrayOf("bigblob")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "upperBodyL", "upperBodyR", "cloth")

    override fun BottomArmor(): Array<String> = arrayOf("armorBootyR", "armorBootyL", "armorPantsLowL", "armorPantsLowR", "armorPantsLowR", "armorPantsUpR", "armorPantsUpL", "armorHip")

    override fun Bottom(): Array<String> = arrayOf("fleshL", "fleshR", "vagina", "curvesL", "curvesR", "kneeL", "kneeR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")
}
