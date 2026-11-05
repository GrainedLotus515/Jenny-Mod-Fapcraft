package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BasicGirlEntity
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 * Billboard renderer for [BasicGirlEntity] (the "pyrocinical" girl): a
 * flat textured quad that always faces the player, with a multi-stage texture
 * state machine — idle, walking (two-frame bob), praising/practice, and a
 * 30-tick "fat" morph animation driven by the girl's `lastSoundTick`.
 *
 * **Texture selection.** [getFatTexture] picks: the fat morph frame
 * when the girl recently made a sound, the practice texture when the player is
 * nearly stationary, idle when the girl isn't moving, or alternating walk
 * frames otherwise. Scale grows 1.4x.. and alpha fades over the morph, then
 * shrinks back after 120 ticks.
 *
 * CLIENT-side render thread only. Positions are interpolated with
 * [RotationHelper.lerpVec3dDouble] (PROGRESS lerp — correct for render
 * interpolation, do not switch to the INT step variant).
 *
 * Port notes: [render] dispatches to [doRenderBasicGirl]; "lighting off + lightmap 240/240"
 * is a translucent entity quad at [LightTexture.FULL_BRIGHT].
 */
class BasicGirlRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<BasicGirlEntity>(ctx) {
    @JvmField
    var mc: Minecraft = Minecraft.getInstance()

    @JvmField
    var cachedTexture: ResourceLocation? = null

    @JvmField
    var lastTextureSwitchTime: Long = 0L

    override fun getTextureLocation(girl: BasicGirlEntity): ResourceLocation = IDLE_TEXTURE

    override fun render(girl: BasicGirlEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        poseStack.pushPose()
        try {
            // 1.12 coordinates are relative to the lerped local player
            GirlRenderer.translateToPlayerOrigin(poseStack, girl, partialTicks)
            this.doRenderBasicGirl(girl, entityYaw, partialTicks, poseStack, bufferSource)
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Renders the girl as a player-facing quad relative to the local player
     * (lerped positions, billboard rotation, scale from the morph progress,
     * alpha from the morph shrink), and fires the praising sound once per
     * minute when the practice texture first appears.
     */
    fun doRenderBasicGirl(girl: BasicGirlEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val localPlayer = this.mc.player ?: return
        val girlPos = RotationHelper.lerpVec3dDouble(Vec3(girl.xOld, girl.yOld, girl.zOld), girl.position(), partialTicks.toDouble())
        val playerPos = RotationHelper.lerpVec3dDouble(Vec3(localPlayer.xOld, localPlayer.yOld, localPlayer.zOld), localPlayer.position(), partialTicks.toDouble())
        val offset = girlPos.subtract(playerPos)
        val texture = this.getFatTexture(girl, Math.abs(offset.x) + Math.abs(offset.y) + Math.abs(offset.z))
        poseStack.pushPose()
        try {
            val alpha = this.getFatShrink(girl, partialTicks)
            poseStack.translate(offset.x, offset.y + this.getFatBobOffset(texture), offset.z)
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - this.entityRenderDispatcher.camera.yRot))
            val scale = 1.4f + this.getFatProgress(girl, partialTicks)
            poseStack.scale(scale, scale, scale)
            val pose = poseStack.last()
            val buffer = bufferSource.getBuffer(RenderType.entityTranslucent(texture))
            val light = LightTexture.FULL_BRIGHT
            buffer.addVertex(pose, -1.0f, 0.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, alpha).setUv(0.0f, 1.0f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 1.0f, 0.0f)
            buffer.addVertex(pose, 1.0f, 0.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, alpha).setUv(1.0f, 1.0f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 1.0f, 0.0f)
            buffer.addVertex(pose, 1.0f, 2.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, alpha).setUv(1.0f, 0.0f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 1.0f, 0.0f)
            buffer.addVertex(pose, -1.0f, 2.0f, 0.0f).setColor(1.0f, 1.0f, 1.0f, alpha).setUv(0.0f, 0.0f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 1.0f, 0.0f)
        } finally {
            poseStack.popPose()
        }

        val now = System.currentTimeMillis()
        if (this.cachedTexture !== PRACTICE_TEXTURE && texture === PRACTICE_TEXTURE && now > this.lastTextureSwitchTime + 60000L) {
            localPlayer.playSound(SoundHandler.MISC_PYRO[0], 1.0f, 1.0f)
            this.lastTextureSwitchTime = now
        }

        this.cachedTexture = texture
    }

