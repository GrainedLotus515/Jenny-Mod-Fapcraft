package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import java.io.PrintWriter
import java.io.StringWriter
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Vec3i
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.util.ObfuscationReflectionHelper
import net.neoforged.neoforge.client.event.RenderHandEvent

/**
 * **Role.** CLIENT-side custom in-hand map rendering for transformed
 * player-girls: when the local player is a player-girl, holding a map (or
 * anything, while the item-switch animation is running) renders the map +
 * the girl's hand model in first person instead of the vanilla arm.
 *
 * **State.** Per-outfit hand model/texture/color from
 * [AbstractPlayerGirlEntity.getHandModel] etc.; `PROGRESS_SCALE`
 * derives from the vanilla [ItemInHandRenderer] equipped-progress fields read
 * via [ObfuscationReflectionHelper].
 *
 * **Pitfall (field names).** 1.12 had to use SRG names at runtime. NeoForge 1.21 runs on
 * Mojang names in production, so the single branch reads `oMainHandHeight` /
 * `mainHandHeight` (the 1.12 `prevEquippedProgressMainHand` / `equippedProgressMainHand`).
 *
 * Port notes: the GL matrix is the event's pose stack; the hand model (1.12
 * `ModelRenderer.render(0.175F)`) is drawn at 0.175 * 16 = 2.8x the vanilla 1/16 scale with
 * the hand colour as vertex colour; the map background / map contents use the vanilla map
 * render types; a reflection failure prints the trace to the local chat (1.12 sent it to the
 * server as a chat message).
 */
class InHandMapRenderer {
    @JvmField var mc: Minecraft = Minecraft.getInstance()
    @JvmField var PROGRESS_SCALE: Float = 2.0f
    @JvmField var isRendering: Boolean = false
    @JvmField var handModel: IVanillaModel? = null
    @JvmField var handTexture: ResourceLocation? = null
    @JvmField var handColor: Vec3i = Vec3i(255, 255, 255)
    @JvmField var ANIM_OFFSET: Float = 0.0f

    // per-event render state (the 1.12 GL matrix / bound buffer)
    private var poseStack: PoseStack = PoseStack()
    private var bufferSource: MultiBufferSource? = null
    private var packedLight: Int = 0

    /**
     * CLIENT: replaces the vanilla in-hand map render with a custom girl-hand
     * version while the player is transformed (holds a map). Renders the map
     * plane plus the girl's hand model.
     */
    @SubscribeEvent
    fun onRenderSpecificHand(event: RenderHandEvent) {
        val localPlayer = Minecraft.getInstance().player ?: return
        AbstractPlayerGirlEntity.rebuildPlayerGirlTableFromWorld()
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(localPlayer.uuid)
        if (playerGirl != null) {
            val outfitIndex = playerGirl.getOutfitIndex()
            this.handModel = playerGirl.getHandModel(outfitIndex)
            this.handTexture = Main.rl(playerGirl.getHandTexture(outfitIndex))
            this.handColor = playerGirl.getHandColor(outfitIndex)
            if (this.handModel == null) {
                Main.LOGGER.info("HAND IS NULL uwu did you forget to assign this girl a hand owo?")
            } else {
                this.mc = Minecraft.getInstance()
                this.poseStack = event.poseStack
                this.bufferSource = event.multiBufferSource
                this.packedLight = event.packedLight
                var prevProgress = 0.0f
                var curProgress = 0.0f

                try {
                    val itemRenderer = this.mc.entityRenderDispatcher.itemInHandRenderer
                    prevProgress = ObfuscationReflectionHelper.getPrivateValue<Float, ItemInHandRenderer>(ItemInHandRenderer::class.java, itemRenderer, "oMainHandHeight")!!
                    curProgress = ObfuscationReflectionHelper.getPrivateValue<Float, ItemInHandRenderer>(ItemInHandRenderer::class.java, itemRenderer, "mainHandHeight")!!
                    SceneDebug.log(SceneDebug.IN_HAND, "InHandMapRenderer: prev=%.3f cur=%.3f", prevProgress, curProgress)

                    this.PROGRESS_SCALE = 2.0f - (prevProgress + (curProgress - prevProgress) * event.partialTick)
                } catch (exception: Exception) {
                    Main.LOGGER.info("couldnt do the reflection thingy")
                    val stringWriter = StringWriter()
                    exception.printStackTrace(PrintWriter(stringWriter))
                    ClientHooks.displayChat(stringWriter.toString())
                }

                val player = this.mc.player!! // java-npe: checked above
                val swingProgress = player.getAttackAnim(event.partialTick)
                val stack = player.mainHandItem
                if (event.hand == InteractionHand.MAIN_HAND) {
                    if (stack.isEmpty || stack.item is MapItem) {
                        event.isCanceled = true
                        this.renderPlayerMap(stack, event.partialTick, player, this.PROGRESS_SCALE, swingProgress)
                        this.isRendering = true
                    } else if (curProgress < prevProgress) {
                        if (this.isRendering) {
                            event.isCanceled = true
                            this.renderPlayerMap(stack, event.partialTick, player, this.PROGRESS_SCALE, swingProgress)
                        }
                    } else {
                        this.isRendering = false
                    }
                } else if (player.offhandItem.item is MapItem) {
                    event.isCanceled = true
                    this.renderHandMap(HumanoidArm.LEFT, this.PROGRESS_SCALE - 1.0f, swingProgress, player.offhandItem)
                }
            }
        }
    }

