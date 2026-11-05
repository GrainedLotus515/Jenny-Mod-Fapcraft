package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.DragonStaffItem
import java.util.ConcurrentModificationException
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * **Role.** CLIENT-only overlay markers for tribe-managed blocks: the
 * dragon-staff UI and the `SendBlocksPacket` flow add/remove [BlockPos]s
 * from `markerPositions`; while the player holds the dragon staff, every
 * marked position gets a colored marker quad drawn in world space (blue = bed,
 * green = chest, red = mine target).
 *
 * **State.** `Reference.cameraPosPrevious/Current` are advanced in
 * [onClientTick] — `StructureMarkerRenderer` is the only writer of those fields.
 *
 * **Pitfall.** [renderMarkers] iterates the shared set during
 * world render while `SendBlocksPacket` handlers can mutate it from the
 * netty thread — the set is a [ConcurrentHashMap] key set so iteration is
 * safe; the concurrent-modification guard stays as a belt-and-braces.
 *
 * Port notes: drawn in [RenderLevelStageEvent] `AFTER_WEATHER` with an immediate
 * `POSITION_TEX_COLOR` batch, depth test and culling off (restored afterwards). The 1.12 world
 * origin was the lerped player position (`Reference.cameraPos*`); the 1.21 level pose stack
 * is camera-relative, so the markers are offset by the camera position instead.
 */
class StructureMarkerRenderer {
    @SubscribeEvent
    fun onRenderWorldLast(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return
        }

        val player = Minecraft.getInstance().player ?: return
        var stack = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (stack.item !== DragonStaffItem.DRAGON_STAFF) {
            stack = player.getItemInHand(InteractionHand.OFF_HAND)
        }

        if (stack.item !== DragonStaffItem.DRAGON_STAFF) {
            return
        }

        renderMarkers(event.poseStack, event.camera.position)
    }

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val player = Minecraft.getInstance().player
        if (player != null) {
            Reference.cameraPosPrevious = Reference.cameraPosCurrent
            Reference.cameraPosCurrent = player.position()
        }
    }

    companion object {
        @JvmField val COLOR_RED: Vec3i = Vec3i(255, 0, 0)
        @JvmField val COLOR_GREEN: Vec3i = Vec3i(0, 255, 0)
        @JvmField val COLOR_BLUE: Vec3i = Vec3i(0, 0, 255)
        @JvmField val MARK_TEXTURE: ResourceLocation = Main.rl("textures/mark.png")

        @JvmField
        var markerPositions: MutableSet<BlockPos> = ConcurrentHashMap.newKeySet()

        /** Per-marker colour memo, dropped whenever the marker set changes. */
        @JvmField
        val markerColors: MutableMap<BlockPos, Vec3i> = ConcurrentHashMap()

        @JvmStatic
        fun clearMarkers() {
            markerPositions.clear()
            markerColors.clear()
        }

        @JvmStatic
        fun isMarked(pos: BlockPos): Boolean = markerPositions.contains(pos)

        @JvmStatic
        fun renderMarkers(poseStack: PoseStack, cameraPos: Vec3) {
            poseStack.pushPose()
            try {
                RenderSystem.disableCull()
                RenderSystem.disableDepthTest()
                RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
                RenderSystem.setShaderTexture(0, MARK_TEXTURE)
                poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
                var any = false

                try {
                    for (pos in markerPositions) {
                        val color = getBlockColor(pos)
                        drawMarkerFace(buffer, poseStack, pos, color.x, color.y, color.z)
                        any = true
                    }
                } catch (exception: ConcurrentModificationException) {
                    // ignored: marker list mutated during the frame; markers are redrawn next frame
                }

                val mesh = buffer.build()
                if (any && mesh != null) {
                    BufferUploader.drawWithShader(mesh)
                } else {
                    mesh?.close()
                }
            } finally {
                RenderSystem.enableDepthTest()
                RenderSystem.enableCull()
                poseStack.popPose()
            }
        }

        @JvmStatic
        fun getBlockColor(pos: BlockPos): Vec3i {
            val cached = markerColors[pos]
            if (cached != null) {
                return cached
            }

            val level = Minecraft.getInstance().level ?: return COLOR_RED
            val block = level.getBlockState(pos).block
            val color = if (block is BedBlock) {
                COLOR_BLUE
            } else {
                if (block is ChestBlock) COLOR_GREEN else COLOR_RED
            }

            markerColors[pos] = color
            return color
        }

        @JvmStatic
        fun drawMarkerFace(buffer: BufferBuilder, poseStack: PoseStack, pos: BlockPos, red: Int, green: Int, blue: Int) {
            val pose = poseStack.last().pose()
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 1).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 1).toFloat(), (pos.z + 0).toFloat()).setUv(1.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 0).toFloat(), (pos.z + 0).toFloat()).setUv(1.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 0).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 1).toFloat(), (pos.z + 1).toFloat()).setUv(0.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 1).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 0).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 0).toFloat(), (pos.z + 1).toFloat()).setUv(0.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 1).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 1).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 0).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 0).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 1).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 1).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 0).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 0).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 0).toFloat(), (pos.z + 1).toFloat()).setUv(0.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 0).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 0).toFloat(), (pos.z + 0).toFloat()).setUv(1.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 0).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 1).toFloat(), (pos.z + 1).toFloat()).setUv(0.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 1).toFloat(), (pos.z + 1).toFloat()).setUv(1.0f, 1.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 1).toFloat(), (pos.y + 1).toFloat(), (pos.z + 0).toFloat()).setUv(1.0f, 0.0f).setColor(red, green, blue, 255)
        buffer.addVertex(pose, (pos.x + 0).toFloat(), (pos.y + 1).toFloat(), (pos.z + 0).toFloat()).setUv(0.0f, 0.0f).setColor(red, green, blue, 255)
        }

        @JvmStatic
        fun renderMarkers(markers: HashSet<BlockPos>) {
            markerPositions.addAll(markers)
            markerColors.clear()
        }

        @JvmStatic
        fun setMarkers(markers: HashSet<BlockPos>) {
            markerPositions.removeAll(markers)
            markerColors.clear()
        }
    }
}
