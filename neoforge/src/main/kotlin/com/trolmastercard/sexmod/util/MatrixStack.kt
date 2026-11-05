package com.trolmastercard.sexmod.util

import java.util.Stack
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube

/**
 * Simple implementation of a matrix stack.
 *
 * Exact joml port of GeckoLib 3's `software.bernie.geckolib3.util.MatrixStack`
 * (javax.vecmath), used for the non-render bone math (`GirlBoneMath`,
 * `BaseGirlEntity.getBoneMatrixStack`). Every operation post-multiplies the
 * top matrix (`top = top * op`) like vecmath's `mul`; vecmath element
 * `mRC` (row, column) is joml `mCR()` (column, row), so the translation that
 * vecmath stores in `m03`/`m13`/`m23` is joml `m30()`/`m31()`/`m32()`.
 *
 * GL3 -> GL4 member mapping: `bone.rotationPointX` -> [GeoBone.getPivotX],
 * `bone.getPositionX()` -> [GeoBone.getPosX], `bone.getRotationX()` ->
 * [GeoBone.getRotX], `cube.pivot` / `cube.rotation` -> [GeoCube.pivot] /
 * [GeoCube.rotation] (records).
 *
 * COMMON class (no client types).
 */
class MatrixStack {
    private val model: Stack<Matrix4f> = Stack()
    private val normal: Stack<Matrix3f> = Stack()

    private val tempModelMatrix = Matrix4f()
    private val tempNormalMatrix = Matrix3f()

    @Suppress("unused")
    private val tempArray = FloatArray(16)

    init {
        val model = Matrix4f()
        val normal = Matrix3f()

        model.identity()
        normal.identity()

        this.model.add(model)
        this.normal.add(normal)
    }

    fun getModelMatrix(): Matrix4f = this.model.peek()

    fun getNormalMatrix(): Matrix3f = this.normal.peek()

    fun push() {
        this.model.add(Matrix4f(this.model.peek()))
        this.normal.add(Matrix3f(this.normal.peek()))
    }

    fun pop() {
        if (this.model.size == 1) {
            throw IllegalStateException("A one level stack can't be popped!")
        }

        this.model.pop()
        this.normal.pop()
    }

    /* Translate */

    fun translate(x: Float, y: Float, z: Float) {
        this.translate(Vector3f(x, y, z))
    }

    fun translate(vec: Vector3f) {
        this.tempModelMatrix.identity()
        this.tempModelMatrix.setTranslation(vec)

        this.model.peek().mul(this.tempModelMatrix)
    }

    fun moveToPivot(cube: GeoCube) {
        val pivot = cube.pivot()
        this.translate(pivot.x.toFloat() / 16, pivot.y.toFloat() / 16, pivot.z.toFloat() / 16)
    }

    fun moveBackFromPivot(cube: GeoCube) {
        val pivot = cube.pivot()
        this.translate(-pivot.x.toFloat() / 16, -pivot.y.toFloat() / 16, -pivot.z.toFloat() / 16)
    }

    fun moveToPivot(bone: GeoBone) {
        this.translate(bone.pivotX / 16, bone.pivotY / 16, bone.pivotZ / 16)
    }

    fun moveBackFromPivot(bone: GeoBone) {
        this.translate(-bone.pivotX / 16, -bone.pivotY / 16, -bone.pivotZ / 16)
    }

    fun translate(bone: GeoBone) {
        this.translate(-bone.posX / 16, bone.posY / 16, bone.posZ / 16)
    }

    /* Scale */

    fun scale(x: Float, y: Float, z: Float) {
        this.tempModelMatrix.identity()
        this.tempModelMatrix.m00(x)
        this.tempModelMatrix.m11(y)
        this.tempModelMatrix.m22(z)

        this.model.peek().mul(this.tempModelMatrix)

        if (x < 0 || y < 0 || z < 0) {
            this.tempNormalMatrix.identity()
            this.tempNormalMatrix.m00(if (x < 0) -1f else 1f)
            this.tempNormalMatrix.m11(if (y < 0) -1f else 1f)
            this.tempNormalMatrix.m22(if (z < 0) -1f else 1f)

            this.normal.peek().mul(this.tempNormalMatrix)
        }
    }

    fun scale(bone: GeoBone) {
        this.scale(bone.scaleX, bone.scaleY, bone.scaleZ)
    }

    /* Rotate */

    fun rotateX(radian: Float) {
        this.tempModelMatrix.identity()
        this.tempModelMatrix.rotationX(radian)

        this.tempNormalMatrix.identity()
        this.tempNormalMatrix.rotationX(radian)

        this.model.peek().mul(this.tempModelMatrix)
        this.normal.peek().mul(this.tempNormalMatrix)
    }

    fun rotateY(radian: Float) {
        this.tempModelMatrix.identity()
        this.tempModelMatrix.rotationY(radian)

        this.tempNormalMatrix.identity()
        this.tempNormalMatrix.rotationY(radian)

        this.model.peek().mul(this.tempModelMatrix)
        this.normal.peek().mul(this.tempNormalMatrix)
    }

    fun rotateZ(radian: Float) {
        this.tempModelMatrix.identity()
        this.tempModelMatrix.rotationZ(radian)

        this.tempNormalMatrix.identity()
        this.tempNormalMatrix.rotationZ(radian)

        this.model.peek().mul(this.tempModelMatrix)
        this.normal.peek().mul(this.tempNormalMatrix)
    }

    fun rotate(bone: GeoBone) {
        if (bone.rotZ != 0.0f) {
            this.rotateZ(bone.rotZ)
        }

        if (bone.rotY != 0.0f) {
            this.rotateY(bone.rotY)
        }

        if (bone.rotX != 0.0f) {
            this.rotateX(bone.rotX)
        }
    }

    fun rotate(bone: GeoCube) {
        val rotation = bone.rotation()
        val matrix4f = Matrix4f()
        val matrix3f = Matrix3f()

        this.tempModelMatrix.identity()
        matrix4f.rotationZ(rotation.z.toFloat())
        this.tempModelMatrix.mul(matrix4f)

        matrix4f.rotationY(rotation.y.toFloat())
        this.tempModelMatrix.mul(matrix4f)

        matrix4f.rotationX(rotation.x.toFloat())
        this.tempModelMatrix.mul(matrix4f)

        this.tempNormalMatrix.identity()
        matrix3f.rotationZ(rotation.z.toFloat())
        this.tempNormalMatrix.mul(matrix3f)

        matrix3f.rotationY(rotation.y.toFloat())
        this.tempNormalMatrix.mul(matrix3f)

        matrix3f.rotationX(rotation.x.toFloat())
        this.tempNormalMatrix.mul(matrix3f)

        this.model.peek().mul(this.tempModelMatrix)
        this.normal.peek().mul(this.tempNormalMatrix)
    }
}
