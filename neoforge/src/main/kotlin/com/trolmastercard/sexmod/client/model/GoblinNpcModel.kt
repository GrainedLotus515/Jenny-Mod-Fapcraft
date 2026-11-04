package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.entity.api.IGoblin
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Geckolib model for the goblin: nude/armored geo variants whose per-frame
 * pass drives the throw/pick-up/vanishing/breeding poses — pregnancy bone,
 * first-person body hiding, throw-leg swing from the owner's limb motion,
 * head look-at for awaiting players, and the body offset during breeding
 * scenes in first person.
 *
 * **Pose matrix.** Body/head bones are hidden or repositioned per action
 * and per perspective (owner in first person vs. onlookers); the goblin
 * renderer relies on these exact hidden states for the shoulder/pick-up
 * views — keep the action switch intact.
 *
 * Port note: the Java subclass re-declared `mc`; the inherited [GirlModel.mc] is used.
 * Missing bones are null-safe no-ops.
 */
class GoblinNpcModel : GirlModel<BaseGirlEntity>() {
    @JvmField
    val legSwingAngle: Float = 60.0f

    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/goblin/goblin.geo.json"), Main.rl("geo/goblin/armored.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/goblin/goblin.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/goblin/goblin.animation.json")

    override fun canRender(girl: BaseGirlEntity): Boolean {
        if (girl !is GoblinEntity) {
            return super.canRender(girl)
        }

        var uuid = girl.getInteractionPlayerUUID()
        if (uuid == null) {
            uuid = girl.getOwnerUUID()
        }

        if (uuid == null) {
            return true
        }

        val world = girl.level()
        val player = world.getPlayerByUUID(uuid) as? AbstractClientPlayer ?: return true
        return player.skin.model() == PlayerSkin.Model.WIDE
    }

    private fun isLocalPlayer(uuid: java.util.UUID?): Boolean {
        val local = this.mc.player ?: return false
        return local.uuid == uuid
    }

