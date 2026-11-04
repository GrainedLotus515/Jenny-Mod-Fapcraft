package com.trolmastercard.sexmod.client.gui

import com.mojang.blaze3d.systems.RenderSystem
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.renderer.DragonStaffRenderer
import com.trolmastercard.sexmod.networking.CancelTaskPacket
import com.trolmastercard.sexmod.networking.FallTreePacket
import com.trolmastercard.sexmod.networking.MinePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendBlocksPacket
import com.trolmastercard.sexmod.networking.SetTribeFollowModePacket
import com.trolmastercard.sexmod.util.StructureMarkerRenderer
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import java.util.Collections

/**
 * Direction-pad command screen for the dragon staff: while the player holds
 * the mouse in one of four quadrants and releases (closes the screen), a
 * context-dependent command fires — bottom-left = mark/unmark the targeted
 * chest/bed ([SendBlocksPacket]), top-left = toggle tribe follow mode
 * ([SetTribeFollowModePacket]), bottom-right = toggle the staff's
 * rendering mode ([DragonStaffRenderer.toggleStaffRendering]),
 * top-right = fell the targeted log / mine the targeted breakable block
 * ([FallTreePacket] / [MinePacket] / [CancelTaskPacket]).
 *
 * Which quadrants are offered depends on the looked-at block: chest/bed
 * enable the mark command, logs and breakable materials (clay/rock/sand/
 * ground, within 3 blocks of ground support and below the player) enable the
 * mine command. Closing the screen commits the action with the highest
 * quadrant accumulation — must fire exactly once.
 *
 * CLIENT-side only; any mouse release closes the screen.
 *
 * 1.21 port: 1.12 `Material` sets have no equivalent; "breakable material"
 * (CLAY/ROCK/SAND/GROUND) is approximated by the `mineable/pickaxe` and
 * `mineable/shovel` block tags, and `BlockLog` by `#minecraft:logs`.
 * `onGuiClosed` is [removed]; 1.12 drew no background.
 */
open class StructureCommandScreen : Screen(Component.empty()) {
    @JvmField
    var animProgress: Float = 0.0f

    @JvmField
    var animBottomLeft: Float = 0.0f

    @JvmField
    var animTopLeft: Float = 0.0f

    @JvmField
    var animBottomRight: Float = 0.0f

    @JvmField
    var animTopRight: Float = 0.0f

    @JvmField
    var targetBlockState: BlockState

    @JvmField
    var targetBlockPos: BlockPos

    @JvmField
    var targetFacing: Direction

    /** The graphics of the frame being drawn. */
    private var currentGraphics: GuiGraphics? = null

    init {
        val mc = Minecraft.getInstance()
        val hit = mc.hitResult
        if (hit is BlockHitResult) {
            this.targetBlockPos = hit.blockPos
            this.targetFacing = hit.direction.opposite
        } else {
            this.targetBlockPos = BlockPos.ZERO
            this.targetFacing = Direction.NORTH
        }

        this.targetBlockState = mc.level!!.getBlockState(this.targetBlockPos) // java-npe
    }

    /**
     * Commits the command of the most-accumulated quadrant (see class doc).
     * No-op when the player closed without holding a quadrant.
     */
    override fun removed() {
        super.removed()
        val animations = listOf(this.animBottomLeft, this.animTopLeft, this.animBottomRight, this.animTopRight)
        val maxAnim = Collections.max(animations)
        if (maxAnim != 0.0f) {
            if (this.animBottomLeft == maxAnim) {
                this.updateTargetState()
            }

            if (this.animTopLeft == maxAnim) {
                this.toggleFollowMode()
            }

            if (this.animBottomRight == maxAnim) {
                this.toggleStaffView()
            }

            if (this.animTopRight == maxAnim) {
                this.handleLogBlock()
            }
        }
    }

    /**
     * Toggles the mark state of the targeted chest/bed: sends
     * [SendBlocksPacket] with the inverse of the current
     * [StructureMarkerRenderer] mark. Only valid for beds and chests.
     */
    open fun updateTargetState() {
        val level = Minecraft.getInstance().level ?: return
        val state = level.getBlockState(this.targetBlockPos)
        if (state.block is BedBlock || state.block is ChestBlock) {
            PacketHandler.networkWrapper.sendToServer(SendBlocksPacket(this.targetBlockPos, !StructureMarkerRenderer.isMarked(this.targetBlockPos)))
        }
    }

    /**
     * Sends [SetTribeFollowModePacket] with the inverted current erasing
     * flag (`isErasing`).
     */
    open fun toggleFollowMode() {
        PacketHandler.networkWrapper.sendToServer(SetTribeFollowModePacket(!isErasing))
    }

    /** Toggles the dragon staff's renderer mode ([DragonStaffRenderer]). */
    open fun toggleStaffView() {
        DragonStaffRenderer.toggleStaffRendering()
    }

