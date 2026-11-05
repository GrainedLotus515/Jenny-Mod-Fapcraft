package com.trolmastercard.sexmod

import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Matrix4f
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Matrix utilities for geckolib bone rendering. Provides the bridge between
 * geckolib's bone transforms and the render matrix, used by renderers that must
 * place vanilla-rendered parts (or custom geometry) at a bone's exact pose.
 *
 * **CLIENT-side only.** The shared static `SCRATCH_MATRIX` is overwritten on
 * every call; callers must consume it immediately and must not hold references
 * across renders.
 *
 * Port note: in 1.12 the bone transforms lived in GeckoLib's own `MATRIX_STACK`
 * and had to be uploaded onto the OpenGL matrix with `multMatrix`. In 1.21 the
 * [PoseStack] handed to the bone recursion already holds the entity transform
 * plus every parent bone transform, so [applyBoneTransform] only has to add the
 * bone's pivot translation.
 */
object MatrixHelper {
    @JvmField
    val SCRATCH_MATRIX: FloatArray = FloatArray(16)

    /**
     * Applies a bone's model-space transform onto the render matrix: the pose
     * stack (already at the bone's parent transform) is translated to the bone's
     * rotation point (pixels converted to blocks, /16).
     *
     * Must be called on the render thread between pose pushes; the caller is
     * responsible for `pushPose`/`popPose` around it.
     */
    @JvmStatic
    fun applyBoneTransform(stack: PoseStack, bone: GeoBone) {
        stack.translate(bone.pivotX / 16.0f, bone.pivotY / 16.0f, bone.pivotZ / 16.0f)
    }

    /**
     * Flattens a [Matrix4f] into a 16-element float array in the vecmath
     * row-major order of the 1.12 code (`target[1]` is row 0 / column 1).
     * `target` must have length >= 16.
     */
    @JvmStatic
    fun toFloatArray(target: FloatArray, matrix: Matrix4f) {
        // joml mCR() is column C / row R; vecmath mRC is row R / column C
        target[0] = matrix.m00()
        target[1] = matrix.m10()
        target[2] = matrix.m20()
        target[3] = matrix.m30()
        target[4] = matrix.m01()
        target[5] = matrix.m11()
        target[6] = matrix.m21()
        target[7] = matrix.m31()
        target[8] = matrix.m02()
        target[9] = matrix.m12()
        target[10] = matrix.m22()
        target[11] = matrix.m32()
        target[12] = matrix.m03()
        target[13] = matrix.m13()
        target[14] = matrix.m23()
        target[15] = matrix.m33()
    }

    /** `right * left` (vecmath `right.clone().mul(left)`), as a new matrix. */
    @JvmStatic
    fun multiply(left: Matrix4f, right: Matrix4f): Matrix4f {
        val copy = Matrix4f(right)
        copy.mul(left)
        return copy
    }
}
