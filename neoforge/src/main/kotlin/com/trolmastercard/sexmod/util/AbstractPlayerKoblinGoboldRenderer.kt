package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BodyParts
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.Vec3i
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.util.Color
import software.bernie.geckolib.util.RenderUtil

/**
 * Base geckolib renderer for player-form kobolds/goblins: per-bone colours from the
 * model code (cached per bone name + girl UUID), the player-girl pose overrides
 * (shared with [GirlPlayerRenderer]) and a cube pass that tints every non-armor
 * bone with its resolved colour.
 */
abstract class AbstractPlayerKoblinGoboldRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) :
    GirlPlayerRenderer(ctx, geoModel) {
    open fun getBoneColor(bone: GeoBone): Vec3i {
        val boneName = bone.name
        val cacheKey = boneName.hashCode() + this.playerGirl.uuid.hashCode()
        var color = boneColorCache[cacheKey]
        if (color != null) {
            return color
        }

        color = this.resolveBoneColor(boneName)
        boneColorCache[cacheKey] = color
        return color
    }

    abstract fun resolveBoneColor(boneName: String): Vec3i

    open fun hideBone(bone: GeoBone, index: Int) {
        val childBones = bone.childBones

        for (i in childBones.indices) {
            val childBone = childBones[i]
            if (index == i) {
                childBone.setHidden(false)
                return
            }
        }
    }

    open fun getDefaultScale(): Float = 1.0f

    open fun getItemRenderOffset(stack: ItemStack?): Vec3 = Vec3(-90.0, 0.0, 0.0)

    open fun getChildBone(bone: GeoBone, index: Int): GeoBone? {
        val childBones = bone.childBones
        var result: GeoBone? = null
        childBones.sortWith(Comparator.comparingDouble { it.pivotY.toDouble() })

        for (i in childBones.indices) {
            val childBone = childBones[i]
            if (index == i) {
                result = childBone
                result.setHidden(false)
            } else {
                childBone.setHidden(true)
            }
        }

        return result
    }

    open fun passThroughColor(color: Vec3i): Vec3i = color

    override fun renderRecursively(bone: GeoBone, r: Float, g: Float, b: Float, a: Float) {
        var red = r
        var green = g
        var blue = b
        val boneName = bone.name
        if (this.isRendering) {
            if (boneName == "upperBody") {
                bone.setRotX(bone.rotX - 0.5f)
            }

            if (boneName == "head") {
                bone.setRotX(bone.rotX + 0.5f)
            }

            if (boneName == "legL" || boneName == "legR") {
                bone.setPosZ(bone.posZ + 1.0f)
            }
        }

        if (boneName == "head") {
            this.renderOverlay(bone, Color.ofRGB(red, green, blue))
        }

        this.onBoneRenderStart(boneName, bone)
        this.onBoneRenderingLayer(boneName, bone, this.playerGirl)
        if (this.isUsingItem && (this.mainhandItem.item is BowItem || this.offhandItem.item is BowItem)) {
            if (boneName == "armR") {
                bone.setRotX(bone.rotX - this.playerGirl.xRot / 50.0f)
            }

            if (boneName == "armL") {
                bone.setRotY(bone.rotY - this.playerGirl.xRot / 50.0f)
            }

            if (this.offhandItem.item is BowItem) {
                val offhandItem = this.offhandItem
                this.offhandItem = this.mainhandItem
                this.mainhandItem = offhandItem
            }
        }

        if (this.isUsingItem && this.mainhandItem.item is ShieldItem) {
            if (boneName == "armR") {
                bone.setRotZ(0.0f)
                bone.setRotX(0.5f)
            } else if (this.offhandItem.item is ShieldItem && boneName == "armL") {
                bone.setRotZ(0.0f)
                bone.setRotX(0.5f)
            }
        }

        if (boneName == "weapon" && !this.mainhandItem.isEmpty) {
            this.renderEquippedItem(bone, false)
        }

        if (boneName == "offhand" && !this.offhandItem.isEmpty) {
            this.renderEquippedItem(bone, true)
        }

        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, bone)
            if ("Head2" == boneName && !this.shouldRenderHead2()) {
                return
            } else if (("neck" == boneName || "head" == boneName) && !this.shouldRenderFirstPersonHead()) {
                return
            }

            if (!bone.isHidden) {
                val armorColor = this.calculateBoneArmorColor(boneName, red, green, blue)
                red = armorColor.x
                green = armorColor.y
                blue = armorColor.z
                val alpha = armorColor.w.toDouble()
                if (!this.activeCustomPartBones.contains(boneName)) {
                    for (cube in bone.cubes) {
                        poseStack.pushPose()
                        try {
                            this.currentRenderingBone = bone
                            this.renderCubeGeometry(cube, bone, red, green, blue, a, alpha)
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }

                for (childBone in bone.childBones) {
                    if (alpha == 0.0) {
                        this.renderRecursively(childBone, red, green, blue, a)
                    } else {
                        this.renderCustomBones(childBone, red, green, blue, a, alpha)
                    }
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Cube pass: armor bones keep the incoming colour, every other bone uses its resolved
     * model-code colour (with the custom-part fake shading); the texture U is shifted by
     * the armor overlay value `alpha`.
     */
    open fun renderCubeGeometry(cube: GeoCube, bone: GeoBone, red: Float, green: Float, blue: Float, partialTicks: Float, alpha: Double) {
        val poseStack = this.renderCtx.poseStack
        RenderUtil.translateToPivotPoint(poseStack, cube)
        RenderUtil.rotateMatrixAroundCube(poseStack, cube)
        RenderUtil.translateAwayFromPivotPoint(poseStack, cube)
        val normalMatrix = poseStack.last().normal()
        val poseMatrix = poseStack.last().pose()

        for (quad in cube.quads()) {
            if (quad == null) {
                continue
            }

            val normal = normalMatrix.transform(Vector3f(quad.normal()))
            RenderUtil.fixInvertedFlatCube(cube, normal)
            val colorVec: Vec3
            if (this.isBoneBlacklisted(bone.name)) {
                colorVec = Vec3(red.toDouble(), green.toDouble(), blue.toDouble())
            } else {
                var boneColor = this.getBoneColor(bone)
                boneColor = this.passThroughColor(boneColor)
                colorVec = BodyParts.getBoneWorldPosition(
                    this,
                    bone,
                    Vec3((boneColor.x / 255.0f).toDouble(), (boneColor.y / 255.0f).toDouble(), (boneColor.z / 255.0f).toDouble()),
                    normal,
                )
            }

            this.emitQuad(quad, poseMatrix, normal, colorVec.x.toFloat(), colorVec.y.toFloat(), colorVec.z.toFloat(), partialTicks, alpha)
        }
    }

    open fun isBoneBlacklisted(boneName: String): Boolean = boneName.startsWith("armor")

    companion object {
        @JvmField
        val tintColor: Vec3i = Vec3i(255, 255, 255)

        @JvmField
        var boneColorCache: HashMap<Int, Vec3i> = HashMap()

        @JvmStatic
        fun clearRenderCache() {
            boneColorCache.clear()
        }
    }
}