    private fun handColorInt(): Int = FastColor.ARGB32.color(255, this.handColor.x, this.handColor.y, this.handColor.z)

    /** 1.12 `handModel.getModel().render(0.175F)`. */
    private fun renderHandModel() {
        val source = this.bufferSource ?: return
        val texture = this.handTexture ?: return
        val model = this.handModel ?: return
        this.poseStack.pushPose()
        try {
            val scale = 0.175f * 16.0f
            this.poseStack.scale(scale, scale, scale)
            model.getModel().render(this.poseStack, source.getBuffer(RenderType.entityCutoutNoCull(texture)), this.packedLight, OverlayTexture.NO_OVERLAY, this.handColorInt())
        } finally {
            this.poseStack.popPose()
        }
    }

    fun renderPlayerMap(stack: ItemStack, partialTicks: Float, player: AbstractClientPlayer, progressScale: Float, swingProgress: Float) {
        if (stack.item is MapItem) {
            if (player.offhandItem.isEmpty) {
                // jar-verified: delegate to the 4-arg overload (stack, player, swingProgress, partialTicks) —
                // the deobf once rerouted this to the 5-arg self-call (infinite recursion)
                this.renderPlayerMap(stack, player, swingProgress, partialTicks)
            } else {
                this.renderHandMap(HumanoidArm.RIGHT, progressScale - 1.0f, swingProgress, stack)
            }
        } else {
            this.renderMapView(swingProgress, partialTicks)
        }
    }

    fun renderHandMap(handSide: HumanoidArm, progressScale: Float, swingProgress: Float, stack: ItemStack) {
        val poseStack = this.poseStack
        val side = if (handSide == HumanoidArm.RIGHT) 1.0f else -1.0f
        poseStack.translate(side * 0.125f, -0.125f, 0.0f)
        if (!this.mc.player!!.isInvisible) {
            poseStack.pushPose()
            poseStack.mulPose(Axis.ZP.rotationDegrees(side * 10.0f))
            this.renderMapPlane(progressScale, swingProgress, handSide)
            poseStack.translate(-0.5f, -1.1f, 0.0f)
            if (handSide == HumanoidArm.RIGHT) {
                poseStack.translate(0.48f, 0.15f, 0.0f)
            } else {
                poseStack.translate(0.44f, 1.3f, 1.0f)
            }

            this.renderHandModel()
            poseStack.popPose()
        }

        poseStack.pushPose()
        poseStack.translate(side * 0.51f, -0.08f + progressScale * -1.2f, -0.75f)
        val swing = Mth.sqrt(swingProgress)
        val swingSin = Mth.sin(swing * Math.PI.toFloat())
        val swingSin2 = -0.5f * swingSin
        val swingSin3 = 0.4f * Mth.sin(swing * (Math.PI * 2).toFloat())
        val swingSqSin = -0.3f * Mth.sin(swingProgress * Math.PI.toFloat())
        poseStack.translate(side * swingSin2, swingSin3 - 0.3f * swingSin, swingSqSin)
        poseStack.mulPose(Axis.XP.rotationDegrees(swingSin * -45.0f))
        poseStack.mulPose(Axis.YP.rotationDegrees(side * swingSin * -30.0f))
        this.renderMapItem(stack)
        poseStack.popPose()
    }

    fun renderPlayerMap(stack: ItemStack, player: AbstractClientPlayer, swingProgress: Float, partialTicks: Float) {
        val poseStack = this.poseStack
        val pitch = player.xRotO + (player.xRot - player.xRotO) * partialTicks
        val swing = Mth.sqrt(swingProgress)
        val swingSin = -0.2f * Mth.sin(swingProgress * Math.PI.toFloat())
        val swingSin2 = -0.4f * Mth.sin(swing * Math.PI.toFloat())
        poseStack.translate(0.0f, -swingSin / 2.0f, swingSin2)
        val scale = this.calculateMapScale(pitch)
        poseStack.translate(0.0f, 0.04f + (this.PROGRESS_SCALE - 1.0f) * -1.2f + scale * -0.5f, -0.72f)
        poseStack.mulPose(Axis.XP.rotationDegrees(scale * -85.0f))
        poseStack.pushPose()
        poseStack.mulPose(Axis.YP.rotationDegrees(90.0f))
        this.renderMapHand(HumanoidArm.RIGHT)
        this.renderMapHand(HumanoidArm.LEFT)
        poseStack.popPose()
        val swingSin3 = Mth.sin(swing * Math.PI.toFloat())
        poseStack.mulPose(Axis.XP.rotationDegrees(swingSin3 * 20.0f))
        poseStack.scale(2.0f, 2.0f, 2.0f)
        this.renderMapItem(stack)
    }