    /**
     * Per-frame pose pass (see class doc): pregnancy bone, breeding body
     * offset, look/throw/vanish bone states, throw leg swing and walk/idle
     * poses. Two near-identical branches cover owned goblins vs. other
     * entities — keep them in sync.
     */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        if (!SexWorldClient.isPreloadWorld(entity.level())) {
            val processor = this.animationProcessor
            if (entity is GoblinEntity) {
                val preggyBone2 = processor.getBone("preggy")
                preggyBone2?.setHidden(!entity.entityData.get(GoblinEntity.PREGNANT))
                val bodyBone2 = processor.getBone("body") ?: return
                val headBone2 = processor.getBone("head")
                val action2 = entity.getCurrentAction()
                if ((action2 == Action.BREEDING_SLOW_2 || action2 == Action.BREEDING_FAST_2 || action2 == Action.BREEDING_CUM_2) && ClientHooks.thirdPersonView() == 0) {
                    bodyBone2.setPosY(bodyBone2.posY + 1.5f)
                }

                val goblin2 = entity as IGoblin
                if ((action2 == Action.AWAIT_PICK_UP || action2 == Action.VANISH) && headBone2 != null) {
                    this.updateBoneLook(entity, bodyBone2, headBone2)
                }

                if (action2 == Action.SIT && headBone2 != null) {
                    this.updateBoneLook(entity, headBone2)
                }

                if (action2 == Action.START_THROWING) {
                    if (this.isLocalPlayer(goblin2.getOwnerUUID())) {
                        this.applyGoblinBone(bodyBone2, processor, entity, goblin2)
                    } else {
                        this.applyBoneState(bodyBone2, processor, entity)
                    }
                } else {
                    bodyBone2.setHidden(false)
                }

                if (!bodyBone2.isHidden && action2 == Action.START_THROWING || action2 == Action.THROWN) {
                    val interpolatedPos2 = getInterpolatedPosition(entity)
                    bodyBone2.setRotX(interpolatedPos2.x.toFloat())
                    bodyBone2.setPosY(interpolatedPos2.y.toFloat())
                    bodyBone2.setPosZ(interpolatedPos2.z.toFloat())
                }

                if (action2 == Action.START_THROWING || action2 == Action.PICK_UP) {
                    this.updateThrowPose(processor, goblin2, entity)
                }
            } else {
                val preggyBone = processor.getBone("preggy")
                preggyBone?.setHidden(!entity.entityData.get(GoblinEntity.PREGNANT))
                val bodyBone = processor.getBone("body") ?: return
                val headBone = processor.getBone("head")
                val action = entity.getCurrentAction()
                if ((action == Action.BREEDING_SLOW_2 || action == Action.BREEDING_FAST_2 || action == Action.BREEDING_CUM_2) && ClientHooks.thirdPersonView() == 0) {
                    bodyBone.setPosY(bodyBone.posY + 1.5f)
                }

                val goblin = entity as IGoblin
                if (action == Action.VANISH && headBone != null) {
                    this.updateBoneLook(entity, bodyBone, headBone)
                }

                if (action == Action.START_THROWING) {
                    if (this.isLocalPlayer(goblin.getOwnerUUID())) {
                        this.applyGoblinBone(bodyBone, processor, entity, goblin)
                    } else {
                        this.applyBoneState(bodyBone, processor, entity)
                    }
                } else {
                    bodyBone.setHidden(false)
                }

                if (!bodyBone.isHidden && action == Action.START_THROWING || action == Action.THROWN) {
                    val interpolatedPos = getInterpolatedPosition(entity)
                    bodyBone.setRotX(interpolatedPos.x.toFloat())
                    bodyBone.setPosY(interpolatedPos.y.toFloat())
                    bodyBone.setPosZ(interpolatedPos.z.toFloat())
                }

                if (action == Action.START_THROWING || action == Action.PICK_UP) {
                    this.updateThrowPose(processor, goblin, entity)
                }

                this.updateWalkPose(processor, entity)
                this.updateIdlePose(processor, entity)
            }
        }
    }

    /**
     * Hides the body entirely when the owner's own goblin is being thrown in
     * first person (the POV path renders it instead).
     */
    fun updateIdlePose(processor: AnimationProcessor<BaseGirlEntity>, goblin: BaseGirlEntity) {
        if (goblin.getCurrentAction() == Action.START_THROWING) {
            if (ClientHooks.thirdPersonView() == 0 && this.isLocalPlayer((goblin as AbstractPlayerGirlEntity).getOwnerUserUUID())) {
                val bodyBone = processor.getBone("body")
                bodyBone?.setHidden(true)
            }
        }
    }

    /**
     * While the goblin is picked up, onlookers see it lowered by 32 px (dangling
     * in the owner's hands); the owner in first person keeps the POV placement.
     */
    fun updateWalkPose(processor: AnimationProcessor<BaseGirlEntity>, goblin: BaseGirlEntity) {
        if (goblin.getCurrentAction() == Action.PICK_UP) {
            if (ClientHooks.thirdPersonView() != 0 || !this.isLocalPlayer((goblin as IGoblin).getOwnerUUID())) {
                val bodyBone = processor.getBone("body")
                if (bodyBone != null) {
                    val steveBone = processor.getBone("steve")
                    if (steveBone != null) {
                        bodyBone.setPosY(bodyBone.posY - 32.0f)
                        steveBone.setPosY(steveBone.posY - 32.0f)
                    }
                }
            }
        }
    }

    /**
     * Throw/catch leg swing: the goblin's legs swing opposite the owner's
     * interpolated limb swing (60-degree amplitude) while throwing or being
     * picked up.
     */
    fun updateThrowPose(processor: AnimationProcessor<BaseGirlEntity>, goblin: IGoblin, entity: BaseGirlEntity) {
        val ownerUuid = goblin.getOwnerUUID()
        if (ownerUuid != null) {
            val owner = entity.level().getPlayerByUUID(ownerUuid)
            if (owner != null) {
                val limbSwingAmount = owner.walkAnimation.speed(ClientHooks.partialTick())
                val limbSwing = owner.walkAnimation.position()
                val swingSin = Math.sin(limbSwing.toDouble()).toFloat()
                val leftLegBone = processor.getBone("LeftLeg")
                val rightLegBone = processor.getBone("RightLeg")
                val swingAngle = TrigMath.wrapDegrees(60.0f * swingSin * limbSwingAmount)
                leftLegBone?.setRotX(swingAngle)
                rightLegBone?.setRotX(-swingAngle)
            }
        } else {
            entity.getInteractionPlayerUUID()
        }
    }

    /**
     * Head-only look at the nearest player within 15 blocks, gated by the
     * goblin's facing (only looks when the player is in front of it).
     */
    fun updateBoneLook(entity: BaseGirlEntity, headBone: GeoBone) {
        val player = entity.level().getNearestPlayer(entity, 15.0)
        if (player != null) {
            val playerPos = player.position()
            val entityPos = entity.position()
            val delta = playerPos.subtract(entityPos)
            val yaw = entity.yRot
            var inFront = false
            when (yaw.toInt()) {
                -90 -> inFront = playerPos.x > entityPos.x
                0 -> inFront = playerPos.z > entityPos.z
                90 -> inFront = playerPos.x < entityPos.x
                180 -> inFront = playerPos.z < entityPos.z
            }

            if (!inFront) {
                headBone.setRotY(0.0f)
            } else {
                var facingOffset = 0.0f
                when (yaw.toInt()) {
                    0 -> facingOffset = -90.0f
                    90 -> facingOffset = 180.0f
                    180 -> facingOffset = 90.0f
                }

                val yawAngle = (-(Mth.atan2(delta.z, delta.x) * (180.0 / Math.PI) + facingOffset)).toFloat()
                val pitch = ThreadNames.clampFloat((player.eyeHeight + playerPos.y - (entity.eyeHeight + entityPos.y)).toFloat(), -0.75f, 0.75f)
                headBone.setRotY(TrigMath.wrapDegrees(yawAngle))
                headBone.setRotX(pitch)
            }
        }
    }

    /**
     * Body+head look at the nearest player (vanishing pose): body yaw + head
     * pitch, clamped by the height difference.
     */
    fun updateBoneLook(entity: BaseGirlEntity, bodyBone: GeoBone, headBone: GeoBone) {
        val player = entity.level().getNearestPlayer(entity, 15.0)
        if (player != null) {
            val playerPos = player.position()
            val entityPos = entity.position()
            val delta = playerPos.subtract(entityPos)
            val yaw = (-(Math.atan2(delta.z, delta.x) * (180.0 / Math.PI))).toFloat() + 90.0f
            val pitch = ThreadNames.clampFloat((player.eyeHeight + playerPos.y - (entity.eyeHeight + entityPos.y)).toFloat(), -0.75f, 0.75f)
            bodyBone.setRotY(TrigMath.wrapDegrees(yaw))
            headBone.setRotX(pitch)
        }
    }

    /**
     * Start-throw body state for non-owners: locally registered goblins hide
     * the body; real ones show it with the steve skin hidden.
     */
    fun applyBoneState(bodyBone: GeoBone, processor: AnimationProcessor<BaseGirlEntity>, goblin: BaseGirlEntity) {
        if (goblin.isLocallyRegistered()) {
            bodyBone.setHidden(true)
        } else {
            bodyBone.setHidden(false)
            ModelBones.setHidden(processor, "steve", true)
        }
    }

    /**
     * Start-throw body state for the owner: body hidden for locally registered
     * previews, else hidden until the throw progress passes 15 ticks; steve
     * skin always hidden during the throw.
     */
    fun applyGoblinBone(bodyBone: GeoBone, processor: AnimationProcessor<BaseGirlEntity>, goblin: BaseGirlEntity, goblinApi: IGoblin) {
        if (goblin.isLocallyRegistered()) {
            bodyBone.setHidden(true)
        } else {
            bodyBone.setHidden(goblinApi.getThrowProgress() < 15)
        }

        if (!goblin.isLocallyRegistered()) {
            ModelBones.setHidden(processor, "steve", true)
        }
    }

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun TopArmor(): Array<String> = arrayOf("armorBoobL", "armorBoobR")

    override fun Top(): Array<String> = arrayOf("nippleL", "nippleR")

    override fun BottomArmor(): Array<String> = arrayOf("armorCheekR", "armorCheekL", "armorLegL", "armorLegR", "armorShinL", "armorShinR", "armorTorso")

    override fun Bottom(): Array<String> = arrayOf("fuckhole", "vagina", "meatCheekR", "meatCheekL", "meatLegL", "meatLegR", "meatShinL", "meatShinR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorFootL", "armorFootR")

    override fun Shoes(): Array<String> = arrayOf("meatFootL", "meatFootR")
}
