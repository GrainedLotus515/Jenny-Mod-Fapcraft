package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.UnknownScreen
import com.trolmastercard.sexmod.entity.DragonEntity
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation

/**
 * Billboard renderer for the [DragonEntity] energy ball: a translucent
 * cyan/magenta quad pair (outer + inner core) that always faces the player.
 *
 * <b>Color animation.</b> At full scale (SCALE_1_0 == 1) the outer/inner
 * colors continuously lerp between cyan and magenta (sine of world time);
 * while shrinking (scale < 1, e.g. when the ball is consumed) both layers fade
 * from white-tinted to cyan, tracking the scale.
 *
 * CLIENT-side render thread only.
 *
 * Port notes: the pose stack arrives already at the interpolated entity
 * position relative to the camera, so the 1.12 manual dragon-minus-player
 * offset is gone; the two-axis `playerViewY/X` billboard (with the front-view
 * sign hack) is the dispatcher's camera orientation. Full-bright unlit
 * POSITION_TEX_COLOR quads use [RenderType.text] (lightmap pinned to 240).
 */
open class DragonRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<DragonEntity>(ctx) {
    @JvmField
    var mc: Minecraft = Minecraft.getInstance()

    init {
        instance = this
    }

    override fun getTextureLocation(dragon: DragonEntity): ResourceLocation = Main.rl("textures/entity/galath/energy_ball.png")

    /**
     * Draws the energy ball: full-bright, billboarded against the camera,
     * two-layer quad with animated colors (see class doc).
     */
    override fun render(dragon: DragonEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        poseStack.pushPose()
        try {
            poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation())
            val s = dragon.SCALE_1_0.toFloat()
            poseStack.scale(s, s, s)
            val outerColor: UnknownScreen
            val innerColor: UnknownScreen
            if (dragon.SCALE_1_0 == 1.0) {
                val time = (this.mc.level?.gameTime ?: 0L).toFloat() + partialTicks
                val lerpProgress = 0.5 * Math.sin(time * 0.5) + 0.5
                outerColor = RotationHelper.lerpColor(COLOR_CYAN, COLOR_MAGENTA, lerpProgress)
                innerColor = RotationHelper.lerpColor(COLOR_MAGENTA, COLOR_CYAN, lerpProgress)
            } else {
                outerColor = RotationHelper.lerpColor(COLOR_WHITE_ALPHA, COLOR_CYAN, dragon.SCALE_1_0)
                innerColor = RotationHelper.lerpColor(COLOR_WHITE_ALPHA, COLOR_CYAN, dragon.SCALE_1_0)
            }

            val buffer = bufferSource.getBuffer(RenderType.text(this.getTextureLocation(dragon)))
            this.renderDragonColor(poseStack, buffer, outerColor, 0.0f)
            poseStack.scale(0.75f, 0.75f, 0.75f)
            poseStack.translate(0.0f, 0.075f, 0.0f)
            this.renderDragonColor(poseStack, buffer, innerColor, 0.001f)
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Emits one billboard quad (0.5x0.5) with the given color and a tiny z
     * offset to avoid z-fighting between the two layers.
     */
    fun renderDragonColor(poseStack: PoseStack, buffer: VertexConsumer, color: UnknownScreen, zOffset: Float) {
        val pose = poseStack.last().pose()
        val light = LightTexture.FULL_BRIGHT
        buffer.addVertex(pose, -0.25f, 0.0f, zOffset).setColor(color.red, color.green, color.blue, color.alpha).setUv(0.0f, 0.0f).setLight(light)
        buffer.addVertex(pose, 0.25f, 0.0f, zOffset).setColor(color.red, color.green, color.blue, color.alpha).setUv(1.0f, 0.0f).setLight(light)
        buffer.addVertex(pose, 0.25f, 0.5f, zOffset).setColor(color.red, color.green, color.blue, color.alpha).setUv(1.0f, 1.0f).setLight(light)
        buffer.addVertex(pose, -0.25f, 0.5f, zOffset).setColor(color.red, color.green, color.blue, color.alpha).setUv(0.0f, 1.0f).setLight(light)
    }

    companion object {
        @JvmField
        var instance: DragonRenderer? = null

        @JvmField
        val COLOR_CYAN: UnknownScreen = UnknownScreen(0, 255, 251, 255)

        @JvmField
        val COLOR_MAGENTA: UnknownScreen = UnknownScreen(255, 0, 236, 255)

        @JvmField
        val COLOR_WHITE_ALPHA: UnknownScreen = UnknownScreen(255, 255, 255, 0)
    }
}
