package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.client.renderer.GoblinRenderer
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.entity.GoblinPlayerEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.Vec3i
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector4f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the player-form Goblin (horny potion).
 *
 * Port notes: the 1.12 class re-declared `playerGirl` as a `GoblinPlayerEntity` field; it is
 * [goblinPlayerGirl] here (the inherited `playerGirl` is still set by [GirlPlayerRenderer]).
 * Hook parameters use the base names (positional logic unchanged). The throw-pose matrix push
 * is popped whenever it was pushed.
 */
open class PlayerGoblinRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) :
    AbstractPlayerKoblinGoboldRenderer(ctx, geoModel) {
    @JvmField var goblinPlayerGirl: GoblinPlayerEntity? = null
    @JvmField var isShoulderIdle: Boolean = false
    @JvmField var isBeingPickedUp: Boolean = false
    @JvmField var isFirstPersonView: Boolean = false

    override fun resolveBoneColor(boneName: String): Vec3i {
        val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(this.renderEntity)
        return if (modelCodeParts.size < 8) {
            tintColor
        } else if (boneName.contains("band")) {
            GoblinRenderer.DEFAULT_BONE_COLOR
        } else if (boneName.contains("eyeColor") || boneName.contains("eyeColor2")) {
            GoblinRenderer.getEyeColor(modelCodeParts[8])
        } else if (boneName.contains("variant") || boneName.contains("boob")) {
            GoblinRenderer.getSkinColor(modelCodeParts[7])
        } else if (boneName.contains("hair")) {
            GoblinRenderer.getHairColor(modelCodeParts[6])
        } else if (GoblinRenderer.NUDE_BONE_NAMES.contains(boneName)) {
            GoblinRenderer.getSkinColor(modelCodeParts[7])
        } else {
            if (GoblinRenderer.LASH_BONE_NAMES.contains(boneName)) GoblinRenderer.getHairColor(modelCodeParts[6]) else tintColor
        }
    }

    override fun calculateBoneArmorColor(boneName: String, r: Float, g: Float, b: Float): Vector4f {
        var red = r
        var green = g
        var blue = b
        if (boneName.startsWith("crown")) {
            val stack = this.renderEntity.entityData.get(AbstractGirlNpcEntity.HELMET_SLOT)
            if (stack.isEmpty) {
                return super.calculateBoneArmorColor(boneName, red, green, blue)
            }

            val armor = stack.item as ArmorItem // java-npe style: a non-armor helmet threw ClassCastException in 1.12 too
            val material = armor.material
            var armorValue = 0.0f
            if (material.`is`(ArmorMaterials.GOLD)) {
                armorValue = 1.0f
            } else if (material.`is`(ArmorMaterials.CHAIN) || material.`is`(ArmorMaterials.IRON)) {
                armorValue = 2.0f
            } else if (material.`is`(ArmorMaterials.LEATHER)) {
                armorValue = 4.0f
                val colorInt = DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR)
                red = (colorInt shr 16 and 0xFF) / 255.0f
                green = (colorInt shr 8 and 0xFF) / 255.0f
                blue = (colorInt and 0xFF) / 255.0f
            }

            return Vector4f(red, green, blue, 72.0f * armorValue / 4096.0f)
        } else {
            return super.calculateBoneArmorColor(boneName, red, green, blue)
        }
    }

    override fun isBoneBlacklisted(boneName: String): Boolean {
        return if (boneName.startsWith("crown")) true else super.isBoneBlacklisted(boneName)
    }

    override fun getBlacklistedBones(): HashSet<String> {
        return hashSetOf("boobs", "booty", "vagina", "fuckhole", "preggy", "LegL", "LegR", "cheekR", "cheekL")
    }

    override fun onBoneRenderStart(boneName: String, bone: GeoBone) {
        val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(this.renderEntity)
        if (modelCodeParts.size >= 8) {
            when (boneName) {
                "earL" -> GoblinRenderer.applyBoneParts(bone, modelCodeParts[0], modelCodeParts[1], modelCodeParts[3])
                "earR" -> GoblinRenderer.applyBoneParts(bone, modelCodeParts[0], modelCodeParts[2], modelCodeParts[4])
                "hair" -> GoblinRenderer.applyBonePart(bone, modelCodeParts[5])
                "body" -> {
                    bone.setPivotY(-0.15f)
                    GoblinRenderer.applyBoneState(this.renderEntity, bone)
                }
                "LegR" -> GoblinRenderer.applyBoneRot(this.isShoulderIdle, bone, 25.0f, 25.0f)
                "boobR" -> GoblinRenderer.applyBoneRot(this.isShoulderIdle, bone, 30.0f, 30.0f)
                "boobR1" -> GoblinRenderer.applyBoneRot(this.isShoulderIdle, bone, 10.0f, 15.0f)
                "boobR2" -> GoblinRenderer.applyBoneRot(this.isShoulderIdle, bone, 5.0f, 3.0f)
            }

            if (boneName.contains("crown")) {
                GoblinRenderer.applyBoneColor(this.renderEntity, bone, modelCodeParts[9])
            }
        }
    }

    override fun vanillaRenderBase(entity: BaseGirlEntity, entityYaw: Float, partialTick: Float): Vec3 {
        return if (entityYaw == GoblinRenderer.SENTINEL_VALUE) Vec3.ZERO else super.vanillaRenderBase(entity, entityYaw, partialTick)
    }

    private fun rotateAroundAxis(poseStack: PoseStack, degrees: Float, x: Float, y: Float, z: Float) {
        val length = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        if (length != 0.0f) {
            poseStack.mulPose(Quaternionf().rotationAxis(Math.toRadians(degrees.toDouble()).toFloat(), x / length, y / length, z / length))
        }
    }

    override fun doRenderEntity(
        entity: BaseGirlEntity,
        x0: Double,
        y0: Double,
        z0: Double,
        yaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        var x = x0
        var y = y0
        var z = z0
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        this.isFirstPersonView = isFirstPerson
        val goblin = entity as GoblinPlayerEntity
        this.goblinPlayerGirl = goblin
        this.isShoulderIdle = GoblinRenderer.SENTINEL_VALUE == yaw && entity.getCurrentAction() == Action.SHOULDER_IDLE
        this.isBeingPickedUp = GoblinRenderer.SENTINEL_VALUE == yaw && entity.getCurrentAction() == Action.PICK_UP
        this.partialTicks = partialTick
        GoblinRenderer.currentActionValue = yaw
        val action = entity.getCurrentAction()
        val ownerUuid = goblin.getOwnerUUID()
        var pushed = false
        try {
            if (ownerUuid != null) {
                if (entity.isLocallyRegistered()) {
                    val throwPos = GoblinRenderer.getThrowPosition(entity.level(), entity, ownerUuid, x, y, z)
                    x = throwPos.x
                    y = throwPos.y
                    z = throwPos.z
                }

                if (action == Action.THROWN || action == Action.START_THROWING) {
                    if (ClientHooks.thirdPersonView() == 0 && yaw == GoblinRenderer.SENTINEL_VALUE && !entity.isLocallyRegistered()) {
                        return
                    }

                    if (!entity.isLocallyRegistered()) {
                        val yawOffset = entity.getYawRotation()
                        entity.yBodyRotO = yawOffset
                        entity.yBodyRot = yawOffset
                    }
                }

                if (GoblinRenderer.isThrowAction(entity, action)) {
                    if (player.uuid == ownerUuid) {
                        if (GoblinRenderer.SENTINEL_VALUE != yaw) {
                            return
                        }

                        entity.yBodyRot = player.yRot + 180.0f
                        entity.yBodyRotO = player.yRot + 180.0f
                        val lookVec = player.lookAngle
                        poseStack.pushPose()
                        pushed = true
                        poseStack.translate(lookVec.x, lookVec.y + player.eyeHeight, lookVec.z)
                        val rotateVec = GoblinEntity.rotateVectorYaw(Vec3(-Math.abs(player.xRot).toDouble(), 0.0, 0.0), player.yRot)
                        this.rotateAroundAxis(poseStack, player.xRot, rotateVec.x.toFloat(), 0.0f, rotateVec.z.toFloat())
                        x = 0.0
                        y = 0.0
                        z = 0.0
                    } else if (goblin.getOwnerUserUUID() != player.uuid) {
                        if (!entity.isLocallyRegistered() || player.uuid == ownerUuid) {
                            if (player.uuid != ownerUuid) {
                                val owner = entity.level().getPlayerByUUID(ownerUuid)
                                if (owner != null) {
                                    entity.yBodyRot = owner.yRot
                                    entity.yBodyRotO = owner.yRot
                                }
                            } else {
                                entity.yBodyRot = player.yRot
                                entity.yBodyRotO = player.yRot
                            }
                        }

                        val aim = GoblinRenderer.getThrowAim(entity, goblin.getOwnerUUID(), partialTick)
                        x = aim.x
                        y = aim.y
                        z = aim.z
                    }
                } else if (this.isShoulderIdle) {
                    GoblinRenderer.setFirstPersonCamera(poseStack, partialTick)
                    var cameraOffset = Vec3(RotationHelper.lerp(-0.1f, 0.2f, mc.options.fov().get() / 110.0f).toDouble(), 0.0, 0.0)
                    cameraOffset = GoblinEntity.rotateVectorYaw(cameraOffset, player.yRot)
                    x = cameraOffset.x
                    y = cameraOffset.y
                    z = cameraOffset.z
                    entity.yBodyRot = player.yRot
                    entity.yBodyRotO = player.yRotO
                    if (player.isCrouching) {
                        y -= 0.075
                    }
                } else if (action == Action.SHOULDER_IDLE) {
                    if (player.uuid == ownerUuid && ClientHooks.thirdPersonView() == 0) {
                        return
                    }

                    val owner2 = entity.level().getPlayerByUUID(ownerUuid) ?: return

                    val pov = GoblinRenderer.getFirstPersonView(owner2, partialTick)
                    x = pov.x.toDouble()
                    y = pov.y.toDouble()
                    z = pov.z.toDouble()
                    entity.yBodyRot = pov.w
                    if (owner2.isCrouching) {
                        y -= 0.32
                    }
                } else if (action == Action.PICK_UP) {
                    val owner3 = entity.level().getPlayerByUUID(ownerUuid)
                    if (owner3 != null) {
                        entity.yBodyRotO = owner3.yHeadRotO
                        entity.yBodyRot = owner3.yHeadRot
                    }
                }

                super.doRenderEntity(entity, x, y, z, yaw, partialTick, poseStack, bufferSource, packedLight)
            } else {
                if (entity.isLocallyRegistered()) {
                    val throwPos2 = GoblinRenderer.getThrowPosition(entity.level(), entity, ownerUuid, x, y, z)
                    x = throwPos2.x
                    y = throwPos2.y
                    z = throwPos2.z
                }

                if (action == Action.THROWN || action == Action.START_THROWING) {
                    if (ClientHooks.thirdPersonView() == 0 && yaw == GoblinRenderer.SENTINEL_VALUE && !entity.isLocallyRegistered()) {
                        return
                    }

                    if (!entity.isLocallyRegistered()) {
                        val yawOffset2 = entity.getYawRotation()
                        entity.yBodyRotO = yawOffset2
                        entity.yBodyRot = yawOffset2
                    }
                }

                if (GoblinRenderer.isThrowAction(entity, action)) {
                    // ownerUuid is null on this branch; the 1.12 `getOwnerUserUUID().equals(...)` would NPE
                    // for an unowned player-goblin, so only the throw-aim case is reachable here
                    if (goblin.getOwnerUserUUID() != player.uuid) {
                        entity.yBodyRot = player.yRot
                        entity.yBodyRotO = player.yRot
                        val aim2 = GoblinRenderer.getThrowAim(entity, goblin.getOwnerUUID(), partialTick)
                        x = aim2.x
                        y = aim2.y
                        z = aim2.z
                    }
                } else if (this.isShoulderIdle) {
                    GoblinRenderer.setFirstPersonCamera(poseStack, partialTick)
                    var cameraOffset2 = Vec3(RotationHelper.lerp(-0.1f, 0.2f, mc.options.fov().get() / 110.0f).toDouble(), 0.0, 0.0)
                    cameraOffset2 = GoblinEntity.rotateVectorYaw(cameraOffset2, player.yRot)
                    x = cameraOffset2.x
                    y = cameraOffset2.y
                    z = cameraOffset2.z
                    entity.yBodyRot = player.yRot
                    entity.yBodyRotO = player.yRotO
                    if (player.isCrouching) {
                        y -= 0.075
                    }
                } else {
                    if (action == Action.SHOULDER_IDLE) {
                        return
                    }
                }

                super.doRenderEntity(entity, x, y, z, yaw, partialTick, poseStack, bufferSource, packedLight)
            }
        } finally {
            if (pushed) {
                poseStack.popPose()
            }
        }
    }

    override fun drawOverlayLines(girl: BaseGirlEntity, color: com.trolmastercard.sexmod.util.Vector3fSexmodSpecial, lineWidth: Float) {
        renderGirlTint(girl, color, lineWidth)
    }

    override fun getAdditionalOverlayColor(entity: BaseGirlEntity): Vector3fSexmodSpecial? {
        if (!this.isFirstPersonView) {
            return null
        }

        if (entity !is GoblinPlayerEntity) {
            return null
        }

        val ownerUuid = entity.getOwnerUserUUID()
        val clientPlayer = Minecraft.getInstance().player ?: return null
        if (ownerUuid != null && (ClientHooks.thirdPersonView() != 0 || clientPlayer.uuid != ownerUuid)) {
            entity.getOwnerPlayer() ?: return null

            val stack = entity.entityData.get(AbstractGirlNpcEntity.CHEST_SLOT)
            if (stack.isEmpty) {
                return null
            }

            val armor = stack.item as? ArmorItem ?: return null

            val material = armor.material
            return if (material.`is`(ArmorMaterials.GOLD)) {
                Vector3fSexmodSpecial(99.0f, 98.0f, 14.0f)
            } else if (material.`is`(ArmorMaterials.CHAIN) || material.`is`(ArmorMaterials.IRON)) {
                Vector3fSexmodSpecial(85.0f, 85.0f, 85.0f)
            } else if (material.`is`(ArmorMaterials.LEATHER)) {
                val colorInt = DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR)
                Vector3fSexmodSpecial((colorInt shr 16 and 0xFF).toFloat(), (colorInt shr 8 and 0xFF).toFloat(), (colorInt and 0xFF).toFloat())
            } else {
                Vector3fSexmodSpecial(23.0f, 100.0f, 93.0f)
            }
        } else {
            return null
        }
    }

    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0, -0.77, -0.05)
        poseStack.scale(0.5f, 0.5f, 0.5f)
    }

    override fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        super.applyItemPostRotation(isOffhand, stack)
        val poseStack = this.renderCtx.poseStack
        if (stack.useAnimation == UseAnim.BOW) {
            if (isOffhand) {
                poseStack.translate(0.1f, 0.0f, 0.0f)
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
            } else {
                poseStack.mulPose(Axis.XP.rotationDegrees(170.0f))
            }
        } else {
            poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 70.0f else 180.0f))
            poseStack.translate(0.0, 0.05, -0.03)
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        if (isOffhand) {
            if (isBlocking) {
                poseStack.translate(0.0, 0.2, -0.25)
                poseStack.mulPose(Axis.YP.rotationDegrees(85.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(38.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f))
            } else {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
                poseStack.translate(0.0, -0.265, -0.04)
            }
        } else if (isBlocking) {
            poseStack.mulPose(Axis.XP.rotationDegrees(0.0f))
            poseStack.mulPose(Axis.YP.rotationDegrees(150.0f))
            poseStack.mulPose(Axis.ZP.rotationDegrees(0.0f))
            poseStack.translate(0.0, -0.33, -0.1)
        } else {
            poseStack.translate(-0.02, -0.05, -0.05)
        }
    }

    companion object {
        /** Java static inherited from [AbstractPlayerKoblinGoboldRenderer]; companions are not inherited in Kotlin. */
        @JvmStatic
        fun clearRenderCache() {
            AbstractPlayerKoblinGoboldRenderer.clearRenderCache()
        }
    }
}
