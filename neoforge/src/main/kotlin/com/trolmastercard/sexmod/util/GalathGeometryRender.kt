package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.gui.UnknownScreen
import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> CLIENT-side geometry construction for the Galath model's custom
 * translucent overlays (body/wing meshes drawn in the editor and in-game).
 * Builds quad meshes from bone offsets and rotation parameters: [buildBodyBoneMesh]
 * = wing mesh + body mesh; [buildBodyMesh] = body line + wing mesh.
 *
 * <b>Pitfalls.</b> The 8/12-point vertex layouts are indexed by the mesh
 * builders — reordering a vertex breaks the quads. Bones are resolved via
 * [BaseGirlEntity.getAnimationProcessor] and `getCachedBoneOffset`; a missing
 * bone yields zeroed vertices (tolerated, renders nothing).
 *
 * Port notes (CLIENT-ONLY): [renderMesh] writes into a POSITION_COLOR quad
 * consumer through the pose matrix (1.12 POSITION_TEX_COLOR with tex 0,0);
 * [renderGalathGeometry] gained the [PoseStack] it translates (1.12 used the
 * GL matrix); its pose must sit at the 1.12 origin (the lerped local player).
 */
object GalathGeometryRender {
    @JvmStatic
    fun buildBodyBoneMesh(
        girl: BaseGirlEntity, partialTicks: Float, leftBoneName: String, rightBoneName: String, midBoneName: String,
        innerRadius: Float, outerRadius: Float, innerLength: Float, outerLength: Float, wingBoneName: String,
    ): Array<Array<Vec3>> {
        val mesh = buildWingBoneMesh(girl, partialTicks, leftBoneName, rightBoneName, midBoneName, innerRadius, outerRadius, innerLength, outerLength, wingBoneName)
        return buildBodyMesh(mesh)
    }

    @JvmStatic
    fun buildBodyMesh(
        girl: BaseGirlEntity, partialTicks: Float, leftBoneName: String, rightBoneName: String,
        leftOffset: Vector3fSexmodSpecial, rightOffset: Vector3fSexmodSpecial,
    ): Array<Array<Vec3>> {
        val line = buildBodyMeshLine(girl, partialTicks, leftBoneName, rightBoneName, leftOffset, rightOffset)
        return buildWingMesh(line)
    }

    @JvmStatic
    fun buildBodyMeshLine(
        girl: BaseGirlEntity, partialTicks: Float, leftBoneName: String, rightBoneName: String,
        leftOffset: Vector3fSexmodSpecial, rightOffset: Vector3fSexmodSpecial,
    ): Array<Vec3> {
        val leftPos = girl.getCachedBoneOffset(leftBoneName)
        val rightPos = girl.getCachedBoneOffset(rightBoneName)
        val lx = leftOffset.x.toDouble()
        val ly = leftOffset.y.toDouble()
        val lz = leftOffset.z.toDouble()
        val rx = rightOffset.x.toDouble()
        val ry = rightOffset.y.toDouble()
        val rz = rightOffset.z.toDouble()
        val corners: Array<Vec3> = if (leftOffset.x == 0.0f && rightOffset.x == 0.0f) {
            arrayOf(
                Vec3(0.0, ly, lz), Vec3(0.0, -ly, lz), Vec3(0.0, -ly, -lz), Vec3(0.0, ly, -lz),
                Vec3(0.0, ry, rz), Vec3(0.0, -ry, rz), Vec3(0.0, -ry, -rz), Vec3(0.0, ry, -rz),
            )
        } else {
            arrayOf(
                Vec3(lx, ly, 0.0), Vec3(-lx, ly, 0.0), Vec3(-lx, -ly, 0.0), Vec3(lx, -ly, 0.0),
                Vec3(rx, ry, 0.0), Vec3(-rx, ry, 0.0), Vec3(-rx, -ry, 0.0), Vec3(rx, -ry, 0.0),
            )
        }

        for (i in corners.indices) {
            corners[i] = VectorMath.rotateByYaw(corners[i], partialTicks)
        }

        for (i2 in 0 until 4) {
            corners[i2] = corners[i2].add(leftPos)
        }

        for (i3 in 4 until 8) {
            corners[i3] = corners[i3].add(rightPos)
        }

        return corners
    }

    @JvmStatic
    fun buildWingMesh(c: Array<Vec3>): Array<Array<Vec3>> = arrayOf(
        arrayOf(c[0], c[1], c[2], c[3]),
        arrayOf(c[4], c[5], c[6], c[7]),
        arrayOf(c[1], c[2], c[6], c[5]),
        arrayOf(c[3], c[7], c[4], c[0]),
        arrayOf(c[1], c[0], c[4], c[5]),
        arrayOf(c[2], c[3], c[7], c[6]),
    )

