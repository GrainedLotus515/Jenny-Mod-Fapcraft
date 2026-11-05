package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.gui.UnknownScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * <b>Role.</b> CLIENT-side renderer for animated ribbons (scene effects, editor
 * previews). Builds a wavy quad strip whose per-segment rotation is driven by
 * three [WaveFunction]s evaluated over time, then draws it as a colored
 * quad strip.
 *
 * <b>Pitfall.</b> [renderRibbonStrip] draws 4 faces per segment pair and
 * assumes a `POSITION_COLOR` quad consumer — do not change the vertex format or
 * the strip tears.
 *
 * Port notes (CLIENT-ONLY): the 1.12 `BufferBuilder`/`Tessellator` parameters
 * are replaced by the caller's [PoseStack] + [MultiBufferSource]; the quads go
 * into [RenderType.debugQuads] (POSITION_COLOR, translucent, no culling — the
 * callers disabled culling around the 1.12 draw).
 */
object RibbonRenderer {
    @JvmStatic
    fun renderRibbon(poseStack: PoseStack, bufferSource: MultiBufferSource, minecraft: Minecraft, config: RibbonConfig) {
        val basePoints = arrayOf(
            Vec3(-config.thickness.toDouble(), -config.advance.toDouble(), 0.0),
            Vec3(-config.thickness.toDouble(), config.advance.toDouble(), 0.0),
            Vec3(config.thickness.toDouble(), config.advance.toDouble(), 0.0),
            Vec3(config.thickness.toDouble(), -config.advance.toDouble(), 0.0),
        )
        var advanceVec = Vec3(0.0, 0.0, -config.length.toDouble())
        var widthVec = VectorMath.scale(advanceVec.normalize(), config.width.toDouble())
        val startQuad = basePoints.copyOf()
        val segments = ArrayList<Array<Vec3>>()
        val ticks = (minecraft.player?.tickCount ?: 0) + minecraft.timer.getGameTimeDeltaPartialTick(true)

        for (i in 0..config.segmentCount) {
            val progress = 1.0f - i.toFloat() / config.segmentCount
            val quad = Array(4) { j ->
                val point = basePoints[j]
                Vec3(point.x * progress, point.y, point.z).add(widthVec)
            }

            segments.add(quad)
            advanceVec = VectorMath.rotateByEuler(advanceVec, config.xWaveFn.getPoint(i, ticks), config.yWaveFn.getPoint(i, ticks), config.zWaveFn.getPoint(i, ticks))
            widthVec = widthVec.add(advanceVec)
        }

        val buffer = bufferSource.getBuffer(RenderType.debugQuads())
        val pose = poseStack.last().pose()
        renderRibbonStrip(buffer, pose, startQuad, segments[0], config.color)

        for (i2 in 0 until config.segmentCount - 1) {
            renderRibbonStrip(buffer, pose, segments[i2], segments[i2 + 1], config.color)
        }
    }

    @JvmStatic
    fun getWaveOffset(x: Float, frequency: Float, time: Float, index: Int, amplitude: Float): Float =
        (Math.sin((x * frequency + time * index).toDouble()) * amplitude).toFloat()

    private fun v(buffer: VertexConsumer, pose: Matrix4f, p: Vec3, color: UnknownScreen) {
        buffer.addVertex(pose, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).setColor(color.red, color.green, color.blue, color.alpha)
    }

    @JvmStatic
    fun renderRibbonStrip(buffer: VertexConsumer, pose: Matrix4f, quadA: Array<Vec3>, quadB: Array<Vec3>, color: UnknownScreen) {
        v(buffer, pose, quadA[1], color); v(buffer, pose, quadA[2], color); v(buffer, pose, quadB[2], color); v(buffer, pose, quadB[1], color)
        v(buffer, pose, quadA[0], color); v(buffer, pose, quadA[1], color); v(buffer, pose, quadB[1], color); v(buffer, pose, quadB[0], color)
        v(buffer, pose, quadA[2], color); v(buffer, pose, quadA[3], color); v(buffer, pose, quadB[3], color); v(buffer, pose, quadB[2], color)
        v(buffer, pose, quadA[0], color); v(buffer, pose, quadA[3], color); v(buffer, pose, quadB[3], color); v(buffer, pose, quadB[0], color)
    }

    fun interface WaveFunction {
        fun getPoint(index: Int, time: Float): Float
    }

    class RibbonConfig(
        @JvmField var color: UnknownScreen,
        @JvmField var width: Float,
        @JvmField var segmentCount: Int,
        @JvmField var length: Float,
        @JvmField var xWaveFn: WaveFunction,
        @JvmField var yWaveFn: WaveFunction,
        @JvmField var zWaveFn: WaveFunction,
        @JvmField var thickness: Float,
        @JvmField var advance: Float,
    ) {
        fun copy(): RibbonConfig = RibbonConfig(this.color, this.width, this.segmentCount, this.length, this.xWaveFn, this.yWaveFn, this.zWaveFn, this.thickness, this.advance)
    }
}
