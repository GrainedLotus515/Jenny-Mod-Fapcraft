package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.model.GirlModel
import com.trolmastercard.sexmod.util.MatrixStack
import com.trolmastercard.sexmod.util.RotationHelper
import java.util.Collections
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.Level
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Client-side bone geometry for a girl: the world-transform matrix of a named
 * bone, the bone offset cache and the girlCam bone height.
 *
 * Extracted verbatim from [BaseGirlEntity]. The entity keeps its
 * methods as delegates, and the overridable hooks
 * (`applyAdditionalMatrixTransformations`, `transformCameraPivotY`,
 * `getCachedBoneOffset`) are called back through the girl instance so
 * subclass overrides still dispatch.
 *
 * Port note: uses the mod's joml port of GL3's matrix stack
 * ([MatrixStack]) and GL4 bones; the processor is GL4's
 * `AnimationProcessor.getBone(name)`, which returns a [GeoBone] directly.
 */
object GirlBoneMath {
    /**
     * CLIENT: computes the world-transform MatrixStack for a named model bone
     * (walks the bone parent chain, applies the girl's yaw when anchored).
     * @return empty stack if the bone is unknown
     */
    @JvmStatic
    fun getBoneMatrixStack(girl: BaseGirlEntity, boneName: String, usePivots: Boolean): MatrixStack {
        if (girl.cachedAnimationProcessor == null) {
            girl.cachedAnimationProcessor = girl.getAnimationProcessor()
        }

        val processor = girl.cachedAnimationProcessor ?: return MatrixStack()

        val bone: GeoBone? = processor.getBone(boneName)
        if (bone == null) {
            if (!GirlModel.CAMERA_PLACEMENTS.contains(boneName)) {
                Main.LOGGER.log(
                    Level.WARN,
                    String.format("The bone '%s' does not exist on %s. Bone model matrix couldn't be calculated", boneName, girl.getDisplayNameText()),
                )
                girl.boneTrackingList.remove(boneName)
            }

            return MatrixStack()
        } else {
            val currentBone: GeoBone = bone
            val chain = ArrayList<GeoBone>()
            var bone6: GeoBone = currentBone

            while (bone6.parent != null) {
                val parent: GeoBone = bone6.parent
                chain.add(parent)
                bone6 = parent
            }

            Collections.reverse(chain)
            val matrixStack = MatrixStack()
            if (girl.isAnchored()) {
                matrixStack.rotateY((-Math.toRadians(girl.getYawRotation().toDouble())).toFloat())
            } else if (usePivots) {
                matrixStack.rotateY(
                    (-Math.toRadians(RotationHelper.lerp(girl.yBodyRotO, girl.yBodyRot, ClientHooks.partialTick()).toDouble())).toFloat(),
                )
            }

            for (chainBone in chain) {
                matrixStack.translate(chainBone)
                matrixStack.moveToPivot(chainBone)
                matrixStack.rotate(chainBone)
                matrixStack.scale(chainBone)
                matrixStack.moveBackFromPivot(chainBone)
            }

            matrixStack.translate(currentBone)
            matrixStack.moveToPivot(currentBone)
            matrixStack.rotate(currentBone)
            matrixStack.scale(currentBone)
            return girl.applyAdditionalMatrixTransformations(matrixStack)
        }
    }

    /** @return the cached world offset of a bone, or ZERO (tracking the bone) */
    @JvmStatic
    fun getCachedBoneOffset(girl: BaseGirlEntity, boneName: String): Vec3 {
        val offset = girl.boneOffsetCache[boneName]
        if (offset != null) {
            return offset
        }

        if (!girl.boneTrackingList.contains(boneName)) {
            girl.boneTrackingList.add(boneName)
        }

        return Vec3.ZERO
    }

    /** @return the girl's position plus the cached offset of the named bone */
    @JvmStatic
    fun getBoneWorldPosition(girl: BaseGirlEntity, boneName: String): Vec3 {
        return girl.getCachedBoneOffset(boneName).add(girl.position())
    }

    @JvmStatic
    fun setBoneWorldPosition(girl: BaseGirlEntity, boneName: String, pos: Vec3) {
        girl.boneOffsetCache[boneName] = pos
    }

    /** CLIENT: height of the girlCam bone in world units (for camera placement). */
    @JvmStatic
    fun getCameraBoneHeight(girl: BaseGirlEntity): Float {
        val processor = girl.getAnimationProcessor() ?: return 0.0f

        val bone = processor.getBone("girlCam") ?: return 0.0f

        var pivotY = bone.pivotY
        pivotY = girl.transformCameraPivotY(pivotY)
        return pivotY / 16.0f
    }
}
