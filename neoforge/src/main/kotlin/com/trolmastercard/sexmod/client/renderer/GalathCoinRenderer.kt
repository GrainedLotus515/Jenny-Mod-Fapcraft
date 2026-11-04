package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.model.GalathCoinModel
import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.util.FastColor
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube
import software.bernie.geckolib.renderer.GeoItemRenderer
import software.bernie.geckolib.util.RenderUtil

/**
 * Item renderer for the Galath coin: renders the geckolib coin model with a
 * red tint, a special "pentagram" bone, and animation states driven by the
 * player's persistent NBT timestamps.
 *
 * <b>Animation.</b> While held, the coin alternates between an activation
 * spin-up and a deactivation fade over 2-second windows (timestamps
 * `sexmod:galath_coin_activation_time`/`_deactivation_time`):
 * during spin the lightmap/scale value is 240 -> 120 and the tint goes
 * bright -> dark; during fade it reverses. Idle (not held) coins bob with a
 * sine and stay bright red.
 *
 * CLIENT-side render thread only. The pentagram bone is drawn in a separate
 * full-bright pass with per-vertex tint; the rest uses normal lighting.
 *
 * Port notes: the 1.12 full `render(model, ...)` override is the top-level
 * branch of [renderRecursively] (GL4 still runs the animation pass in
 * `actuallyRender`). The pentagram lightmap value `v` becomes the packed light
 * `v | v << 16`; the tint is passed as the GL4 vertex colour. GL4 already
 * mirrors flat-cube normals (`fixInvertedFlatCube`), so the non-pentagram cube
 * pass is the default one.
 */
open class GalathCoinRenderer : GeoItemRenderer<GalathCoinItem>(GalathCoinModel()) {
    @JvmField
    var isFlipping: Boolean = false

    @JvmField
    var currentTint: Vector3fSexmodSpecial? = null

    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: GalathCoinItem,
        bone: GeoBone,
        renderType: RenderType,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        if (bone.parent != null) {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
            return
        }

        // top-level bone: the 1.12 render(model, ...) body
        var pentagramBone: GeoBone? = null
        this.isFlipping = false
        poseStack.pushPose()
        try {
            RenderUtil.prepMatrixForBone(poseStack, bone)

            for (child in bone.childBones) {
                if ("pentagram" == child.name) {
                    pentagramBone = child
                } else {
                    this.renderRecursively(poseStack, animatable, child, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
                }
            }

            val lightmapValue = this.getCoinScale(partialTick)
            val tint = this.getCoinColor()
            this.currentTint = tint
            var light = packedLight
            if (!GirlSavedData.debugEnabled) {
                val v = lightmapValue.toInt()
                light = v or (v shl 16)
            }

            this.isFlipping = true
            if (pentagramBone != null) {
                val tintColour = FastColor.ARGB32.colorFromFloat(1.0f, tint.x, tint.y, tint.z)
                super.renderRecursively(poseStack, animatable, pentagramBone, renderType, bufferSource, buffer, isReRender, partialTick, light, packedOverlay, tintColour)
            }
        } finally {
            this.isFlipping = false
            poseStack.popPose()
        }
    }

    override fun renderCube(poseStack: PoseStack, cube: GeoCube, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, colour: Int) {
        // flipping (pentagram) mode: the tint arrives as `colour`, see renderRecursively
        super.renderCube(poseStack, cube, buffer, packedLight, packedOverlay, colour)
    }

    /**
     * Resolves the coin's scale/brightness value: 120 (full-bright) while
     * spinning up, 240 (dim) while fading, else a sine bob for idle coins; in
     * debug mode it is pinned to 120.
     */
    fun getCoinScale(partialTicks: Float): Float {
        val player = mc.player ?: return this.getCoinBob(partialTicks)
        if (player.mainHandItem !== this.currentItemStack && player.offhandItem !== this.currentItemStack) {
            return this.getCoinBob(partialTicks)
        } else {
            val now = System.currentTimeMillis()
            val nbt = player.persistentData
            val activationTime = nbt.getLong(GalathCoinItem.ACTIVATION_TIME_KEY)
            val deactivationTime = nbt.getLong(GalathCoinItem.DEACTIVATION_TIME_KEY)
            return if (activationTime != 0L) {
                this.getCoinSpin(now, activationTime, partialTicks)
            } else if (deactivationTime != 0L) {
                this.getCoinFade(now, deactivationTime, partialTicks)
            } else {
                if (GirlSavedData.debugEnabled) 120.0f else this.getCoinBob(partialTicks)
            }
        }
    }