    fun renderMapItem(stack: ItemStack) {
        val poseStack = this.poseStack
        val source = this.bufferSource ?: return
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        poseStack.scale(0.38f, 0.38f, 0.38f)
        poseStack.translate(-0.5f, -0.5f, 0.0f)
        poseStack.scale(0.0078125f, 0.0078125f, 0.0078125f)
        val pose = poseStack.last().pose()
        val buffer = source.getBuffer(RenderType.text(MAP_BACKGROUND))
        buffer.addVertex(pose, -7.0f, 135.0f, 0.0f).setColor(-1).setUv(0.0f, 1.0f).setLight(this.packedLight)
        buffer.addVertex(pose, 135.0f, 135.0f, 0.0f).setColor(-1).setUv(1.0f, 1.0f).setLight(this.packedLight)
        buffer.addVertex(pose, 135.0f, -7.0f, 0.0f).setColor(-1).setUv(1.0f, 0.0f).setLight(this.packedLight)
        buffer.addVertex(pose, -7.0f, -7.0f, 0.0f).setColor(-1).setUv(0.0f, 0.0f).setLight(this.packedLight)
        val level = this.mc.level ?: return
        val mapId = stack.get(DataComponents.MAP_ID)
        val mapData = MapItem.getSavedData(mapId, level)
        if (mapId != null && mapData != null) {
            this.mc.gameRenderer.mapRenderer.render(poseStack, source, mapId, mapData, false, this.packedLight)
        }
    }

    private fun renderMapHand(handSide: HumanoidArm) {
        val poseStack = this.poseStack
        poseStack.pushPose()
        val side = if (handSide == HumanoidArm.RIGHT) 1.0f else -1.0f
        poseStack.mulPose(Axis.YP.rotationDegrees(92.0f))
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * -41.0f))
        poseStack.translate(side * 0.3f, -1.1f, 0.45f)
        if (handSide == HumanoidArm.RIGHT) {
            poseStack.translate(0.63f, 0.36f, 0.0f)
        } else {
            poseStack.translate(1.6f, 0.35f, 0.0f)
        }

        this.renderHandModel()
        poseStack.popPose()
    }

    private fun calculateMapScale(pitch: Float): Float {
        var scale = 1.0f - pitch / 45.0f + 0.1f
        scale = Mth.clamp(scale, 0.0f, 1.0f)
        return -Mth.cos(scale * Math.PI.toFloat()) * 0.5f + 0.5f
    }

    fun renderMapView(progressScale: Float, partialTicks: Float) {
        val poseStack = this.poseStack
        poseStack.pushPose()
        this.renderMapPlane(this.PROGRESS_SCALE, progressScale, HumanoidArm.RIGHT)
        this.renderHandModel()
        poseStack.popPose()
    }

    private fun renderMapPlane(progressScale: Float, partialTicks: Float, handSide: HumanoidArm) {
        val poseStack = this.poseStack
        val isRight = handSide != HumanoidArm.LEFT
        val side = if (isRight) 1.0f else -1.0f
        val swing = Mth.sqrt(partialTicks)
        val offsetX = -0.3f * Mth.sin(swing * Math.PI.toFloat())
        val offsetY = 0.4f * Mth.sin(swing * (Math.PI * 2).toFloat())
        val offsetZ = -0.4f * Mth.sin(partialTicks * Math.PI.toFloat())
        poseStack.translate(side * (offsetX + 0.64000005f), offsetY + -0.6f + progressScale * -0.6f, offsetZ + -0.71999997f)
        poseStack.mulPose(Axis.YP.rotationDegrees(side * 45.0f))
        val swingSq = Mth.sin(partialTicks * partialTicks * Math.PI.toFloat())
        val swingSin = Mth.sin(swing * Math.PI.toFloat())
        poseStack.mulPose(Axis.YP.rotationDegrees(side * swingSin * 70.0f))
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * swingSq * -20.0f))
        poseStack.translate(side * -1.0f, 3.6f, 3.5f)
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * 120.0f))
        poseStack.mulPose(Axis.XP.rotationDegrees(200.0f))
        poseStack.mulPose(Axis.YP.rotationDegrees(side * -135.0f))
        poseStack.translate(side * 5.6f, 0.0f, 0.0f)
        poseStack.translate(0.5f, 1.1f, 0.0f)
    }

    companion object {
        private val MAP_BACKGROUND: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/map/map_background.png")
    }
}