    /**
     * Texture state machine (see class doc): fat morph frame when
     * `lastSoundTick` is active, practice texture when the player is
     * nearly still (distance < 3), idle when the girl hasn't moved, otherwise
     * alternating walk frames.
     *
     * @param distance the player-relative distance to the girl
     */
    fun getFatTexture(girl: BasicGirlEntity, distance: Double): ResourceLocation {
        if (girl.lastSoundTick != -1) {
            return Main.rl(String.format("%s%s.png", "textures/entity/pyrocinical/fat/", this.getFatIndex(girl)))
        } else if (distance < 3.0) {
            return PRACTICE_TEXTURE
        } else {
            val movement = Vec3(girl.xOld, girl.yOld, girl.zOld).subtract(girl.position())
            return if (Math.abs(movement.x) + Math.abs(movement.y) + Math.abs(movement.z) == 0.0) {
                IDLE_TEXTURE
            } else {
                if (Math.sin((this.mc.player!!.tickCount * 0.75f).toDouble()) > 0.0) WALK_TEXTURE_1 else WALK_TEXTURE_2
            }
        }
    }

    fun getFatBobOffset(texture: ResourceLocation): Double {
        return if (WALK_TEXTURE_1 != texture && WALK_TEXTURE_2 != texture) 0.0 else Math.sin((this.mc.player!!.tickCount * 0.75f).toDouble()) * 0.1f
    }

    /**
     * Morph frame index 1..30, clamped from the time since the girl's last
     * sound.
     */
    fun getFatIndex(girl: BasicGirlEntity): Int {
        return if (girl.lastSoundTick == -1) 0 else ThreadNames.clampFloat((this.mc.player!!.tickCount - girl.lastSoundTick).toFloat(), 1.0f, 30.0f).toInt()
    }

    /**
     * Scale multiplier of the morph animation: 0 idle, ramps 0..1 over 30 ticks
     * after a sound, then holds.
     */
    fun getFatProgress(girl: BasicGirlEntity, partialTicks: Float): Float {
        if (girl.lastSoundTick == -1) {
            return 0.0f
        }

        val index = this.getFatIndex(girl)
        return if (index == 30) 1.0f else (index + partialTicks) / 30.0f
    }

    /**
     * Alpha multiplier for the morph: 1 normally, fades to 0 between 90 and 120
     * ticks after the sound (the girl "un-morphs" back to normal).
     */
    fun getFatShrink(girl: BasicGirlEntity, partialTicks: Float): Float {
        if (girl.lastSoundTick == -1) {
            return 1.0f
        }

        val ticks = this.mc.player!!.tickCount // java-npe: rendering implies a local player
        if (ticks - girl.lastSoundTick > 120) {
            return 0.0f
        }

        val elapsed = ThreadNames.clampFloat((ticks - girl.lastSoundTick).toFloat(), 90.0f, 120.0f) - 90.0f
        val fadeProgress = (elapsed + partialTicks) / 30.0f
        return 1.0f - fadeProgress
    }

    companion object {
        @JvmField val IDLE_TEXTURE: ResourceLocation = Main.rl("textures/entity/pyrocinical/standing.png")
        @JvmField val PRACTICE_TEXTURE: ResourceLocation = Main.rl("textures/entity/pyrocinical/praising.png")
        @JvmField val WALK_TEXTURE_1: ResourceLocation = Main.rl("textures/entity/pyrocinical/walking1.png")
        @JvmField val WALK_TEXTURE_2: ResourceLocation = Main.rl("textures/entity/pyrocinical/walking2.png")
        const val FAT_TEXTURE_PATH: String = "textures/entity/pyrocinical/fat/"
        const val FAT_MAX_TICKS: Int = 30
        const val SCALE_1_4: Float = 1.4f
        const val SCALE_0_75: Float = 0.75f
    }
}