    /**
     * Log command: for a targeted log, sends [FallTreePacket], or
     * [CancelTaskPacket] when the position is already marked; same for a
     * minable material via [MinePacket]. Exactly one packet per close.
     */
    open fun handleLogBlock() {
        if (this.targetBlockState.`is`(BlockTags.LOGS)) {
            if (StructureMarkerRenderer.isMarked(this.targetBlockPos)) {
                PacketHandler.networkWrapper.sendToServer(CancelTaskPacket(this.targetBlockPos))
                return
            }

            PacketHandler.networkWrapper.sendToServer(FallTreePacket(this.targetBlockPos))
        }

        val materialTarget = this.getTargetMaterial()
        if (materialTarget != null) {
            if (StructureMarkerRenderer.isMarked(this.targetBlockPos)) {
                PacketHandler.networkWrapper.sendToServer(CancelTaskPacket(this.targetBlockPos))
                return
            }

            PacketHandler.networkWrapper.sendToServer(MinePacket(materialTarget[0] as BlockPos, materialTarget[1] as Direction))
        }
    }

    /**
     * Resolves the mining target for the looked-at block.
     *
     * @return `[groundPos, facing]` if the target material is breakable,
     *         the player is at/below the target, and the ground support is at
     *         most 3 blocks below; otherwise `null`
     */
    open fun getTargetMaterial(): Array<Any>? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        if (!isBreakableMaterial(level.getBlockState(this.targetBlockPos))) {
            return null
        }

        if (player.blockPosition().y > this.targetBlockPos.y) {
            return null
        }

        var groundPos = this.targetBlockPos

        while (level.getBlockState(groundPos.below().offset(this.targetFacing.opposite.normal)).isAir) {
            groundPos = groundPos.below()
        }

        return if (this.targetBlockPos.y - groundPos.y > 3) null else arrayOf(groundPos, this.targetFacing)
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    /**
     * Draws the four quadrant buttons with entrance animation and per-quadrant
     * hover accumulation (the values [removed] reads). Only the
     * quadrants valid for the targeted block are offered, and active states
     * (erasing/staff rendering/marked) overlay a highlight icon.
     */
    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        this.currentGraphics = guiGraphics
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()

        val tickLength = Minecraft.getInstance().timer.gameTimeDeltaTicks
        this.animProgress = Math.min(1.0f, this.animProgress + tickLength / 5.0f)