    @JvmStatic
    fun buildWingBoneMesh(
        girl: BaseGirlEntity, partialTicks: Float, leftBoneName: String, rightBoneName: String, midBoneName: String,
        innerRadius: Float, outerRadius: Float, innerLength: Float, outerLength: Float, boneName: String,
    ): Array<Vec3> {
        val bone = girl.getAnimationProcessor()?.getBone(boneName) ?: return Array(12) { Vec3.ZERO }

        val rotY = TrigMath.toDegrees(bone.rotY)
        val rotZ = TrigMath.toDegrees(bone.rotZ)
        val leftPos = girl.getCachedBoneOffset(leftBoneName)
        val rightPos = girl.getCachedBoneOffset(rightBoneName)
        val midPos = girl.getCachedBoneOffset(midBoneName)
        val ir = innerRadius.toDouble()
        val or = outerRadius.toDouble()
        val il = innerLength.toDouble()
        val ol = outerLength.toDouble()
        val corners = arrayOf(
            Vec3(ir, 0.0, -or), Vec3(-ir, 0.0, -or), Vec3(-ir, 0.0, or), Vec3(ir, 0.0, or),
            Vec3(ir, or, 0.0), Vec3(-ir, or, 0.0), Vec3(-ir, -or, 0.0), Vec3(ir, -or, 0.0),
            Vec3(il, 0.0, -ol), Vec3(-il, 0.0, -ol), Vec3(-il, 0.0, ol), Vec3(il, 0.0, ol),
        )

        for (i in corners.indices) {
            corners[i] = VectorMath.rotateByYaw(corners[i], partialTicks)
        }

        for (i2 in 0 until 4) {
            corners[i2] = VectorMath.rotateByEuler(corners[i2], 0.0f, rotY, rotZ)
        }

        for (i3 in 0 until 4) {
            corners[i3] = corners[i3].add(leftPos)
        }

        for (i4 in 4 until 8) {
            corners[i4] = corners[i4].add(rightPos)
        }

        for (i5 in 8 until 12) {
            corners[i5] = corners[i5].add(midPos)
        }

        return corners
    }

    @JvmStatic
    fun buildBodyMesh(p: Array<Vec3>): Array<Array<Vec3>> = arrayOf(
        arrayOf(p[0], p[1], p[5], p[4]),
        arrayOf(p[1], p[2], p[6], p[5]),
        arrayOf(p[3], p[2], p[6], p[7]),
        arrayOf(p[0], p[4], p[7], p[3]),
        arrayOf(p[0], p[1], p[2], p[3]),
        arrayOf(p[4], p[5], p[9], p[8]),
        arrayOf(p[9], p[10], p[6], p[5]),
        arrayOf(p[10], p[11], p[7], p[6]),
        arrayOf(p[4], p[7], p[11], p[8]),
        arrayOf(p[8], p[9], p[10], p[11]),
    )

    @JvmStatic
    fun renderMesh(poseStack: PoseStack, buffer: VertexConsumer, mesh: Array<Array<Vec3>>, color: UnknownScreen) {
        val pose = poseStack.last().pose()
        for (row in mesh) {
            for (point in row) {
                buffer.addVertex(pose, point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                    .setColor(color.red, color.green, color.blue, color.alpha)
            }
        }
    }

    @JvmStatic
    fun renderGalathGeometry(minecraft: Minecraft, girl: BaseGirlEntity, partialTicks: Float, poseStack: PoseStack) {
        val player = minecraft.player
        if (player != null) {
            poseStack.translate(0.0, 0.01, 0.0)
            val renderer = minecraft.entityRenderDispatcher.getRenderer(girl)
            val renderEntity: Entity = (renderer as? GirlRenderer<*>)?.getRenderEntity(girl) ?: girl
            val girlPos = if (girl.isAnchored()) {
                girl.getTargetPosition()
            } else {
                RotationHelper.lerpVec3dDouble(Vec3(renderEntity.xOld, renderEntity.yOld, renderEntity.zOld), renderEntity.position(), partialTicks.toDouble())
            }
            val cameraPos = RotationHelper.lerpVec3dDouble(Vec3(player.xOld, player.yOld, player.zOld), player.position(), partialTicks.toDouble())
            var offset = girlPos.subtract(cameraPos)
            offset = girl.transformRenderOffset(offset, partialTicks)
            poseStack.translate(offset.x, offset.y, offset.z)
        }
    }
}
