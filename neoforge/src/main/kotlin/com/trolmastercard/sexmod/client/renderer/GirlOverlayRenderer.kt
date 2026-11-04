package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3

/**
 * Colored line overlays drawn on top of a girl (corruption / buff tints, see
 * [GirlRenderer.renderGirlColor]).
 *
 * **Geometry.** Lines are built from the girl's cached bone offsets
 * ([BaseGirlEntity.getCachedBoneOffset]), so they follow the animated
 * skeleton. [renderGirlTint] draws the `braString*` chain;
 * [renderGirlColor] translates the whole chain to the girl's render
 * position first and picks the `braString` tint.
 *
 * CLIENT-side render thread only: draws immediately (one line batch per segment, as
 * 1.12 drew one `Tessellator` batch per segment), sets the line width and
 * pushes/pops one pose.
 *
 * Port notes: the lines use the vanilla line shader (1.12 sampled texel 0,0 of
 * `textures/line.png` through `POSITION_TEX_COLOR`); `ActiveRenderInfo.getCameraPosition()
 * + playerPos` is the 1.21 camera position.
 */
object GirlOverlayRenderer {
    /** The pose the overlay lines are drawn with; set by [renderGirlColor]. */
    @JvmField
    var activePose: PoseStack.Pose? = null

    /**
     * Draws the colored line overlay (e.g. corrupted girl tint) anchored to the
     * girl (target position when anchored, else lerped) relative to the local
     * player, with line width from [getRenderOffset]. `renderer.renderCtx.poseStack`
     * must be at the 1.12 origin (the lerped local player, see
     * [GirlRenderer.translateToPlayerOrigin]).
     */
    @JvmStatic
    fun renderGirlColor(renderer: GirlRenderer<*>, girl: BaseGirlEntity, partialTicks: Float, overlayColor: Vector3fSexmodSpecial) {
        val localPlayer = Minecraft.getInstance().player ?: return
        val color = Vector3fSexmodSpecial(overlayColor.x / 255.0f, overlayColor.y / 255.0f, overlayColor.z / 255.0f)
        val poseStack = renderer.renderCtx.poseStack
        poseStack.pushPose()
        try {
            poseStack.translate(0.0, 0.01, 0.0)
            val renderEntity = renderer.getRenderEntity(girl)
            val girlPos = if (girl.isAnchored()) {
                girl.getTargetPosition()
            } else {
                RotationHelper.lerpVec3dDouble(Vec3(renderEntity.xOld, renderEntity.yOld, renderEntity.zOld), renderEntity.position(), partialTicks.toDouble())
            }
            val localPlayerPos = RotationHelper.lerpVec3dDouble(Vec3(localPlayer.xOld, localPlayer.yOld, localPlayer.zOld), localPlayer.position(), partialTicks.toDouble())
            val offset = girlPos.subtract(localPlayerPos)
            poseStack.translate(offset.x, offset.y, offset.z)
            activePose = poseStack.last()
            val lineWidth = getRenderOffset(girl, partialTicks, 1.0f, 5.0f)
            renderer.drawOverlayLines(girl, color, lineWidth)
        } finally {
            activePose = null
            poseStack.popPose()
        }
    }

    /**
     * Line width scaled by the girl's distance from the camera (5 blocks = max
     * width, closer = thinner), clamped 0..1 progress.
     */
    @JvmStatic
    fun getRenderOffset(girl: BaseGirlEntity, partialTicks: Float, minWidth: Float, maxWidth: Float): Float {
        val mc = Minecraft.getInstance()
        val renderer = mc.entityRenderDispatcher.getRenderer(girl)
        val renderEntity = if (renderer is GirlRenderer<*>) renderer.getRenderEntity(girl) else girl
        val girlPos = if (girl.isAnchored()) {
            girl.getTargetPosition()
        } else {
            RotationHelper.lerpVec3dDouble(Vec3(renderEntity.xOld, renderEntity.yOld, renderEntity.zOld), renderEntity.position(), partialTicks.toDouble())
        }
        val cameraPos = mc.gameRenderer.mainCamera.position
        val distance = cameraPos.distanceTo(girlPos).toFloat()
        val clampedDistance = Math.abs(distance) / 5.0f
        return RotationHelper.lerp(maxWidth, minWidth, ThreadNames.clampFloat(clampedDistance, 0.0f, 1.0f))
    }

    /**
     * Draws a single colored line segment between two cached bone offsets.
     */
    @JvmStatic
    fun renderNameLabel(girl: BaseGirlEntity, startBone: String, endBone: String, r: Float, g: Float, b: Float, width: Float) {
        val pose = activePose ?: return
        val startPos = girl.getCachedBoneOffset(startBone)
        val endPos = girl.getCachedBoneOffset(endBone)
        var normal = endPos.subtract(startPos)
        normal = if (normal.lengthSqr() == 0.0) Vec3(0.0, 1.0, 0.0) else normal.normalize()
        RenderSystem.lineWidth(width)
        RenderSystem.setShader { GameRenderer.getRendertypeLinesShader() }
        try {
            val builder = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL)
            builder.addVertex(pose, startPos.x.toFloat(), startPos.y.toFloat(), startPos.z.toFloat())
                .setColor(r, g, b, 1.0f)
                .setNormal(pose, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            builder.addVertex(pose, endPos.x.toFloat(), endPos.y.toFloat(), endPos.z.toFloat())
                .setColor(r, g, b, 1.0f)
                .setNormal(pose, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            BufferUploader.drawWithShader(builder.buildOrThrow())
        } finally {
            RenderSystem.lineWidth(1.0f)
        }
    }

    /**
     * Draws the bra-string tint chain (all `braString*` segments) in the
     * given color — used by the corruption/buff overlay renderers.
     */
    @JvmStatic
    fun renderGirlTint(girl: BaseGirlEntity, color: Vector3fSexmodSpecial, width: Float) {
        renderNameLabel(girl, "braStringMidStartR", "braStringMidMid1R", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidMid1R", "braStringMidMid2R", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidMid2R", "braStringMidMid3R", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidMid3R", "braStringMidEndR", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidEndR", "braStringBackR", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringBackR", "braStringRightEndR", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringRightEndR", "braStringRightStartR", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringRightR", "braStringRightL", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidStartL", "braStringMidMid1L", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidMid1L", "braStringMidMid2L", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidMid2L", "braStringMidMid3L", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidMid3L", "braStringMidEndL", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringMidEndL", "braStringBackL", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringBackL", "braStringLeftEndL", color.x, color.y, color.z, width)
        renderNameLabel(girl, "braStringLeftEndL", "braStringLeftStartL", color.x, color.y, color.z, width)
    }
}