        val scale = this.easeOutBack(this.animProgress.toDouble()).toFloat()
        val offset = (1.0f - scale) * 100.0f
        this.animBottomLeft = this.animBottomLeft + (if (mouseX < this.width / 2 && mouseY > this.height / 2) 1 else -1) * tickLength
        this.animTopLeft = this.animTopLeft + (if (mouseX < this.width / 2 && mouseY < this.height / 2) 1 else -1) * tickLength
        this.animBottomRight = this.animBottomRight + (if (mouseX > this.width / 2 && mouseY > this.height / 2) 1 else -1) * tickLength
        this.animTopRight = this.animTopRight + (if (mouseX > this.width / 2 && mouseY < this.height / 2) 1 else -1) * tickLength
        this.animBottomLeft = ThreadNames.clampFloat(this.animBottomLeft, 0.0f, 1.0f)
        this.animTopLeft = ThreadNames.clampFloat(this.animTopLeft, 0.0f, 1.0f)
        this.animBottomRight = ThreadNames.clampFloat(this.animBottomRight, 0.0f, 1.0f)
        this.animTopRight = ThreadNames.clampFloat(this.animTopRight, 0.0f, 1.0f)
        val pose = guiGraphics.pose()
        pose.pushPose()
        try {
            pose.translate(this.width / 2.0f, this.height / 2.0f, 0.0f)
            pose.scale(scale, scale, scale)
            pose.pushPose()
            pose.scale(1.0f + this.animTopLeft * 0.5f, 1.0f + this.animTopLeft * 0.5f, 1.0f)
            this.drawTexturedModalRect(-62.0f + offset - this.animTopLeft * 15.0f, -62.0f + offset - this.animTopLeft * 15.0f, 0, 0, 64, 64)
            this.drawTopLeft(offset)
            if (isErasing) {
                this.drawTexturedModalRect(-62.0f + offset - this.animTopLeft * 15.0f, -62.0f + offset - this.animTopLeft * 15.0f, 128, 64, 64, 64)
            }

            pose.popPose()
            pose.pushPose()
            pose.scale(1.0f + this.animBottomRight * 0.5f, 1.0f + this.animBottomRight * 0.5f, 1.0f)
            this.drawTexturedModalRect(-2.0f - offset + this.animBottomRight * 15.0f, -2.0f - offset + this.animBottomRight * 15.0f, 0, 0, 64, 64)
            this.drawBottomRight(offset)
            if (DragonStaffRenderer.isRenderingStaff()) {
                this.drawTexturedModalRect(-2.0f - offset + this.animBottomRight * 15.0f, -2.0f - offset + this.animBottomRight * 15.0f, 128, 64, 64, 64)
            }

            pose.popPose()
            val block = this.targetBlockState.block
            val isChest = block is ChestBlock
            val isBed = block is BedBlock
            if (isChest || isBed) {
                pose.pushPose()
                pose.scale(1.0f + this.animBottomLeft * 0.5f, 1.0f + this.animBottomLeft * 0.5f, 1.0f)
                this.drawTexturedModalRect(-62.0f + offset - this.animBottomLeft * 15.0f, -2.0f - offset + this.animBottomLeft * 15.0f, 0, 0, 64, 64)
                if (isChest) {
                    this.drawBottomLeft(offset)
                }

                if (isBed) {
                    this.setBuildProgress(offset)
                }

                if (StructureMarkerRenderer.isMarked(this.targetBlockPos)) {
                    this.drawTexturedModalRect(-62.0f + offset - this.animBottomLeft * 15.0f, -2.0f - offset + this.animBottomLeft * 15.0f, 128, 64, 64, 64)
                }

                pose.popPose()
            }

            val isLog = this.targetBlockState.`is`(BlockTags.LOGS)
            val hasMaterialTarget = this.getTargetMaterial() != null
            if (isLog || hasMaterialTarget) {
                pose.pushPose()
                pose.scale(1.0f + this.animTopRight * 0.5f, 1.0f + this.animTopRight * 0.5f, 1.0f)
                this.drawTexturedModalRect(-2.0f - offset + this.animTopRight * 15.0f, -62.0f + offset - this.animTopRight * 15.0f, 0, 0, 64, 64)
                if (isLog) {
                    this.setEraseProgress(offset)
                }

                if (hasMaterialTarget) {
                    this.drawTopRight(offset)
                }

                if (StructureMarkerRenderer.isMarked(this.targetBlockPos)) {
                    this.drawTexturedModalRect(-2.0f - offset + this.animTopRight * 15.0f, -62.0f + offset - this.animTopRight * 15.0f, 128, 64, 64, 64)
                }

                pose.popPose()
            }
        } finally {
            pose.popPose()
            RenderSystem.disableBlend()
        }
    }

    /** 1.12 `drawTexturedModalRect(float x, float y, ...)` with [GUI_TEXTURE]. */
    open fun drawTexturedModalRect(x: Float, y: Float, u: Int, v: Int, w: Int, h: Int) {
        val guiGraphics = this.currentGraphics ?: return
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x, y, 0.0f)
        guiGraphics.blit(GUI_TEXTURE, 0, 0, u, v, w, h)
        pose.popPose()
    }

    open fun drawBottomRight(offset: Float) {
        this.drawTexturedModalRect(-2.0f - offset + this.animBottomRight * 15.0f, -2.0f - offset + this.animBottomRight * 15.0f, 192, 64, 64, 64)
    }

    open fun drawTopLeft(offset: Float) {
        this.drawTexturedModalRect(-62.0f + offset - this.animTopLeft * 15.0f, -62.0f + offset - this.animTopLeft * 15.0f, 64, 64, 64, 64)
    }

    open fun setEraseProgress(offset: Float) {
        this.drawTexturedModalRect(-2.0f - offset + this.animTopRight * 15.0f, -62.0f + offset - this.animTopRight * 15.0f, 64, 0, 64, 64)
    }

    open fun drawTopRight(offset: Float) {
        this.drawTexturedModalRect(-2.0f - offset + this.animTopRight * 15.0f, -62.0f + offset - this.animTopRight * 15.0f, 128, 0, 64, 64)
    }

    open fun setBuildProgress(offset: Float) {
        this.drawTexturedModalRect(-62.0f + offset - this.animBottomLeft * 15.0f, -2.0f - offset + this.animBottomLeft * 15.0f, 0, 64, 64, 64)
    }

    open fun drawBottomLeft(offset: Float) {
        this.drawTexturedModalRect(-62.0f + offset - this.animBottomLeft * 15.0f, -2.0f - offset + this.animBottomLeft * 15.0f, 192, 0, 64, 64)
    }

    open fun easeOutBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = 2.70158
        return 1.0 + c3 * Math.pow(t - 1.0, 3.0) + c1 * Math.pow(t - 1.0, 2.0)
    }

    /**
     * Any mouse release closes the screen, committing the held quadrant's
     * command ([removed]).
     */
    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        net.minecraft.client.Minecraft.getInstance().setScreen(null) // client-only screen: a container-close packet would close a menu the server just opened
        return super.mouseReleased(mouseX, mouseY, mouseButton)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/command.png")

        @JvmField
        var isErasing: Boolean = false

        /** 1.12 `breakableMaterials` = {CLAY, ROCK, SAND, GROUND} (see class doc). */
        @JvmStatic
        fun isBreakableMaterial(state: BlockState): Boolean =
            state.`is`(BlockTags.MINEABLE_WITH_PICKAXE) || state.`is`(BlockTags.MINEABLE_WITH_SHOVEL)
    }
}
