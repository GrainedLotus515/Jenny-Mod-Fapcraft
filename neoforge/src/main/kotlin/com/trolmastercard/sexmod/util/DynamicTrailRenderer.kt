package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.trolmastercard.sexmod.api.IPositionProvider
import com.trolmastercard.sexmod.api.ITargetProvider
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> CLIENT-side ribbon/trail renderer used by girl scene effects
 * (energy tendrils etc.). Maintains a ring of [TrailSegment]s that are
 * spawned from the source position provider, drift toward the target provider
 * with jitter, and are drawn as a sorted translucent polyline with per-segment
 * partial-tick interpolation.
 *
 * <b>Pitfalls.</b> [renderTrailSegments] insertion-sorts segments by
 * distance (bubble pass) so the strip renders back-to-front — reordering logic
 * must stay. [RotationHelper.lerpVec3dDouble] here is correct (render
 * interpolation by partial ticks); do not swap to the INT step variant.
 *
 * Port notes (CLIENT-ONLY class): [renderTrail] lost the 1.12
 * `Tessellator`/`BufferBuilder` parameters (§2.9 hook rule) and draws
 * immediately with the position-colour shader. The 1.12 GL mode 9
 * (`GL_POLYGON`) has no 1.21 equivalent; a convex polygon is drawn as
 * `TRIANGLE_FAN`. Vertices are made relative to the camera position (1.21
 * world-render space) instead of the interpolated player feet position.
 */
open class DynamicTrailRenderer(
    maxSegmentsCount: Int,
    sourcePositionProvider: IPositionProvider,
    targetPositionProvider: ITargetProvider,
    ownerEntity: BaseGirlEntity,
    randomnessRadius: Float,
    maxDistance: Float,
) {
    @JvmField
    var trailSegments: MutableList<TrailSegment> = ArrayList()

    @JvmField
    val maxSegmentsCount: Int = maxSegmentsCount

    @JvmField
    val sourcePositionProvider: IPositionProvider = sourcePositionProvider

    @JvmField
    val targetPositionProvider: ITargetProvider = targetPositionProvider

    @JvmField
    val ownerEntity: BaseGirlEntity = ownerEntity

    @JvmField
    val randomnessRadius: Float = randomnessRadius

    @JvmField
    val maxDistance: Float = maxDistance

    open fun renderTrail(minecraft: Minecraft, partialTicks: Float) {
        RenderSystem.disableCull()
        // 1.12 disableAlpha: the position-colour shader has no alpha test
        val targetPos = minecraft.gameRenderer.mainCamera.position
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        var buffer = begin()
        this.renderTrailSegments()
        var lastPos: Vec3? = null

        for (segment in this.trailSegments) {
            val segmentPos = RotationHelper.lerpVec3dDouble(segment.offset, segment.velocity, partialTicks.toDouble())
            if (lastPos == null) {
                lastPos = segmentPos
            }

            if (lastPos.distanceTo(segmentPos) > this.maxDistance) {
                draw(buffer)
                buffer = begin()
            }

            buffer.addVertex(
                (segmentPos.x - targetPos.x).toFloat(),
                (segmentPos.y - targetPos.y).toFloat(),
                (segmentPos.z - targetPos.z).toFloat(),
            ).setColor(255, 255, 255, 255)
            lastPos = segmentPos
        }

        draw(buffer)
        RenderSystem.enableCull()
    }

    private fun begin(): BufferBuilder =
        Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR)

    private fun draw(buffer: BufferBuilder) {
        val mesh = buffer.build() ?: return
        BufferUploader.drawWithShader(mesh)
    }

    open fun updateTrails() {
        if (this.trailSegments.size < this.maxSegmentsCount) {
            for (i in 0 until 6) {
                val sourcePos = this.sourcePositionProvider.getPosition(this.ownerEntity)
                this.trailSegments.add(
                    TrailSegment(
                        this.ownerEntity.level(),
                        this.targetPositionProvider.getTargetPosition(this.ownerEntity),
                        Vec3(
                            sourcePos.x + (Reference.RANDOM.nextFloat() * 2.0f - 1.0f) * this.randomnessRadius,
                            sourcePos.y + (Reference.RANDOM.nextFloat() * 2.0f - 1.0f) * this.randomnessRadius,
                            sourcePos.z + (Reference.RANDOM.nextFloat() * 2.0f - 1.0f) * this.randomnessRadius,
                        ),
                    ),
                )
            }
        }

        for (segment in this.trailSegments) {
            segment.onUpdate()
        }
    }

    open fun renderTrailSegments() {
        if (this.trailSegments.isNotEmpty() && this.trailSegments.size > 1) {
            for (i in 1 until this.trailSegments.size) {
                val segment = this.trailSegments[i]
                val velocity = segment.velocity

                var j = i - 1
                while (j >= 0 &&
                    velocity.distanceToSqr(this.trailSegments[j].velocity) < velocity.distanceToSqr(this.trailSegments[j + 1].velocity)
                ) {
                    this.trailSegments[j + 1] = this.trailSegments[j]
                    j--
                }

                this.trailSegments[j + 1] = segment
            }
        }
    }

    companion object {
        const val MAX_TICKS_AGE: Int = 30
        const val SEGMENTS_PER_TICK: Int = 6
        const val BATCH_SIZE: Int = 6
        const val ALPHA_THRESHOLD: Float = 0.15f
    }
}