    fun getCoinFade(now: Long, start: Long, partialTicks: Float): Float {
        val elapsed = (now - start).toFloat()
        return if (elapsed < 1000.0f) {
            120.0f
        } else {
            if (elapsed <= 3000.0f) RotationHelper.lerp(120.0f, 240.0f, (elapsed - 1000.0f) / 2000.0f) else 240.0f
        }
    }

    fun getCoinSpin(now: Long, start: Long, partialTicks: Float): Float {
        val elapsed = (now - start).toFloat()
        return if (elapsed < 1000.0f) {
            240.0f
        } else {
            if (elapsed <= 3000.0f) RotationHelper.lerp(240.0f, 120.0f, (elapsed - 1000.0f) / 2000.0f) else 120.0f
        }
    }

    /**
     * Resolves the coin's tint color: dark while spinning up (lerping back to
     * bright over 2s), bright while fading, static bright when idle.
     */
    fun getCoinColor(): Vector3fSexmodSpecial {
        val player = mc.player ?: return COIN_COLOR
        if (player.mainHandItem !== this.currentItemStack && player.offhandItem !== this.currentItemStack) {
            return COIN_COLOR
        } else {
            val now = System.currentTimeMillis()
            val nbt = player.persistentData
            val activationTime = nbt.getLong(GalathCoinItem.ACTIVATION_TIME_KEY)
            val deactivationTime = nbt.getLong(GalathCoinItem.DEACTIVATION_TIME_KEY)
            return if (activationTime != 0L) {
                this.getCoinColorDark(activationTime, now)
            } else if (deactivationTime != 0L) {
                this.getCoinColor(deactivationTime, now)
            } else {
                if (GirlSavedData.debugEnabled) COIN_COLOR_DARK else COIN_COLOR
            }
        }
    }

    fun getCoinColor(start: Long, now: Long): Vector3fSexmodSpecial {
        val elapsed = (now - start).toFloat()
        return if (elapsed < 1000.0f) {
            COIN_COLOR_DARK
        } else {
            if (elapsed <= 3000.0f) RotationHelper.lerpVector3f(COIN_COLOR_DARK, COIN_COLOR, ((elapsed - 1000.0f) / 2000.0f).toDouble()) else COIN_COLOR
        }
    }

    fun getCoinColorDark(start: Long, now: Long): Vector3fSexmodSpecial {
        val elapsed = (now - start).toFloat()
        return if (elapsed < 1000.0f) {
            COIN_COLOR
        } else {
            if (elapsed <= 3000.0f) RotationHelper.lerpVector3f(COIN_COLOR, COIN_COLOR_DARK, ((elapsed - 1000.0f) / 2000.0f).toDouble()) else COIN_COLOR_DARK
        }
    }

    fun getCoinBob(partialTicks: Float): Float =
        (60.0 * Math.sin(((mc.player?.tickCount ?: 0) + partialTicks) * 0.05f.toDouble()) + 180.0).toFloat()

    companion object {
        @JvmField
        val COIN_COLOR: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.84705883f, 0.11764706f, 0.35686275f)

        @JvmField
        val COIN_COLOR_DARK: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.44705883f, 0.44705883f, 0.44705883f)
        const val ROTATION_SPEED: Float = 240.0f
        const val ROTATION_AMPLITUDE: Float = 120.0f
        const val BOB_SPEED: Float = 0.05f

        @JvmStatic
        val mc: Minecraft
            get() = Minecraft.getInstance()
    }
}
