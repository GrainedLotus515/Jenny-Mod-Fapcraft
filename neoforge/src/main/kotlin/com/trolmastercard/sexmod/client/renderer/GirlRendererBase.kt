package com.trolmastercard.sexmod.client.renderer

import com.mojang.math.Axis
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BodyParts
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.Vec3i
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.util.RenderUtil

/**
 * Base class for NPC-only girl renderers (goblins etc.): extends
 * [GirlRenderer] with per-bone coloring from the girl's model code and
 * the custom-bone render pipeline (held items, trade overlay, model-code bone
 * processing).
 *
 * **Coloring.** [getCachedBoneColor] resolves each bone's tint via
 * [getBoneColor] and caches it keyed by (bone name hash + entity
 * UUID); [clearBoneColors] resets the cache (e.g. after a model-code
 * change). [tintBoneColor] is the per-frame hook for lighting.
 *
 * **Custom-bone pass.** [renderCustomBones] recurses the whole
 * skeleton and, per bone: renders the held item on `weapon`, the trade
 * overlay on `itemRenderer` while the action is `PAYMENT`,
 * applies [onBoneProcessing] hooks, then pushes the bone transform and renders
 * cubes (tinted) and children. Skipped entirely in the [SexWorldClient]
 * preload world.
 *
 * CLIENT-side render thread only.
 */
abstract class GirlRendererBase<G : AbstractNpcOnlyEntity>(
    ctx: EntityRendererProvider.Context,
    model: GeoModel<out AbstractNpcOnlyEntity>,
    shadowSize: Double,
) : GirlRenderer<G>(ctx, model, shadowSize) {
    /**
     * Bone tint lookup with per-entity caching (bone name hash + entity UUID);
     * recomputed only when absent. Call after any model-code change must go
     * through [clearBoneColors].
     */
    open fun getCachedBoneColor(bone: GeoBone): Vec3i {
        val boneName = bone.name
        val cacheKey = boneName.hashCode() + this.renderEntity.uuid.hashCode()
        var color = boneColorCache[cacheKey]
        if (color != null) {
            return color
        }

        color = this.getBoneColor(boneName)
        boneColorCache[cacheKey] = color
        return color
    }

    abstract fun getBoneColor(boneName: String): Vec3i

    /**
     * Renders the girl's held item on the `weapon` bone: applies the bone
     * transform, scales by [getDefaultScale], rotates by
     * [getItemRenderOffset], then renders the third-person item and re-binds
     * the entity texture.
     */
    override fun renderHeldItem(bone: GeoBone) {
        val heldStack = this.resolveHeldItemStack(null)
        val scale = this.getDefaultScale()
        val rotation = this.getItemRenderOffset(heldStack)
        if (heldStack != null) {
            val poseStack = this.renderCtx.poseStack
            poseStack.pushPose()
            try {
                MatrixHelper.applyBoneTransform(poseStack, bone)
                poseStack.scale(scale, scale, scale)
                poseStack.mulPose(Axis.XP.rotationDegrees(rotation.x.toFloat()))
                poseStack.mulPose(Axis.YP.rotationDegrees(rotation.y.toFloat()))
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotation.z.toFloat()))
                this.renderVanillaItem(heldStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
            } finally {
                poseStack.popPose()
            }
        }
    }

    open fun getDefaultScale(): Float = 1.0f

    open fun getItemRenderOffset(stack: ItemStack?): Vec3 = Vec3(-90.0, 0.0, 0.0)

    open fun tintBoneColor(color: Vec3i): Vec3i = color

    /**
     * Full custom-bone render pass (see class doc). Recurses the skeleton,
     * per bone: held item / trade overlay hooks, [onBoneProcessing],
     * bone transform push, cube + child rendering. Skipped for entities living
     * in the [SexWorldClient] preload world.
     */
    override fun renderCustomBones(bone: GeoBone, r: Float, g: Float, b: Float, a: Float, overlayAlpha: Double) {
        if (SexWorldClient.isPreloadWorld(this.renderEntity.level())) {
            return
        }

        val boneName = bone.name
        if (boneName == "weapon") {
            this.renderHeldItem(bone)
        }

        if (boneName == "itemRenderer" && this.renderEntity.getCurrentAction() == Action.PAYMENT) {
            this.renderTradeOverlay(bone)
        }

        this.onBoneProcessing(bone.name, bone)
        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, bone)
            if (!bone.isHidden) {
                for (cube in bone.cubes) {
                    poseStack.pushPose()
                    try {
                        this.currentRenderingBone = bone
                        this.renderCubeGeometry(cube, bone, r, g, b, a, overlayAlpha)
                    } finally {
                        poseStack.popPose()
                    }
                }

                for (childBone in bone.childBones) {
                    this.renderCustomBones(childBone, r, g, b, a, overlayAlpha)
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    override fun renderRecursively(bone: GeoBone, r: Float, g: Float, b: Float, a: Float) {
        this.renderCustomBones(bone, r, g, b, a, 0.0)
    }

    /**
     * Emits a cube's quads with per-vertex tint from the bone color (cached,
     * then light-tinted) and transformed normals (mirrored on zero-size faces).
     * `textureVOffset` shifts the texture U coordinate (used by custom-part texture
     * variants).
     */
    open fun renderCubeGeometry(cube: GeoCube, bone: GeoBone, r: Float, g: Float, b: Float, a: Float, textureVOffset: Double) {
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
            var boneColor = this.getCachedBoneColor(bone)
            boneColor = this.tintBoneColor(boneColor)
            val worldColor = BodyParts.getBoneWorldPosition(
                this,
                bone,
                Vec3((boneColor.x / 255.0f).toDouble(), (boneColor.y / 255.0f).toDouble(), (boneColor.z / 255.0f).toDouble()),
                normal,
            )
            this.emitQuad(quad, poseMatrix, normal, worldColor.x.toFloat(), worldColor.y.toFloat(), worldColor.z.toFloat(), a, textureVOffset)
        }
    }

    companion object {
        @JvmField
        val defaultColor: Vec3i = Vec3i(255, 255, 255)

        @JvmField
        var boneColorCache: HashMap<Int, Vec3i> = HashMap()

        @JvmStatic
        fun clearBoneColors() {
            boneColorCache.clear()
        }

        /**
         * Selects one child of a bone by index: sorts children by pivot Y (stable
         * part order), un-hides the chosen one and hides the rest.
         *
         * @return the chosen child bone
         */
        @JvmStatic
        fun getChildBone(bone: GeoBone, index: Int): GeoBone? {
            val children = bone.childBones
            var chosen: GeoBone? = null
            children.sortWith(Comparator.comparingDouble { it.pivotY.toDouble() })

            for (i in children.indices) {
                val child = children[i]
                if (index == i) {
                    chosen = child
                    chosen.setHidden(false)
                } else {
                    child.setHidden(true)
                }
            }

            return chosen
        }
    }
}
