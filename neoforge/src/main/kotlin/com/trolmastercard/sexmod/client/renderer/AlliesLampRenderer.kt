package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.model.AlliesLampModel
import com.trolmastercard.sexmod.item.AlliesLampItem
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.Base64
import java.util.stream.Collectors
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.renderer.GeoItemRenderer
import software.bernie.geckolib.util.RenderUtil

/**
 * Item renderer for the Allies Lamp: renders the geckolib lamp model but
 * replaces the lamp texture with the local player's own Minecraft skin,
 * blended pixel-wise over the base texture (the skin's opaque pixels win).
 *
 * **Skin fetch.** [getSkin] downloads the skin from the Mojang
 * session server once (synchronous HTTP — do not call from the render thread
 * during frame setup on a fresh player) and caches it as a dynamic texture;
 * on any failure it falls back to the plain model texture. The player's arms
 * are hidden while he holds the lamp in first person (skin face would clip).
 *
 * CLIENT-side render thread only. No-argument constructor (PORTING.md §2.8 item template).
 *
 * Port note: the GL3 custom `render(GeoModel, ...)` pass is the GeckoLib bone recursion
 * ([renderRecursively] = Java `renderLampBone`); the skin texture is supplied through
 * [getTextureLocation]. Culling is off (GeckoLib's default `entityCutoutNoCull`), as 1.12 disabled it.
 */
class AlliesLampRenderer : GeoItemRenderer<AlliesLampItem>(AlliesLampModel()) {
    @JvmField
    var mc: Minecraft = Minecraft.getInstance()

    /**
     * Lazily fetches the local player's skin, overlays it onto the lamp's base
     * texture (non-transparent skin pixels replace base pixels) and caches the
     * result as a dynamic texture. Falls back to the plain model texture on any
     * error. Cache is static — persists across item renders.
     */
    fun getSkin(): ResourceLocation {
        var texture = lampTexture
        if (texture == null) {
            try {
                val profileUrl = URI(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" +
                        Minecraft.getInstance().player!!.uuid.toString().replace("-", ""), // java-npe: caught below
                ).toURL()
                val reader = BufferedReader(InputStreamReader(profileUrl.openStream()))
                val profileJson = reader.lines().collect(Collectors.joining())
                val valueStart = profileJson.indexOf("\"value\" : ")
                val valueContentStart = valueStart + 11
                val valueBuilder = StringBuilder()

                var i = 0
                while (profileJson[valueContentStart + i] != '"') {
                    valueBuilder.append(profileJson[valueContentStart + i])
                    i++
                }

                val decodedValue = String(Base64.getDecoder().decode(valueBuilder.toString()))
                val urlStart = decodedValue.indexOf("\"url\" : ")
                val urlContentStart = urlStart + 9
                val urlBuilder = StringBuilder()

                var j = 0
                while (decodedValue[urlContentStart + j] != '"') {
                    urlBuilder.append(decodedValue[urlContentStart + j])
                    j++
                }

                val skinUrl = URI(urlBuilder.toString()).toURL()
                val playerSkin = skinUrl.openStream().use { NativeImage.read(it) }
                val baseTexture = this.mc.resourceManager.open(AlliesLampModel().getTextureResource(null)).use { NativeImage.read(it) }

                for (x in 0 until baseTexture.width) {
                    for (y in 0 until baseTexture.height) {
                        val rgb = playerSkin.getPixelRGBA(x, y)
                        if (rgb != 0) {
                            baseTexture.setPixelRGBA(x, y, rgb)
                        }
                    }
                }

                texture = Minecraft.getInstance().textureManager.register("lamptex", DynamicTexture(baseTexture))
            } catch (e: Exception) {
                texture = AlliesLampModel().getTextureResource(null)
            }

            lampTexture = texture
        }

        return texture!!
    }

    override fun getTextureLocation(animatable: AlliesLampItem): ResourceLocation = this.getSkin()

    /**
     * Java `renderLampBone`: pushes the bone's transform and renders its cubes with the skin
     * texture, skipping the arm bones in first person while the lamp is in use
     * (`sexmodAllieInUse`).
     */
    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: AlliesLampItem,
        bone: GeoBone,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        poseStack.pushPose()
        try {
            RenderUtil.prepMatrixForBone(poseStack, bone)
            if (this.isNotArmBone(bone.name)) {
                this.renderLampEffect(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
            }
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Whether this bone may be drawn: arm bones are only drawn in third person
     * or when the lamp is not currently in use.
     */
    fun isNotArmBone(boneName: String): Boolean {
        val player = this.mc.player ?: return true
        return if (boneName != "leftArm" && boneName != "rightArm") {
            true
        } else {
            player.persistentData.getBoolean("sexmodAllieInUse") && ClientHooks.thirdPersonView() == 0
        }
    }

    /**
     * Renders a bone's cubes and recurses into its child bones, unless the
     * bone is hidden.
     */
    fun renderLampEffect(
        poseStack: PoseStack,
        lamp: AlliesLampItem,
        bone: GeoBone,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        if (!bone.isHidden) {
            for (cube in bone.cubes) {
                poseStack.pushPose()
                try {
                    this.renderCube(poseStack, cube, buffer, packedLight, packedOverlay, colour)
                } finally {
                    poseStack.popPose()
                }
            }

            for (childBone in bone.childBones) {
                this.renderRecursively(poseStack, lamp, childBone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
            }
        }
    }

    companion object {
        @JvmField
        var lampTexture: ResourceLocation? = null
    }
}
