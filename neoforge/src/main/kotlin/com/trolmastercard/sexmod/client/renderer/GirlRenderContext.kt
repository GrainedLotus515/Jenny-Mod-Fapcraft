package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation

/**
 * Per-frame render state of a [GirlRenderer] (new in the 1.21 port, PORTING.md §2.9).
 *
 * The 1.12 renderers threaded a `BufferBuilder` through every hook, flushed it
 * with `Tessellator.draw()` before vanilla item renders and re-bound textures
 * with `bindTexture`. In 1.21 the equivalent state is a [PoseStack], a
 * [MultiBufferSource] and the [VertexConsumer] of the current [RenderType]. Hooks
 * read it from here instead of taking a buffer parameter.
 *
 * - [switchTexture] replaces "`bindTexture(t)` + `buffer.begin(...)`": it picks the
 *   renderer's render type for `t` and fetches its consumer.
 * - [restoreBuffer] replaces "`buffer.begin(...)` after a vanilla item render": vanilla
 *   rendering through the same [MultiBufferSource] may end the batch of the current
 *   render type, so the consumer must be fetched again before more vertices are emitted.
 * - [resetTexture] switches back to the girl's own texture (the one active when
 *   `actuallyRender` started).
 *
 * CLIENT-side render thread only.
 */
class GirlRenderContext(private val renderer: GirlRenderer<*>) {
    @JvmField var poseStack: PoseStack = PoseStack()
    @JvmField var bufferSource: MultiBufferSource? = null
    @JvmField var renderType: RenderType? = null
    @JvmField var buffer: VertexConsumer? = null
    @JvmField var packedLight: Int = 0
    @JvmField var packedOverlay: Int = OverlayTexture.NO_OVERLAY
    @JvmField var partialTick: Float = 0.0f

    /** Texture of [renderType] (null when the render type was given by GeckoLib without a texture). */
    @JvmField var texture: ResourceLocation? = null

    /** The girl texture / render type captured when the model pass started (see [resetTexture]). */
    @JvmField var baseTexture: ResourceLocation? = null
    @JvmField var baseRenderType: RenderType? = null

    /**
     * Whether the current pass is a GeckoLib re-render (render layers). The buffer of a
     * re-render belongs to the layer, so it is never swapped.
     */
    @JvmField var isReRender: Boolean = false

    /** The consumer for the current render type; re-fetched if a previous batch was ended. */
    fun buffer(): VertexConsumer {
        val current = this.buffer
        if (current != null) {
            return current
        }

        this.restoreBuffer()
        return this.buffer!! // java-npe: rendering without a buffer source is a caller bug
    }

    /** Equivalent of `bindTexture(texture)` + `buffer.begin(...)`. */
    fun switchTexture(texture: ResourceLocation) {
        if (this.isReRender) {
            return
        }

        this.texture = texture
        this.renderType = this.renderer.getGirlRenderType(texture)
        this.restoreBuffer()
    }

    /** Re-acquires the consumer of the current render type (after vanilla rendering ended its batch). */
    fun restoreBuffer() {
        if (this.isReRender) {
            return
        }

        val source = this.bufferSource ?: return
        val type = this.renderType ?: return
        this.buffer = source.getBuffer(type)
    }

    /** Switches back to the girl's own texture (the state captured at the start of the model pass). */
    fun resetTexture() {
        if (this.isReRender) {
            return
        }

        this.texture = this.baseTexture
        this.renderType = this.baseRenderType
        this.restoreBuffer()
    }

    /** Captures the current texture/render type as the base for [resetTexture]. */
    fun captureBase() {
        this.baseTexture = this.texture
        this.baseRenderType = this.renderType
    }
}
